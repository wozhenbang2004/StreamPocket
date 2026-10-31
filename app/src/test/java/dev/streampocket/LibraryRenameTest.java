package dev.streampocket;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

/** Real fixture files with a synthetic provider; this is not a device MediaStore test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class LibraryRenameTest {
    private static final String AUTHORITY = "dev.streampocket.rename.fixture";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY + "/video/1");
    private Context app;
    private File directory;
    private FixtureProvider provider;
    private LibraryStore store;
    private long video;

    @Before public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication(); app.deleteDatabase("media-library.db");
        directory = new File(app.getCacheDir(), "rename-fixture"); directory.mkdirs();
        File original = new File(directory, "original.MP4");
        try (FileOutputStream out = new FileOutputStream(original)) { out.write(new byte[]{4, 3, 2, 1}); }
        provider = new FixtureProvider(); provider.file = original;
        ProviderInfo info = new ProviderInfo(); info.authority = AUTHORITY; provider.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
        store = new LibraryStore(app);
        video = store.upsertCompletedDownload(new JSONObject().put("id", "rename-fixture").put("title", "原始标题").put("uri", URI.toString()).put("status", "completed").put("media", new JSONObject().put("duration", 100)));
    }
    @After public void teardown() {
        store.close(); app.deleteDatabase("media-library.db");
        File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete(); directory.delete();
    }

    @Test public void scopedStorageRenameKeepsUriAndMarkerAndQueriesActualCollisionName() throws Exception {
        long marker = store.addRangeMarker(video, 10_000, 20_000, "精彩");
        File collision = new File(directory, "新名字.MP4"); assertTrue(collision.createNewFile());
        store.renameVideo(video, "新名字");
        assertFalse(new File(directory, "original.MP4").exists()); assertTrue(new File(directory, "新名字 (1).MP4").exists());
        assertEquals(4, provider.file.length()); assertTrue(collision.exists());
        JSONObject renamed = store.video(video); assertEquals(URI.toString(), renamed.optString("uri")); assertEquals("新名字 (1).MP4", renamed.optString("fileName")); assertEquals("新名字 (1)", renamed.optString("title"));
        assertEquals(marker, store.markers(video).get(0).optLong("id")); assertEquals(20_000, store.markers(video).get(0).optLong("endMs"));
    }

    @Test @Config(sdk = 28) public void legacyRenameMovesPhysicalFileAndUpdatesProviderPath() {
        store.renameVideo(video, "legacy-new");
        assertTrue(new File(directory, "legacy-new.MP4").isFile()); assertFalse(new File(directory, "original.MP4").exists());
        assertEquals("legacy-new.MP4", store.video(video).optString("fileName")); assertEquals(URI.toString(), store.video(video).optString("uri"));
    }

    @Test @Config(sdk = 28) public void legacyProviderFailureRollsPhysicalRenameBackAndKeepsTitle() {
        provider.failUpdate = true;
        expectInvalid(() -> store.renameVideo(video, "will-fail"));
        assertTrue(new File(directory, "original.MP4").isFile()); assertFalse(new File(directory, "will-fail.MP4").exists()); assertEquals("原始标题", store.video(video).optString("title"));
    }

    @Test public void invalidNamesAndUnavailableFilesCannotBecomeCatalogueOnlyRenames() {
        for (String invalid : new String[]{"", "  ", ".", "..", "a/b", "a\\b", "a\nb", "\tname", "x\u0000y", "中".repeat(61)}) expectInvalid(() -> store.renameVideo(video, invalid));
        assertEquals("原始标题", store.video(video).optString("title")); assertTrue(provider.file.isFile());
        provider.file.delete(); expectInvalid(() -> store.renameVideo(video, "missing")); assertEquals("原始标题", store.video(video).optString("title"));
    }

    private static void expectInvalid(Runnable action) { try { action.run(); fail("Rename should fail without changing metadata"); } catch (IllegalArgumentException expected) {} }

    @SuppressWarnings("deprecation")
    public static final class FixtureProvider extends ContentProvider {
        File file; boolean failUpdate;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA} : projection;
            MatrixCursor cursor = new MatrixCursor(columns); Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) row[i] = columns[i].equals(MediaStore.MediaColumns.DATA) ? file.getAbsolutePath() : file.getName();
            cursor.addRow(row); return cursor;
        }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
            if (failUpdate) throw new SecurityException("fixture refusal");
            if (values.containsKey(MediaStore.MediaColumns.DATA)) { file = new File(values.getAsString(MediaStore.MediaColumns.DATA)); return 1; }
            String name = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME); File target = new File(file.getParentFile(), name);
            if (target.exists() && !target.equals(file)) {
                String extension = LibraryStore.extension(name); String base = name.substring(0, name.length() - extension.length()); int n = 1;
                do { target = new File(file.getParentFile(), base + " (" + n++ + ")" + extension); } while (target.exists());
            }
            if (!file.equals(target) && !file.renameTo(target)) throw new IllegalStateException("fixture rename failed"); file = target; return 1;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException { return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); }
        @Override public String getType(Uri uri) { return "video/mp4"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
