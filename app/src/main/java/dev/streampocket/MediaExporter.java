package dev.streampocket;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Publishes only completed media. Pending rows and partial copies are rolled back. */
public final class MediaExporter {
    private MediaExporter() {}
    public static final class Result {
        public final Uri uri;
        public final String mime;
        public final long bytes;
        public final String sha256;
        Result(Uri uri, String mime) { this(uri, mime, 0, ""); }
        Result(Uri uri, String mime, long bytes, String sha256) { this.uri=uri;this.mime=mime;this.bytes=bytes;this.sha256=sha256; }
    }

    public static Result save(Context context, File source, BooleanSupplier cancelled) throws Exception {
        return save(context, source, cancelled, DownloadLocation.snapshot(context));
    }

    static Result save(Context context, File source, BooleanSupplier cancelled, DownloadLocation.Target target) throws Exception {
        if (!source.isFile() || source.length() == 0) throw new IOException("完整媒体文件不存在或为空");
        String name = source.getName(); int dot = name.lastIndexOf('.');
        String mime = mimeForExtension(dot >= 0 ? name.substring(dot + 1) : "");
        return copyFrom(context, Uri.fromFile(source), name, mime, target, cancelled, created -> {});
    }

    /** The callback durably records an allocated destination before any destructive step. */
    static Result copyFrom(Context context, Uri source, String name, String mime, DownloadLocation.Target target,
                           BooleanSupplier cancelled, java.util.function.Consumer<Uri> onCreated) throws Exception {
        DownloadLocation.validate(context, target.tree);
        checkCancelled(cancelled);
        String safeName = name.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
        if (safeName.isEmpty()) safeName = "拾流文件";
        if (safeName.length() > 220) {
            int dot = safeName.lastIndexOf('.'); String suffix = dot >= 0 && safeName.length()-dot < 12 ? safeName.substring(dot) : "";
            safeName = safeName.substring(0, 220-suffix.length()) + suffix;
        }
        Uri destination = null; File legacy = null; boolean pending = false;
        try {
            if (target.tree != null) {
                destination = android.provider.DocumentsContract.createDocument(context.getContentResolver(), DownloadLocation.document(target), mime, safeName);
                if (destination == null) throw new IOException("无法在所选文件夹中创建文件，请检查授权或空间");
            } else if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS+"/StreamPocket");values.put(MediaStore.MediaColumns.IS_PENDING,1);
                destination=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);pending=true;
                if(destination==null)throw new IOException("无法创建下载文件，请检查手机存储空间");
            } else {
                File directory=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"StreamPocket");
                if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建下载目录，请允许存储权限");
                legacy=uniqueFile(directory,safeName);
                if(!legacy.createNewFile())throw new IOException("保存位置已有同名文件，请重试");
                destination=Uri.fromFile(legacy);
            }
            if (destination.equals(source)) throw new IOException("目标文件与原文件相同，已停止移动");
            onCreated.accept(destination);
            Digest copied;
            try(InputStream input=context.getContentResolver().openInputStream(source);
                OutputStream output=context.getContentResolver().openOutputStream(destination,"wt")) {
                if(input==null||output==null)throw new IOException("无法读取原文件或写入目标文件");
                copied=copyDigest(input,output,cancelled);
            }
            if(copied.bytes<=0)throw new IOException("原文件为空，未保存空文件");
            Digest stored=digest(context,destination,cancelled);
            if(copied.bytes!=stored.bytes||!copied.sha256.equals(stored.sha256))throw new IOException("目标文件回读校验失败，原文件已保留");
            checkCancelled(cancelled);
            if(pending) {
                ContentValues complete=new ContentValues();complete.put(MediaStore.MediaColumns.IS_PENDING,0);complete.put(MediaStore.MediaColumns.SIZE,copied.bytes);
                if(context.getContentResolver().update(destination,complete,null,null)<=0)throw new IOException("无法完成文件保存");
            } else if(legacy!=null) {
                ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DATA,legacy.getAbsolutePath());values.put(MediaStore.MediaColumns.DISPLAY_NAME,legacy.getName());
                values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.SIZE,copied.bytes);
                Uri indexed=context.getContentResolver().insert(MediaStore.Files.getContentUri("external"),values);
                if(indexed==null)throw new IOException("无法登记已保存文件");
                destination=indexed;onCreated.accept(destination);
            }
            return new Result(destination,mime,copied.bytes,copied.sha256);
        } catch(Exception failure) {
            if(destination!=null&&!destination.equals(source))try{delete(context,destination);}catch(Exception ignored){}
            if(legacy!=null)legacy.delete();
            throw failure;
        }
    }

    static final class Digest {
        final long bytes;final String sha256;
        Digest(long bytes,String sha256){this.bytes=bytes;this.sha256=sha256;}
    }
    static Digest digest(Context context,Uri uri,BooleanSupplier cancelled)throws Exception {
        try(InputStream input=context.getContentResolver().openInputStream(uri)) {
            if(input==null)throw new IOException("无法回读目标文件");
            return copyDigest(input,null,cancelled);
        }
    }
    private static Digest copyDigest(InputStream input,OutputStream output,BooleanSupplier cancelled)throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long count=0;byte[] buffer=new byte[131072];int n;
        while((n=input.read(buffer))!=-1){checkCancelled(cancelled);if(output!=null)output.write(buffer,0,n);digest.update(buffer,0,n);count+=n;}
        if(output!=null)output.flush();checkCancelled(cancelled);
        StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",value&255));
        return new Digest(count,hex.toString());
    }
    static boolean delete(Context context,Uri uri)throws Exception {
        if("file".equals(uri.getScheme()))return new File(uri.getPath()).delete();
        if(android.provider.DocumentsContract.isDocumentUri(context,uri))return android.provider.DocumentsContract.deleteDocument(context.getContentResolver(),uri);
        return context.getContentResolver().delete(uri,null,null)>0;
    }

    /** Complete a journaled default-directory copy before it can replace the original. */
    @SuppressWarnings("deprecation")
    static Uri completeRecovered(Context context,Uri uri,String name,String mime,long bytes)throws Exception {
        if(Build.VERSION.SDK_INT>=29&&"media".equals(uri.getAuthority())) {
            ContentValues complete=new ContentValues();complete.put(MediaStore.MediaColumns.IS_PENDING,0);complete.put(MediaStore.MediaColumns.SIZE,bytes);
            if(context.getContentResolver().update(uri,complete,null,null)<=0)throw new IOException("目标副本尚未完成公开保存，原件已保留");
        } else if(Build.VERSION.SDK_INT<29&&"file".equals(uri.getScheme())) {
            Uri collection=MediaStore.Files.getContentUri("external");
            try(android.database.Cursor existing=context.getContentResolver().query(collection,new String[]{MediaStore.MediaColumns._ID},MediaStore.MediaColumns.DATA+"=?",new String[]{uri.getPath()},null)) {
                if(existing!=null&&existing.moveToFirst())return android.content.ContentUris.withAppendedId(collection,existing.getLong(0));
            }
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DATA,uri.getPath());values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
            values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.SIZE,bytes);
            Uri indexed=context.getContentResolver().insert(collection,values);if(indexed==null)throw new IOException("目标文件尚未登记，原件已保留");return indexed;
        }
        return uri;
    }

    private static File uniqueFile(File directory, String name) {
        File file = new File(directory, name); int counter = 1;
        int dot = name.lastIndexOf('.'); String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        while (file.exists()) file = new File(directory, stem + " (" + counter++ + ")" + suffix);
        return file;
    }

    static String mimeForExtension(String extension) {
        switch (extension.toLowerCase(Locale.ROOT)) {
            case "mp4": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "m4a": return "audio/mp4";
            case "mp3": return "audio/mpeg";
            case "zip": return "application/zip";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "avif": return "image/avif";
            case "vtt": return "text/vtt";
            case "srt": return "application/x-subrip";
            case "ass": return "text/x-ssa";
            case "lrc": return "text/plain";
            case "ttml": return "application/ttml+xml";
            default:
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                return mime == null ? "application/octet-stream" : mime;
        }
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消保存");
    }
}
