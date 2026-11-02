package dev.streampocket;

import android.content.Context;
import android.graphics.BitmapFactory;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only explicitly selected, completed artifacts leave the private working directory. */
final class ContentFiles {
    static String image(File file) throws IOException {
        MediaValidation.rejectPage(file);
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("图片文件无法识别，未保存损坏图片");
        try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
            if("image/png".equals(bounds.outMimeType)) {
                if(in.length()<20)throw new IOException("图片已截断");
                in.seek(in.length()-12);if(in.readInt()!=0||in.readInt()!=0x49454e44)throw new IOException("图片已截断");
                return ".png";
            }
            if("image/webp".equals(bounds.outMimeType)) {
                in.seek(4);long declared=Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()))+8;
                if(declared>in.length())throw new IOException("图片已截断");return ".webp";
            }
            if("image/jpeg".equals(bounds.outMimeType)) {
                int size=(int)Math.min(4096,in.length());byte[] tail=new byte[size];in.seek(in.length()-size);in.readFully(tail);
                boolean end=false;for(int i=1;i<tail.length;i++)if((tail[i-1]&255)==255&&(tail[i]&255)==217)end=true;
                if(!end)throw new IOException("图片已截断");return ".jpg";
            }
        }
        if("image/avif".equals(bounds.outMimeType)){MediaValidation.containerBounds(file);return ".avif";}
        return ".gif";
    }
    static File album(File directory, String output, int count, String title, String postId, BooleanSupplier cancelled) throws Exception {
        LinkedHashSet<File> images = new LinkedHashSet<>();
        for (String line : output.split("\\r?\\n")) {
            File f = new File(line.trim());
            if (f.isFile() && f.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())
                    && f.getName().matches("(?i).+\\.(jpg|jpeg|png|webp|avif)")) images.add(f.getCanonicalFile());
        }
        if (count < 1 || count > 100 || images.size() != count) throw new IOException("图集图片数量不完整，请重试");
        Map<File,String> types=new HashMap<>();for (File f : images) types.put(f,image(f));
        String stem = (title + " [" + postId + "]").replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_");
        if(stem.length()>100)stem=stem.substring(0,100);
        File staged = new File(directory, "album.zip.part"), result = new File(directory, stem + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(staged))) {
            int i = 0; byte[] buffer = new byte[65536];
            for (File f : images) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("已取消图集保存");
                String extension = types.get(f);
                zip.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "%03d", ++i) + extension));
                try (InputStream in = new FileInputStream(f)) { int n; while ((n = in.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean()) throw new InterruptedIOException("已取消图集保存");
                    zip.write(buffer, 0, n);
                } }
                zip.closeEntry();
            }
        } catch (Exception error) { staged.delete(); throw error; }
        if (!staged.renameTo(result)) throw new IOException("无法完成图集保存");
        return result;
    }
    static final class Sidecars {
        final JSONArray saved = new JSONArray(); String warning = "", error = "";
    }
    static Sidecars publishSidecars(Context app, File directory, Engine.Media media, BooleanSupplier cancelled, java.util.function.Consumer<JSONArray> onSaved) {
        Sidecars result = new Sidecars();
        JSONArray saved = result.saved;
        try {
        File[] files = directory.listFiles(); if (files == null) return result;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            boolean picture = name.matches(".+\\.(jpg|jpeg|webp|png|avif)");
            boolean subtitle = name.matches(".+\\.(srt|vtt|ass|lrc|ttml)");
            if (!((media.saveCover && picture) || (media.saveSubtitles && subtitle))) continue;
            if (picture) image(file);
            else {
                if (file.length() < 8 || file.length() > 16 * 1024 * 1024) throw new IOException("字幕文件为空或过大");
                try (InputStream in = new FileInputStream(file)) {
                    byte[] header = new byte[512]; int n = in.read(header);
                    String text = new String(header, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (text.contains("<html") || text.contains("<!doctype html")) throw new IOException("源站没有返回有效字幕");
                }
            }
            MediaExporter.Result published = MediaExporter.save(app, file, cancelled);
            saved.put(new JSONObject().put("name", file.getName()).put("uri", published.uri.toString()).put("mime", published.mime));
            onSaved.accept(saved);
        }
        } catch (Exception failure) { result.warning = Engine.userMessage(failure); result.error = Engine.cleanLog(failure.toString()); }
        return result;
    }
}
