package dev.streampocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Copies only recorded downloads; a durable per-file receipt precedes reference changes and deletion. */
public final class DownloadMover {
    static final Object FILE_LOCK = new Object();
    private DownloadMover() {}

    public static final class Result {
        public int moved, skipped, retained, failed;
        public boolean cancelled;
        public final List<String> details = new ArrayList<>();
        public String summary() {
            String text = "已移动 " + moved + " 个文件";
            if (skipped > 0) text += "，跳过 " + skipped + " 个（已在目标目录或仍在保存）";
            if (retained > 0) text += "，" + retained + " 个原件未删除，已保留两份";
            if (failed > 0) text += "，" + failed + " 个未完成，原文件已保留";
            if (cancelled) text += "。已停止后续移动";
            if (!details.isEmpty()) text += "\n" + String.join("\n", details.subList(0, Math.min(8, details.size())));
            return text;
        }
    }

    public static Result moveCompleted(Context context, BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
        synchronized (FILE_LOCK) {
            DownloadLocation.Target target = DownloadLocation.snapshot(context);
            Result result = new Result();
            try(LibraryStore library=new LibraryStore(context)){library.recoverDocumentRenames();}
            SharedPreferences journal = context.getSharedPreferences("download-moves", Context.MODE_PRIVATE);
            Set<String> blocked = new HashSet<>();
            for (Map.Entry<String,?> stored : journal.getAll().entrySet()) {
                if (!stored.getKey().startsWith("move-") || !(stored.getValue() instanceof String)) continue;
                JSONObject entry = new JSONObject((String) stored.getValue());
                if ("done".equals(entry.optString("phase"))) continue;
                if (cancelled.getAsBoolean()) { result.cancelled=true; return result; }
                try {
                    recover(context,journal,stored.getKey(),entry,cancelled,result);
                } catch (InterruptedIOException stopped) { result.cancelled=true; return result; }
                catch (Exception failure) { result.failed++;result.details.add("上次移动待恢复："+message(failure)); }
                JSONObject current = readEntry(journal,stored.getKey());
                if (current != null && !"done".equals(current.optString("phase"))) {
                    blocked.add(current.optString("from")); blocked.add(current.optString("to"));
                }
            }

            Map<String,JSONObject> files = new LinkedHashMap<>(); Set<String> busy = new HashSet<>();
            for (JSONObject task : TaskStore.all(context)) {
                if (!"completed".equals(task.optString("status"))) continue;
                List<JSONObject> parts = new ArrayList<>(); parts.add(task);
                JSONArray attachments=task.optJSONArray("attachments");
                if(attachments!=null)for(int i=0;i<attachments.length();i++)if(attachments.optJSONObject(i)!=null)parts.add(attachments.optJSONObject(i));
                for(JSONObject part:parts) {
                    String uri=part.optString("uri");if(uri.isEmpty())continue;
                    if(task.optBoolean("publicationBusy"))busy.add(uri);else files.putIfAbsent(uri,part);
                }
            }
            try(LibraryStore library=new LibraryStore(context)) {
                for(JSONObject file:library.downloadedFiles()) {
                    String uri=file.optString("uri");if(!uri.isEmpty())files.putIfAbsent(uri,file);
                }
            }
            int index=0;
            for(Map.Entry<String,JSONObject> file:files.entrySet()) {
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()){result.cancelled=true;break;}
                String source=file.getKey();
                if(blocked.contains(source))continue;
                if(busy.contains(source)){result.skipped++;continue;}
                Uri uri=Uri.parse(source);
                if(!"content".equals(uri.getScheme())&&!"file".equals(uri.getScheme())){result.failed++;continue;}
                if(inTarget(context,uri,target)||hasReceipt(journal,source,target.key())){result.skipped++;continue;}
                progress.accept("正在移动 "+(++index)+" / "+files.size());
                String key="move-"+UUID.randomUUID();
                JSONObject entry=new JSONObject().put("from",source).put("target",target.key()).put("label",target.label).put("phase","copying");
                try {
                    String name=displayName(context,uri,file.getValue());
                    String mime=file.getValue().optString("mime","");if(mime.isEmpty())mime=context.getContentResolver().getType(uri);
                    if(mime==null||mime.isEmpty())mime=MediaExporter.mimeForExtension(extension(name));
                    entry.put("name",name).put("mime",mime);writeEntry(journal,key,entry);
                    MediaExporter.Result copied=MediaExporter.copyFrom(context,uri,name,mime,target,cancelled,created->{
                        try {entry.put("to",created.toString());writeEntry(journal,key,entry);}
                        catch(Exception failure){throw new IllegalStateException("无法记录移动目标，原文件已保留",failure);}
                    });
                    entry.put("to",copied.uri.toString()).put("bytes",copied.bytes).put("sha256",copied.sha256).put("phase","copied");
                    writeEntry(journal,key,entry);
                    recover(context,journal,key,entry,cancelled,result);
                } catch(InterruptedIOException stopped) {
                    discardUncommitted(context,journal,key,entry);result.cancelled=true;break;
                } catch(Exception failure) {
                    result.failed++;result.details.add("未完成移动："+message(failure));
                    // A verified copy can be retried without another copy; originals stay until both stores commit.
                    if("copying".equals(entry.optString("phase")))discardUncommitted(context,journal,key,entry);
                }
            }
            return result;
        }
    }

    private static void recover(Context context,SharedPreferences journal,String key,JSONObject entry,BooleanSupplier cancelled,Result result)throws Exception {
        String destination=entry.optString("to");
        if(destination.isEmpty()){removeEntry(journal,key);return;}
        Uri from=Uri.parse(entry.getString("from")),to=Uri.parse(destination);
        if("copying".equals(entry.optString("phase"))) {
            if(!exists(context,to)){removeEntry(journal,key);return;}
            MediaExporter.Digest original=MediaExporter.digest(context,from,cancelled),copy=MediaExporter.digest(context,to,cancelled);
            if(original.bytes<=0||original.bytes!=copy.bytes||!original.sha256.equals(copy.sha256)) {
                if(hasReferences(context,destination)||!MediaExporter.delete(context,to))throw new IOException("未完成的目标副本无法清理，原件已保留");
                removeEntry(journal,key);return;
            }
            entry.put("bytes",copy.bytes).put("sha256",copy.sha256).put("phase","copied");writeEntry(journal,key,entry);
        }
        boolean committed="committed".equals(entry.optString("phase"));
        MediaExporter.Digest verified=MediaExporter.digest(context,to,committed?()->false:cancelled);
        if(verified.bytes!=entry.getLong("bytes")||!verified.sha256.equals(entry.getString("sha256")))throw new IOException("目标文件校验失败，原件未删除");
        if(!committed)checkCancelled(cancelled);
        if(!committed&&"default".equals(entry.optString("target"))) {
            Uri published=MediaExporter.completeRecovered(context,to,entry.optString("name","拾流文件"),entry.optString("mime","application/octet-stream"),verified.bytes);
            if(!published.equals(to)){to=published;entry.put("to",to.toString());writeEntry(journal,key,entry);}
        }
        // Cancellation is deferred across this commit/delete boundary. Both files exist throughout record writes.
        synchronized(TaskStore.class) {
            try(LibraryStore library=new LibraryStore(context)) {
                library.relocateUri(from.toString(),to.toString());
                TaskStore.relocateUri(context,from.toString(),to.toString(),entry.optString("label","自选文件夹"),entry.optString("target"));
                if(library.referencesUri(from.toString())||TaskStore.referencesUri(context,from.toString()))throw new IOException("文件引用未全部更新，原件已保留");
            }
        }
        entry.put("phase","committed");writeEntry(journal,key,entry);
        boolean deleted=false;
        try {deleted=MediaExporter.delete(context,from)||!exists(context,from);}catch(Exception refused){result.details.add("原件删除受限，文件引用已更新到目标副本");}
        if(deleted){
            result.moved++;entry.put("phase","done");
            try{writeEntry(journal,key,entry);}catch(IOException pendingReceipt){result.details.add("文件已移动，恢复标记稍后重试更新");}
        }
        else result.retained++;
    }

    private static void discardUncommitted(Context context,SharedPreferences journal,String key,JSONObject entry) {
        if("committed".equals(entry.optString("phase"))||"done".equals(entry.optString("phase")))return;
        try {
            String uri=entry.optString("to");
            if(uri.isEmpty()||(!hasReferences(context,uri)&&(!exists(context,Uri.parse(uri))||MediaExporter.delete(context,Uri.parse(uri)))))removeEntry(journal,key);
        }catch(Exception ignored){/* Receipt remains; a later explicit move can recover it. */}
    }
    private static boolean hasReferences(Context context,String uri)throws Exception {
        if(TaskStore.referencesUri(context,uri))return true;
        try(LibraryStore library=new LibraryStore(context)){return library.referencesUri(uri);}
    }
    private static boolean exists(Context context,Uri uri)throws Exception {
        try(InputStream input=context.getContentResolver().openInputStream(uri)){return input!=null;}
        catch(FileNotFoundException absent){return false;}
    }
    private static boolean hasReceipt(SharedPreferences journal,String uri,String target)throws Exception {
        for(Object value:journal.getAll().values())if(value instanceof String) {
            JSONObject entry=new JSONObject((String)value);
            if(uri.equals(entry.optString("to"))&&target.equals(entry.optString("target"))&&"done".equals(entry.optString("phase")))return true;
        }
        return false;
    }
    private static boolean inTarget(Context context,Uri uri,DownloadLocation.Target target) {
        try {
            if(target.tree!=null) {
                if(DocumentsContract.isTreeUri(uri)&&target.tree.getAuthority().equals(uri.getAuthority())
                        &&DocumentsContract.getTreeDocumentId(target.tree).equals(DocumentsContract.getTreeDocumentId(uri)))return true;
                return Build.VERSION.SDK_INT>=29&&DocumentsContract.isDocumentUri(context,uri)
                        &&DocumentsContract.isChildDocument(context.getContentResolver(),DownloadLocation.document(target),uri);
            }
            if("file".equals(uri.getScheme()))return new File(uri.getPath()).getCanonicalFile().getParentFile().equals(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"StreamPocket").getCanonicalFile());
            if(Build.VERSION.SDK_INT>=29&&"media".equals(uri.getAuthority())) {
                try(Cursor c=context.getContentResolver().query(uri,new String[]{MediaStore.MediaColumns.RELATIVE_PATH},null,null,null)) {
                    return c!=null&&c.moveToFirst()&&(Environment.DIRECTORY_DOWNLOADS+"/StreamPocket").equals(c.getString(0)==null?"":c.getString(0).replaceAll("/+$",""));
                }
            }
        }catch(Exception unavailable){/* A recorded receipt provides the other idempotence check. */}
        return false;
    }
    private static String displayName(Context context,Uri uri,JSONObject record)throws Exception {
        if("file".equals(uri.getScheme()))return new File(uri.getPath()).getName();
        try(Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(c!=null&&c.moveToFirst()&&!c.isNull(0)&&!c.getString(0).isEmpty())return c.getString(0);
        }
        String name=record.optString("name");return name.isEmpty()?record.optString("title","拾流文件"):name;
    }
    private static String extension(String name){int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot+1);}
    private static void writeEntry(SharedPreferences journal,String key,JSONObject entry)throws IOException {
        if(!journal.edit().putString(key,entry.toString()).commit())throw new IOException("移动记录未能持久保存，原文件已保留");
    }
    private static void removeEntry(SharedPreferences journal,String key)throws IOException {if(!journal.edit().remove(key).commit())throw new IOException("无法更新移动恢复记录");}
    private static JSONObject readEntry(SharedPreferences journal,String key)throws Exception {String value=journal.getString(key,null);return value==null?null:new JSONObject(value);}
    private static void checkCancelled(BooleanSupplier cancelled)throws InterruptedIOException {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("移动已取消，原文件已保留");}
    private static String message(Exception failure){String value=failure.getMessage();return value==null?"请检查文件权限后重试":value;}
}
