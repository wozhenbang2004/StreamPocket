package dev.streampocket;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Fast pre-publication checks; does not decode the entire movie on a phone. */
final class MediaValidation {
    static void rejectPage(File file) throws IOException {
        if (!file.isFile() || file.length() < 16) throw new IOException("下载文件为空或不完整，请重试");
        byte[] first = new byte[512]; int n;
        try (FileInputStream in = new FileInputStream(file)) { n = in.read(first); }
        String head = new String(first, 0, Math.max(0, n), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<?xml")
                || head.startsWith("{\"error") || head.contains("<title>site unavailable"))
            throw new IOException("源站返回了网页或错误信息，没有下载到媒体文件");
    }
    static void check(File file, Engine.Media media, Engine.Quality quality) throws Exception {
        rejectPage(file);
        containerBounds(file);
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            boolean video = false, audio = false; long durationUs = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                video |= mime != null && mime.startsWith("video/");
                audio |= mime != null && mime.startsWith("audio/");
                if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = Math.max(durationUs, f.getLong(MediaFormat.KEY_DURATION));
            }
            if (quality.audioOnly() ? !audio : !video) throw new IOException("下载文件缺少所选的媒体轨道，请重试");
            if (durationUs <= 0) throw new IOException("下载文件时长无效，未保存为完整媒体");
            if (media.duration > 0 && durationUs / 1000000d < media.duration - Math.max(3, media.duration * .05))
                throw new IOException("下载文件明显短于原内容，可能缺少分片，请重试");
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("下载文件无法识别，未保存为完整媒体", error); }
        finally { extractor.release(); }
    }

    /** A fast-start MP4 can expose a full duration even when its declared mdat is truncated. */
    static void containerBounds(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 8) return;
            in.seek(4); byte[] type = new byte[4]; in.readFully(type);
            String kind = new String(type, StandardCharsets.US_ASCII);
            if (!java.util.Arrays.asList("ftyp", "styp", "moov", "mdat", "free", "wide").contains(kind)) return;
            long position = 0, length = in.length(); int boxes = 0;
            while (position < length) {
                if (++boxes > 100000 || length - position < 8) throw new IOException("媒体容器不完整，请重试");
                in.seek(position); long size = Integer.toUnsignedLong(in.readInt()); in.skipBytes(4); long header = 8;
                if (size == 1) { if (length - position < 16) throw new IOException("媒体容器不完整"); size = in.readLong(); header = 16; }
                if (size == 0) size = length - position;
                if (size < header || size > length - position) throw new IOException("媒体文件已截断，未保存不完整内容");
                position += size;
            }
        }
    }
}
