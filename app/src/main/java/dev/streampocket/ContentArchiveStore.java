package dev.streampocket;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Successful source identities, independent from task history and the local video catalogue. */
public final class ContentArchiveStore {
    static final String DATABASE = "content-archive.db";
    private ContentArchiveStore() {}

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) { super(context.getApplicationContext(), DATABASE, null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE completed_content (task_id TEXT PRIMARY KEY,content_key TEXT NOT NULL,variant_key TEXT NOT NULL,source_url TEXT NOT NULL,title TEXT NOT NULL,uri TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL,completed_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX completed_identity ON completed_content(content_key,completed_at DESC)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported content archive migration " + oldVersion + " -> " + newVersion);
        }
    }

    /** Empty means the source did not provide an identity safe enough for deduplication. */
    public static String contentKey(Engine.Media media) {
        if (media == null) return "";
        if (media.directFile()) {
            // Original requested URL, not a redirect target. Query bytes may identify a different file.
            String source = originalUrl(media.url);
            return source.isEmpty() ? "" : "file:v1:" + digest(source);
        }
        String namespace = media.extractorKey == null ? "" : media.extractorKey.trim().toLowerCase(Locale.ROOT);
        String id = media.id == null ? "" : media.id.trim();
        if (id.isEmpty() || "video".equals(id)) return "";
        if (namespace.isEmpty()) {
            try { namespace = new URI(media.url).getHost(); }
            catch (Exception invalid) { return ""; }
            if (namespace == null || namespace.isEmpty()) return "";
            namespace = namespace.toLowerCase(Locale.ROOT);
        }
        // Bili/X cards can expose the parent ID for several entries. Only then use the part.
        // Globally distinct child IDs remain stable when reached through a different playlist.
        int part = parentIdNeedsPart(media, namespace, id) ? Math.max(1, media.playlistIndex) : 0;
        return "content:v1:" + digest(namespace + "\n" + id + "\n" + part);
    }

    public static String variantKey(Engine.Media media, Engine.Quality quality) {
        if (media == null) return "";
        if (media.directFile()) return "file";
        if (media.album) return "images:zip";
        if (quality == null) return "";
        if (quality.audioOnly()) return "audio:" + quality.audioFormat;
        return "video:" + digest(quality.id + "\n" + quality.selector + "\n" + quality.compatibleMp4);
    }

    /** Call after the public save committed. Failed, partial and remote-only tasks never enter the ledger. */
    public static boolean recordCompleted(Context context, JSONObject task) throws Exception {
        if (task == null || !"completed".equals(task.optString("status"))) return false;
        String taskId = task.optString("id"), uri = task.optString("uri");
        if (taskId.isEmpty() || !localUri(uri) || task.optLong("size", -1) < 0) return false;
        JSONObject metadata = task.optJSONObject("media");
        if (metadata == null) return false;
        Engine.Media media = Engine.Media.fromJson(metadata);
        JSONObject selection = task.optJSONObject("quality");
        Engine.Quality quality = selection == null ? null : Engine.Quality.fromJson(selection);
        String content = contentKey(media), variant = variantKey(media, quality);
        if (content.isEmpty() || variant.isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("task_id", taskId); values.put("content_key", content); values.put("variant_key", variant);
        values.put("source_url", media.url); values.put("title", task.optString("title", media.title));
        values.put("uri", uri); values.put("mime", task.optString("mime", "application/octet-stream"));
        values.put("bytes", task.optLong("size")); values.put("completed_at", task.optLong("completedAt", System.currentTimeMillis()));
        try (Database database = new Database(context)) {
            if (database.getWritableDatabase().insertWithOnConflict("completed_content", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                throw new java.io.IOException("内容已保存，但归档记录写入失败");
        }
        return true;
    }

    /** History is evidence of a saved result, not proof that its URI is still accessible. */
    public static List<JSONObject> find(Context context, Engine.Media media, Engine.Quality quality) {
        List<JSONObject> result = new ArrayList<>(); String content = contentKey(media);
        if (content.isEmpty()) return result;
        String requested = variantKey(media, quality);
        try (Database database = new Database(context);
             Cursor rows = database.getReadableDatabase().query("completed_content", null, "content_key=?", new String[]{content},
                     null, null, "completed_at DESC,task_id")) {
            while (rows.moveToNext()) {
                JSONObject item = new JSONObject();
                try {
                    item.put("contentKey", rows.getString(rows.getColumnIndexOrThrow("content_key")))
                            .put("variantKey", rows.getString(rows.getColumnIndexOrThrow("variant_key")))
                            .put("taskId", rows.getString(rows.getColumnIndexOrThrow("task_id")))
                            .put("sourceUrl", rows.getString(rows.getColumnIndexOrThrow("source_url")))
                            .put("title", rows.getString(rows.getColumnIndexOrThrow("title")))
                            .put("uri", rows.getString(rows.getColumnIndexOrThrow("uri")))
                            .put("mime", rows.getString(rows.getColumnIndexOrThrow("mime")))
                            .put("size", rows.getLong(rows.getColumnIndexOrThrow("bytes")))
                            .put("completedAt", rows.getLong(rows.getColumnIndexOrThrow("completed_at")))
                            .put("sameVariant", requested.equals(rows.getString(rows.getColumnIndexOrThrow("variant_key"))));
                } catch (Exception invalid) { throw new IllegalStateException("归档记录无法读取", invalid); }
                result.add(item);
            }
        }
        result.sort((left, right) -> Boolean.compare(right.optBoolean("sameVariant"), left.optBoolean("sameVariant")));
        return result;
    }

    private static boolean parentIdNeedsPart(Engine.Media media, String namespace, String id) {
        String source = media.url == null ? "" : media.url;
        if ((namespace.contains("bili") || source.contains("bilibili.com/")) && source.contains("/video/" + id)) return true;
        return (namespace.equals("twitter") || namespace.equals("x") || source.contains("x.com/") || source.contains("twitter.com/"))
                && source.matches("(?s).*/status/" + java.util.regex.Pattern.quote(id) + "(?:[/?#].*)?");
    }

    private static String originalUrl(String url) {
        try {
            URI source = new URI(url);
            if (!("http".equalsIgnoreCase(source.getScheme()) || "https".equalsIgnoreCase(source.getScheme()))
                    || source.getHost() == null || source.getRawUserInfo() != null) return "";
            int fragment = url.indexOf('#'); return fragment < 0 ? url : url.substring(0, fragment);
        } catch (Exception invalid) { return ""; }
    }

    private static boolean localUri(String value) {
        try { String scheme = new URI(value).getScheme(); return "content".equals(scheme) || "file".equals(scheme); }
        catch (Exception invalid) { return false; }
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(); for (byte item : hash) text.append(String.format(Locale.ROOT, "%02x", item & 255));
            return text.toString();
        } catch (Exception unavailable) { throw new IllegalStateException("无法生成内容身份", unavailable); }
    }
}
