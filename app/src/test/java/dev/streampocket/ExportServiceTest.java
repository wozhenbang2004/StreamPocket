package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import java.util.Arrays;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Lifecycle/DB race fixtures, independent of device codecs or real media files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ExportServiceTest {
    private Context app;
    private LibraryStore store;
    private ServiceController<ExportService> controller;
    private ExportService service;
    private ExecutorService io;
    private boolean destroyed;

    @Before public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication(); app.deleteDatabase("media-library.db");
        store = new LibraryStore(app);
        controller = Robolectric.buildService(ExportService.class).create(); service = controller.get();
        io = (ExecutorService) field("io").get(service);
        running().clear(); MediaWorkload.setExporting(false);
    }
    @After public void cleanup() throws Exception {
        if (!destroyed) controller.destroy();
        assertTrue("Export worker must drain", io.awaitTermination(5, TimeUnit.SECONDS));
        running().clear(); MediaWorkload.setExporting(false); store.close(); app.deleteDatabase("media-library.db");
    }

    @Test public void commitAfterDestroyPreservesCompletionAgainstQueuedInterruption() throws Exception {
        long id = activeProject();
        CountDownLatch copying = new CountDownLatch(1), finishCommit = new CountDownLatch(1);
        Set<Long> committed = committed();
        io.execute(() -> {
            copying.countDown();
            try {
                if (!finishCommit.await(5, TimeUnit.SECONDS)) throw new AssertionError("commit gate timed out");
                // Same order as publish(): public save returns, then commit
                // marker and completed DB state are recorded on the IO worker.
                committed.add(id);
                try (LibraryStore workerStore = new LibraryStore(app)) {
                    workerStore.updateProjectExport(id, "completed", 100, "成片已保存", "content://fixture/committed");
                }
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        });
        assertTrue(copying.await(5, TimeUnit.SECONDS));
        controller.destroy(); destroyed = true;
        assertTrue("Retry remains blocked until the old worker drains", ExportService.isRunning(id));
        finishCommit.countDown(); assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
        JSONObject result = store.project(id);
        assertEquals("completed", result.optString("exportState"));
        assertEquals(100, result.optInt("exportProgress"));
        assertEquals("content://fixture/committed", result.optString("outputUri"));
        assertFalse(ExportService.isRunning(id));
    }

    @Test public void destructionWithoutPublicCommitLeavesRetryableDraft() throws Exception {
        long id = activeProject();
        controller.destroy(); destroyed = true;
        assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
        JSONObject result = store.project(id);
        assertEquals("interrupted", result.optString("exportState"));
        assertTrue(result.optInt("exportProgress") < 100);
        assertEquals("", result.optString("outputUri"));
        assertFalse(ExportService.isRunning(id));
    }

    @Test public void newExportWaitsWithoutClaimingCodecWhilePlaybackIsActive() throws Exception {
        long id = store.createProject("等看完再导出");
        MediaWorkload.playerStarted();
        try {
            service.onStartCommand(new Intent(app, ExportService.class).setAction(ExportService.ENQUEUE)
                    .putExtra(ExportService.PROJECT_ID, id), 0, 1);
            io.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(-1, field("activeId").getLong(service));
            assertNull(field("transformer").get(service));
            assertEquals("等待播放结束后导出", store.project(id).optString("exportDetail"));
            assertEquals(-1, store.project(id).optInt("exportProgress"));
        } finally { MediaWorkload.playerStopped(); }
        assertFalse("Waiting must not claim export work", MediaWorkload.shouldPauseCovers());
        service.onStartCommand(new Intent(app, ExportService.class).setAction(ExportService.CANCEL)
                .putExtra(ExportService.PROJECT_ID, id), 0, 2);
        io.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertEquals("cancelled", store.project(id).optString("exportState"));
    }

    @Test public void compatibilityPresetCapsLongEdgeAndKeepsEvenDimensions() {
        assertArrayEquals(new int[]{1920,1080}, ExportService.compatibilitySize(3840,2160));
        assertArrayEquals(new int[]{1080,1920}, ExportService.compatibilitySize(2160,3840));
        assertArrayEquals(new int[]{1280,720}, ExportService.compatibilitySize(1280,720));
        int[] odd = ExportService.compatibilitySize(1441,1081);
        assertEquals(0, odd[0] % 2); assertEquals(0, odd[1] % 2);
        assertTrue(odd[0] <= 1920 && odd[1] <= 1920);
    }

    @Test public void compositionRequestsAudioForSilenceAndDisablesMixedFormatPassthrough() {
        EditedMediaItem silent = new EditedMediaItem.Builder(MediaItem.fromUri("content://fixture/silent")).build();
        EditedMediaItem voiced = new EditedMediaItem.Builder(MediaItem.fromUri("content://fixture/voiced")).build();
        Composition composition = ExportService.composition(Arrays.asList(silent, voiced));
        assertTrue(composition.sequences.get(0).trackTypes.contains(C.TRACK_TYPE_AUDIO));
        assertTrue(composition.sequences.get(0).trackTypes.contains(C.TRACK_TYPE_VIDEO));
        assertFalse(composition.transmuxAudio); assertFalse(composition.transmuxVideo);
    }

    private long activeProject() throws Exception {
        long id = store.createProject("本地导出状态夹具");
        store.updateProjectExport(id, "saving", 99, "正在保存", "");
        field("activeId").setLong(service, id); field("saving").setBoolean(service, true); running().add(id);
        return id;
    }
    private static Field field(String name) throws Exception {
        Field field = ExportService.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    @SuppressWarnings("unchecked") private Set<Long> committed() throws Exception { return (Set<Long>) field("committed").get(service); }
    @SuppressWarnings("unchecked") private static Set<Long> running() throws Exception { return (Set<Long>) field("RUNNING").get(null); }
}
