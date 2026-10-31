package dev.streampocket;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContentResolver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import java.io.Closeable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

/** Private, independent media catalogue. Call disk/DB methods from a worker thread. */
public final class LibraryStore implements Closeable {
    private static final Object RECOVERY_LOCK = new Object();
    private static boolean exportsRecovered;
    private final Context app;
    private final Database helper;

    public LibraryStore(Context context) {
        app = context.getApplicationContext();
        helper = new Database(app);
        helper.setWriteAheadLoggingEnabled(true);
    }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) { super(context, "media-library.db", null, 1); }
        @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE video (id INTEGER PRIMARY KEY AUTOINCREMENT,uri TEXT NOT NULL UNIQUE,title TEXT NOT NULL,mime TEXT NOT NULL,source TEXT NOT NULL,file_name TEXT NOT NULL DEFAULT '',duration_ms INTEGER NOT NULL DEFAULT 0,width INTEGER NOT NULL DEFAULT 0,height INTEGER NOT NULL DEFAULT 0,bytes INTEGER NOT NULL DEFAULT 0,playback_ms INTEGER NOT NULL DEFAULT 0,author TEXT NOT NULL DEFAULT '',source_url TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)");
            // A separate ledger prevents repeated imports, including after catalogue removal.
            db.execSQL("CREATE TABLE imported_task (task_id TEXT PRIMARY KEY,uri TEXT NOT NULL,video_id INTEGER REFERENCES video(id) ON DELETE SET NULL)");
            db.execSQL("CREATE INDEX video_created ON video(created_at DESC,id DESC)");
            db.execSQL("CREATE TABLE marker (id INTEGER PRIMARY KEY AUTOINCREMENT,video_id INTEGER NOT NULL REFERENCES video(id) ON DELETE CASCADE,position_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL DEFAULT 0,label TEXT NOT NULL DEFAULT '')");
            db.execSQL("CREATE INDEX marker_video ON marker(video_id,position_ms)");
            db.execSQL("CREATE TABLE collection (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE)");
            db.execSQL("CREATE TABLE collection_video (collection_id INTEGER NOT NULL REFERENCES collection(id) ON DELETE CASCADE,video_id INTEGER NOT NULL REFERENCES video(id) ON DELETE CASCADE,PRIMARY KEY(collection_id,video_id))");
            db.execSQL("CREATE TABLE edit_project (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,created_at INTEGER NOT NULL,export_state TEXT NOT NULL DEFAULT 'draft',export_progress INTEGER NOT NULL DEFAULT 0,export_detail TEXT NOT NULL DEFAULT '',output_uri TEXT NOT NULL DEFAULT '')");
            // Clips own small URI/title snapshots, never copies of the media. Removing a
            // catalogue entry therefore does not destroy an already arranged draft.
            db.execSQL("CREATE TABLE edit_clip (id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL REFERENCES edit_project(id) ON DELETE CASCADE,video_id INTEGER REFERENCES video(id) ON DELETE SET NULL,uri TEXT NOT NULL,title TEXT NOT NULL,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,position INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX clip_order ON edit_clip(project_id,position,id)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported media library migration " + oldVersion + " -> " + newVersion);
        }
    }

    public void syncCompletedDownloads() {
        Set<String> imported = new HashSet<>();
        try (Cursor c = helper.getReadableDatabase().query("imported_task", new String[]{"task_id"}, null, null, null, null, null)) {
            while (c.moveToNext()) imported.add(c.getString(0));
        }
        for (JSONObject task : TaskStore.all(app)) if ("completed".equals(task.optString("status"))) {
            if (imported.contains(task.optString("id"))) continue;
            upsertCompletedDownload(task);
        }
    }

    /** Explicit import can restore a removed entry; automatic queue sync cannot. */
    public synchronized long upsertCompletedDownload(JSONObject task) {
        if (task == null || !"completed".equals(task.optString("status"))) return 0;
        String uri = task.optString("uri", "").trim();
        if (!localUri(uri)) return 0;
        String taskId = task.optString("id", "").trim();
        if (taskId.isEmpty()) return 0;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor seen = db.query("imported_task", new String[]{"video_id"}, "task_id=?", new String[]{taskId}, null, null, null)) {
                if (seen.moveToFirst() && !seen.isNull(0)) { long id = seen.getLong(0); db.setTransactionSuccessful(); return id; }
            }
            JSONObject media = task.optJSONObject("media");
            if (media == null) media = new JSONObject();
            String url = task.optString("url", media.optString("url", ""));
            long duration = Math.max(0, media.optLong("duration"));
            duration = duration > Long.MAX_VALUE / 1000 ? 0 : duration * 1000;
            long id = findId(db, "video", "uri=?", new String[]{uri});
            if (id == 0) {
                ContentValues v = videoValues(task.optString("title", media.optString("title", "视频")), uri,
                        task.optString("mime", "video/mp4"), source(url, media.optString("platform")),
                        Math.max(0, task.optLong("size")), duration);
                v.put("author", media.optString("uploader", "")); v.put("source_url", url);
                v.put("width", Math.max(0, media.optInt("width"))); v.put("height", Math.max(0, media.optInt("height")));
                v.put("created_at", task.optLong("completedAt", task.optLong("createdAt", System.currentTimeMillis())));
                id = db.insertOrThrow("video", null, v);
            }
            ContentValues imported = new ContentValues(); imported.put("task_id", taskId); imported.put("uri", uri); imported.put("video_id", id);
            db.insertWithOnConflict("imported_task", null, imported, SQLiteDatabase.CONFLICT_REPLACE); db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }

    public List<JSONObject> listVideos(String query, String source, long collectionId, int limit, int offset) {
        return listVideos(query, source, collectionId, limit, offset, "date");
    }

    /** Sort is a fixed whitelist; user text is always a bound query parameter. */
    public List<JSONObject> listVideos(String query, String source, long collectionId, int limit, int offset, String order) {
        StringBuilder where = new StringBuilder("1=1"); List<String> args = new ArrayList<>();
        if (query != null && !query.trim().isEmpty()) {
            where.append(" AND (title LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\')");
            String pattern = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(pattern); args.add(pattern);
        }
        if (source != null && !source.isEmpty() && !source.equals("all")) { where.append(" AND source=?"); args.add(source); }
        if (collectionId > 0) { where.append(" AND id IN (SELECT video_id FROM collection_video WHERE collection_id=?)"); args.add(String.valueOf(collectionId)); }
        List<JSONObject> videos = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query("video", null, where.toString(), args.toArray(new String[0]), null, null,
                ("source".equals(order) ? "source COLLATE NOCASE,created_at DESC,id DESC" : "created_at DESC,id DESC"), Math.max(0, offset) + "," + Math.max(1, Math.min(100, limit)))) {
            while (c.moveToNext()) videos.add(readVideo(c));
        }
        return videos;
    }

    public JSONObject video(long id) {
        try (Cursor c = helper.getReadableDatabase().query("video", null, "id=?", strings(id), null, null, null)) {
            return c.moveToFirst() ? readVideo(c) : null;
        }
    }

    private JSONObject readVideo(Cursor c) {
        String uri = str(c, "uri"); long id = num(c, "id");
        return json("id", id, "title", str(c, "title"), "uri", uri, "mime", str(c, "mime"), "platform", str(c, "source"),
                "source", str(c, "source"), "fileName", str(c, "file_name"), "durationMs", num(c, "duration_ms"), "width", num(c, "width"), "height", num(c, "height"),
                "bytes", num(c, "bytes"), "createdAt", num(c, "created_at"), "playbackMs", num(c, "playback_ms"), "author", str(c, "author"), "sourceUrl", str(c, "source_url"),
                "thumbnailKey", id + "-" + Integer.toHexString(uri.hashCode()), "missing", !readable(uri));
    }

    private boolean readable(String value) {
        try (ParcelFileDescriptor fd = app.getContentResolver().openFileDescriptor(Uri.parse(value), "r")) { return fd != null; }
        catch (Exception unavailable) { return false; }
    }

    public void savePlayback(long videoId, long positionMs) {
        long duration = duration(videoId); ContentValues v = new ContentValues();
        v.put("playback_ms", duration > 0 ? Math.min(duration, Math.max(0, positionMs)) : Math.max(0, positionMs));
        helper.getWritableDatabase().update("video", v, "id=?", strings(videoId));
    }

    /** Persist actual container metadata discovered by playback or local probing. */
    public void updateMetadata(long videoId, long durationMs, int width, int height) {
        ContentValues v = new ContentValues();
        if (durationMs > 0) v.put("duration_ms", durationMs);
        if (width > 0) v.put("width", width);
        if (height > 0) v.put("height", height);
        if (v.size() == 0) return;
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try {
            db.update("video", v, "id=?", strings(videoId));
            if (durationMs > 0) db.execSQL("UPDATE video SET playback_ms=MIN(playback_ms,?) WHERE id=?", new Object[]{durationMs, videoId});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public long addMarker(long videoId, long positionMs, String label) {
        long duration = duration(videoId);
        if (positionMs < 0 || (duration > 0 && positionMs > duration)) throw new IllegalArgumentException("标记位置超出视频时长");
        ContentValues v = new ContentValues(); v.put("video_id", videoId); v.put("position_ms", positionMs); v.put("label", text(label, "", 200));
        return helper.getWritableDatabase().insertOrThrow("marker", null, v);
    }

    public long addRangeMarker(long videoId, long startMs, long endMs, String label) {
        long duration = duration(videoId);
        if (duration <= 0) throw new IllegalArgumentException("视频时长尚未读取，请播放视频后重试");
        if (startMs < 0 || endMs <= startMs || endMs > duration) throw new IllegalArgumentException("请设置有效的精彩片段起止时间");
        ContentValues v = new ContentValues(); v.put("video_id", videoId); v.put("position_ms", startMs); v.put("end_ms", endMs); v.put("label", text(label, "", 200));
        return helper.getWritableDatabase().insertOrThrow("marker", null, v);
    }

    public List<JSONObject> markers(long videoId) {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query("marker", null, "video_id=?", strings(videoId), null, null, "position_ms,id")) {
            while (c.moveToNext()) result.add(json("id", num(c, "id"), "videoId", num(c, "video_id"), "positionMs", num(c, "position_ms"), "startMs", num(c, "position_ms"), "endMs", num(c, "end_ms"), "label", str(c, "label")));
        } return result;
    }

    public void deleteMarker(long markerId) { helper.getWritableDatabase().delete("marker", "id=?", strings(markerId)); }
    public void renameMarker(long markerId, String label) {
        ContentValues v = new ContentValues(); v.put("label", text(label, "", 200));
        helper.getWritableDatabase().update("marker", v, "id=?", strings(markerId));
    }

    public void editMarker(long videoId,long markerId,long startMs,long endMs,String label){
        long duration=duration(videoId);
        if(startMs<0||duration<=0||startMs>duration||endMs!=0&&(endMs<=startMs||endMs>duration))throw new IllegalArgumentException("标记时间无效");
        ContentValues values=new ContentValues();values.put("position_ms",startMs);values.put("end_ms",endMs);values.put("label",text(label,"",200));
        if(helper.getWritableDatabase().update("marker",values,"id=? AND video_id=?",new String[]{Long.toString(markerId),Long.toString(videoId)})!=1)throw new IllegalArgumentException("标记已不存在");
    }

    public List<JSONObject> collections() {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT c.id,c.name,COUNT(cv.video_id) AS count FROM collection c LEFT JOIN collection_video cv ON c.id=cv.collection_id GROUP BY c.id ORDER BY c.name COLLATE NOCASE,c.id", null)) {
            while (c.moveToNext()) result.add(json("id", num(c, "id"), "name", str(c, "name"), "count", num(c, "count")));
        } return result;
    }

    public long createCollection(String name) {
        String valid = text(name, "", 80); if (valid.isEmpty()) throw new IllegalArgumentException("请输入合集名称");
        SQLiteDatabase db = helper.getWritableDatabase(); ContentValues v = new ContentValues(); v.put("name", valid);
        db.insertWithOnConflict("collection", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return findId(db, "collection", "name=?", new String[]{valid});
    }

    public void setCollection(long videoId, long collectionId, boolean included) {
        SQLiteDatabase db = helper.getWritableDatabase();
        if (included) { ContentValues v = new ContentValues(); v.put("collection_id", collectionId); v.put("video_id", videoId); db.insertWithOnConflict("collection_video", null, v, SQLiteDatabase.CONFLICT_IGNORE); }
        else db.delete("collection_video", "collection_id=? AND video_id=?", new String[]{String.valueOf(collectionId), String.valueOf(videoId)});
    }
    public List<Long> collectionIds(long videoId) {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query("collection_video", new String[]{"collection_id"}, "video_id=?", strings(videoId), null, null, null)) { while (c.moveToNext()) ids.add(c.getLong(0)); }
        return ids;
    }

    /** One transaction for a batch; failing one member does not leave half a selection changed. */
    public void setCollectionForVideos(List<Long> videoIds, long collectionId) {
        if(videoIds==null||videoIds.isEmpty()||collectionId<=0)throw new IllegalArgumentException("请选择视频和合集");
        SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
        try{
            for(Long id:videoIds){if(id==null||id<=0)throw new IllegalArgumentException("视频不存在");setCollection(id,collectionId,true);}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    /** Catalogue deletion preserves public media, queue history, and edit-clip URI snapshots. */
    public void removeVideoRecords(List<Long> videoIds) {
        if(videoIds==null||videoIds.isEmpty())throw new IllegalArgumentException("请选择视频");
        SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
        try{
            for(Long id:videoIds){if(id==null||id<=0)throw new IllegalArgumentException("视频不存在");db.delete("video","id=?",strings(id));}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    /** Removes catalogue metadata, never the user's public media file. */
    public void removeVideoRecord(long videoId) { helper.getWritableDatabase().delete("video", "id=?", strings(videoId)); }

    /** Rename the owned public file first. A catalogue-only rename is never reported as success. */
    public synchronized void renameVideo(long videoId, String newBaseName) {
        String base = validFileBaseName(newBaseName);
        JSONObject video = video(videoId);
        if (video == null || video.optBoolean("missing")) throw new IllegalArgumentException("原视频无法读取，请先恢复文件");
        Uri uri = Uri.parse(video.optString("uri"));
        if (!"content".equals(uri.getScheme())) throw new IllegalArgumentException("此文件地址暂不支持原地重命名");
        ContentResolver resolver = app.getContentResolver();
        String original = displayName(uri, true);
        String requested = base + extension(original);
        if (requested.equals(original)) { saveRenamedMetadata(videoId, original); return; }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues(); values.put(MediaStore.MediaColumns.DISPLAY_NAME, requested);
            boolean changed = false;
            try {
                if (resolver.update(uri, values, null, null) < 1) throw new IllegalStateException("手机中的文件记录无法更新，请重试");
                changed = true;
                String actual = displayName(uri, true);
                saveRenamedMetadata(videoId, actual);
            } catch (RuntimeException failure) {
                if (changed) {
                    ContentValues rollback = new ContentValues(); rollback.put(MediaStore.MediaColumns.DISPLAY_NAME, original);
                    try { resolver.update(uri, rollback, null, null); } catch (RuntimeException ignored) { /* Preserve the provider's readable URI even if rollback is refused. */ }
                }
                throw renameFailure(failure);
            }
        } else renameLegacy(videoId, uri, original, requested);
    }

    @SuppressWarnings("deprecation")
    private void renameLegacy(long videoId, Uri uri, String originalName, String requested) {
        ContentResolver resolver = app.getContentResolver(); String path;
        try (Cursor c = resolver.query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c == null || !c.moveToFirst() || c.isNull(0)) throw new IllegalArgumentException("系统未提供文件位置，无法重命名");
            path = c.getString(0);
        }
        File original = new File(path), target = new File(original.getParentFile(), requested);
        if (!original.isFile()) throw new IllegalArgumentException("原视频文件不存在");
        if (target.exists()) throw new IllegalArgumentException("已有同名文件，请换一个名称");
        if (!original.renameTo(target)) throw new IllegalArgumentException("无法重命名文件，请检查存储空间或权限");
        try {
            ContentValues values = new ContentValues(); values.put(MediaStore.MediaColumns.DATA, target.getAbsolutePath()); values.put(MediaStore.MediaColumns.DISPLAY_NAME, requested);
            if (resolver.update(uri, values, null, null) < 1) throw new IllegalStateException("系统媒体记录更新失败");
            saveRenamedMetadata(videoId, displayName(uri, true));
        } catch (RuntimeException failure) {
            if (target.renameTo(original)) {
                ContentValues rollback = new ContentValues(); rollback.put(MediaStore.MediaColumns.DATA, original.getAbsolutePath()); rollback.put(MediaStore.MediaColumns.DISPLAY_NAME, originalName);
                try { resolver.update(uri, rollback, null, null); } catch (RuntimeException ignored) {}
            }
            throw renameFailure(failure);
        }
    }

    private void saveRenamedMetadata(long videoId, String actualName) {
        ContentValues values = new ContentValues(); values.put("file_name", actualName);
        values.put("title", actualName.substring(0, actualName.length() - extension(actualName).length()));
        helper.getWritableDatabase().update("video", values, "id=?", strings(videoId));
    }
    private IllegalArgumentException renameFailure(RuntimeException cause) { return new IllegalArgumentException("文件重命名失败，请检查文件权限或换一个名称后重试", cause); }
    static String validFileBaseName(String name) {
        if (name != null) for (int i = 0; i < name.length(); i++) if (name.charAt(i) == '/' || name.charAt(i) == '\\' || Character.isISOControl(name.charAt(i))) throw new IllegalArgumentException("名称不能包含斜杠、换行或控制字符");
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.equals(".") || value.equals("..")) throw new IllegalArgumentException("请输入文件名称");
        if (value.getBytes(StandardCharsets.UTF_8).length > 180) throw new IllegalArgumentException("文件名称过长，请缩短后重试");
        return value;
    }
    static String extension(String name) {
        int dot = name.lastIndexOf('.'); return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : "";
    }
    private String displayName(Uri uri, boolean required) {
        try {
            if ("file".equals(uri.getScheme())) return new File(uri.getPath()).getName();
            try (Cursor c = app.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0) && !c.getString(0).trim().isEmpty()) return c.getString(0);
            }
        } catch (RuntimeException failure) { if (required) throw new IllegalArgumentException("无法读取原文件名称", failure); }
        if (required) throw new IllegalArgumentException("无法读取原文件名称");
        return "";
    }

    public long createProject(String title) {
        ContentValues v = new ContentValues(); v.put("title", text(title, "我的剪辑", 160)); v.put("created_at", System.currentTimeMillis());
        return helper.getWritableDatabase().insertOrThrow("edit_project", null, v);
    }
    public JSONObject project(long projectId) {
        try (Cursor c = helper.getReadableDatabase().query("edit_project", null, "id=?", strings(projectId), null, null, null)) { return c.moveToFirst() ? readProject(c) : null; }
    }
    public List<JSONObject> projects() {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query("edit_project", null, null, null, null, null, "created_at DESC,id DESC")) { while (c.moveToNext()) result.add(readProject(c)); }
        return result;
    }
    private JSONObject readProject(Cursor c) {
        return json("id", num(c, "id"), "title", str(c, "title"), "exportState", str(c, "export_state"), "exportProgress", num(c, "export_progress"), "exportDetail", str(c, "export_detail"), "outputUri", str(c, "output_uri"));
    }

    public List<JSONObject> clips(long projectId) {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query("edit_clip", null, "project_id=?", strings(projectId), null, null, "position,id")) {
            while (c.moveToNext()) result.add(json("id", num(c, "id"), "projectId", num(c, "project_id"), "videoId", num(c, "video_id"), "uri", str(c, "uri"), "title", str(c, "title"),
                    "startMs", num(c, "start_ms"), "endMs", num(c, "end_ms"), "durationMs", num(c, "end_ms") - num(c, "start_ms"), "position", num(c, "position")));
        } return result;
    }

    public synchronized long addClip(long projectId, long videoId, long startMs, long endMs) {
        JSONObject video = video(videoId); if (video == null) throw new IllegalArgumentException("视频已不在视频库中");
        long duration = video.optLong("durationMs");
        if (startMs < 0 || endMs <= startMs || (duration > 0 && endMs > duration)) throw new IllegalArgumentException("请设置有效的片段起止时间");
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try {
            int position = 0;
            try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM edit_clip WHERE project_id=?", strings(projectId))) { if (c.moveToFirst()) position = c.getInt(0); }
            ContentValues v = new ContentValues(); v.put("project_id", projectId); v.put("video_id", videoId); v.put("uri", video.optString("uri")); v.put("title", video.optString("title"));
            v.put("start_ms", startMs); v.put("end_ms", endMs); v.put("position", position);
            long id = db.insertOrThrow("edit_clip", null, v); db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    public synchronized void updateClipRange(long projectId,long clipId,String sourceUri,long startMs,long endMs,long measuredDurationMs){
        if(!localUri(sourceUri))throw new IllegalArgumentException("素材地址无效");
        ClipContract.validate(startMs,endMs,measuredDurationMs);
        ContentValues values=new ContentValues();values.put("start_ms",startMs);values.put("end_ms",endMs);
        // Match the stable draft object and its URI snapshot, not an optional library row.
        if(helper.getWritableDatabase().update("edit_clip",values,"id=? AND project_id=? AND uri=?",new String[]{Long.toString(clipId),Long.toString(projectId),sourceUri})!=1)throw new IllegalArgumentException("片段已不存在或素材已改变");
    }
    public synchronized void removeClip(long clipId) {
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try {
            long project = 0;
            try (Cursor c = db.query("edit_clip", new String[]{"project_id"}, "id=?", strings(clipId), null, null, null)) { if (c.moveToFirst()) project = c.getLong(0); }
            db.delete("edit_clip", "id=?", strings(clipId)); normalizePositions(db, project); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized void moveClip(long projectId, long clipId, int delta) {
        if (delta == 0) return;
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try {
            List<Long> ids = clipIds(db, projectId); int old = ids.indexOf(clipId);
            if (old >= 0) { long target = (long) old + delta; int next = (int) Math.max(0, Math.min(ids.size() - 1, target)); ids.remove(old); ids.add(next, clipId); writePositions(db, ids); }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private void normalizePositions(SQLiteDatabase db, long projectId) { writePositions(db, clipIds(db, projectId)); }
    private List<Long> clipIds(SQLiteDatabase db, long projectId) {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = db.query("edit_clip", new String[]{"id"}, "project_id=?", strings(projectId), null, null, "position,id")) { while (c.moveToNext()) ids.add(c.getLong(0)); }
        return ids;
    }
    private void writePositions(SQLiteDatabase db, List<Long> ids) {
        for (int i = 0; i < ids.size(); i++) { ContentValues v = new ContentValues(); v.put("position", i); db.update("edit_clip", v, "id=?", strings(ids.get(i))); }
    }

    public void updateProjectExport(long projectId, String state, int progress, String detail, String outputUri) {
        ContentValues v = new ContentValues(); v.put("export_state", text(state, "draft", 40)); v.put("export_progress", Math.max(-1, Math.min(100, progress)));
        v.put("export_detail", text(detail, "", 3000)); if (outputUri != null) v.put("output_uri", outputUri);
        helper.getWritableDatabase().update("edit_project", v, "id=?", strings(projectId));
    }

    /** Call once during process startup, before accepting new exports. */
    public void recoverInterruptedExports() {
        synchronized (RECOVERY_LOCK) {
            if (exportsRecovered) return;
            ContentValues v = new ContentValues(); v.put("export_state", "interrupted"); v.put("export_detail", "上次导出已中断，草稿已保留，可重新导出。");
            helper.getWritableDatabase().update("edit_project", v, "export_state IN (?,?,?,?,?)", new String[]{"queued", "preparing", "exporting", "saving", "cancelling"});
            exportsRecovered = true;
        }
    }

    public synchronized long addExportedVideo(String title, String uri, String mime, long bytes, long durationMs) {
        if (!localUri(uri)) throw new IllegalArgumentException("导出文件地址无效");
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues v = videoValues(title, uri, mime, "export", Math.max(0, bytes), Math.max(0, durationMs));
        db.insertWithOnConflict("video", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return findId(db, "video", "uri=?", new String[]{uri});
    }

    private ContentValues videoValues(String title, String uri, String mime, String source, long bytes, long durationMs) {
        ContentValues v = new ContentValues(); v.put("title", text(title, "视频", 500)); v.put("uri", uri); v.put("mime", text(mime, "video/mp4", 100));
        v.put("source", source); v.put("file_name", displayName(Uri.parse(uri), false)); v.put("bytes", bytes); v.put("duration_ms", durationMs); v.put("created_at", System.currentTimeMillis()); return v;
    }
    private long duration(long videoId) {
        try (Cursor c = helper.getReadableDatabase().query("video", new String[]{"duration_ms"}, "id=?", strings(videoId), null, null, null)) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("视频已不在视频库中"); return c.getLong(0);
        }
    }
    private static boolean localUri(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String scheme = Uri.parse(value).getScheme(); return "content".equals(scheme) || "file".equals(scheme);
    }
    private static String source(String url, String platform) {
        String value = (url + " " + platform).toLowerCase(Locale.ROOT);
        if (value.contains("bilibili") || value.contains("b23.tv") || value.contains("哔哩")) return "bilibili";
        if (value.contains("douyin") || value.contains("抖音")) return "douyin";
        if (value.contains("x.com/") || value.contains("twitter") || value.contains("推特")) return "x";
        return "other";
    }
    private static long findId(SQLiteDatabase db, String table, String where, String[] args) {
        try (Cursor c = db.query(table, new String[]{"id"}, where, args, null, null, null)) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }
    private static String text(String value, String fallback, int max) {
        String clean = value == null ? "" : value.trim(); if (clean.isEmpty()) clean = fallback; return clean.length() > max ? clean.substring(0, max) : clean;
    }
    private static String[] strings(long id) { return new String[]{String.valueOf(id)}; }
    private static long num(Cursor c, String key) { return c.getLong(c.getColumnIndexOrThrow(key)); }
    private static String str(Cursor c, String key) { return c.getString(c.getColumnIndexOrThrow(key)); }
    private static JSONObject json(Object... pairs) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) result.put(String.valueOf(pairs[i]), pairs[i + 1]); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); } return result;
    }
    @Override public void close() { helper.close(); }
}
