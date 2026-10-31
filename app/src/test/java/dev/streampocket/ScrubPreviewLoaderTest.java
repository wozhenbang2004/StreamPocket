package dev.streampocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Looper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Scheduler and Bitmap fixtures. No file is presented as a decoded video. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ScrubPreviewLoaderTest {
    private static final Uri A = Uri.parse("content://preview.fixture/video/a");
    private static final Uri B = Uri.parse("content://preview.fixture/video/b");
    private Context app;
    private final List<ScrubPreviewLoader> loaders = new ArrayList<>();
    private final List<Frames> factories = new ArrayList<>();

    @Before public void setup() { app = RuntimeEnvironment.getApplication(); }

    @After public void closeWorkers() throws Exception {
        // Also unblock the fixture after a failed assertion; a native call is
        // never simulated by interrupting/closing a decoder from the main thread.
        for (Frames frames : factories) frames.finish.countDown();
        for (ScrubPreviewLoader loader : loaders) loader.release();
        for (ScrubPreviewLoader loader : loaders)
            assertTrue("Preview worker must terminate after release", worker(loader).awaitTermination(5, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        for (Frames frames : factories) {
            assertFalse("Probe/decode/close must stay off main", frames.onMain.get());
            assertFalse("Close must not race an in-flight frame", frames.closedDuringFrame.get());
        }
    }

    @Test public void hotCachePublishesInTheMoveCallWithoutATimerOrWorkerRoundTrip() throws Exception {
        Events events = new Events(); Frames frames = frames(); ScrubPreviewLoader loader = loader(events, frames);
        loader.setSource(A, 100_000); loader.request(5_000); advanceAndDrain(loader, 90);
        Bitmap cached = events.images.get(0).image;
        loader.request(5_299);
        assertEquals("A hot preview must not wait another 90 ms", 2, events.images.size());
        assertSame(cached, events.images.get(1).image); assertEquals(1, frames.calls.get());
    }

    @Test public void preparesBeforeDraggingAndEveryPreparedTileIsSynchronousWithoutMoreDecodes() throws Exception {
        Events events = new Events(); Frames frames = frames(); ScrubPreviewLoader loader = loader(events, frames);
        loader.setSource(A, 20_000); loader.prepare(8_000);
        advanceAndDrain(loader,350);
        for(int i=0;i<45;i++) advanceAndDrain(loader,60);
        assertEquals("20 s at 500 ms spacing is prepared once",40,frames.calls.get());
        assertTrue("Preparation cannot open the preview UI by itself",events.images.isEmpty());
        long clock = android.os.SystemClock.uptimeMillis();
        for(int i=0;i<40;i++) {
            loader.request(i*500L);
            assertEquals(i+1,events.images.size()); assertEquals(i*500L,events.images.get(i).sampleAt);
            assertTrue(events.images.get(i).image.getWidth()<=128); assertTrue(events.images.get(i).image.getHeight()<=72);
        }
        assertEquals("All 40 requests complete before advancing the event loop",clock,android.os.SystemClock.uptimeMillis());
        assertEquals(40,frames.calls.get());
    }

    @Test public void twoHourVideoPreparationHasFixedFrameAndMemoryLimits() throws Exception {
        Events events = new Events(); Frames frames = frames(); ScrubPreviewLoader loader = loader(events,frames);
        loader.setSource(A,7_200_000); loader.prepare(3_600_000); advanceAndDrain(loader,350);
        for(int i=0;i<105;i++) advanceAndDrain(loader,60);
        assertEquals(96,frames.calls.get()); assertTrue(events.images.isEmpty());
        Field field=ScrubPreviewLoader.class.getDeclaredField("storyboard");field.setAccessible(true);
        java.util.Map<Long,Bitmap> tiles=(java.util.Map<Long,Bitmap>)field.get(loader);
        long bytes=0;for(Bitmap image:tiles.values())bytes+=image.getAllocationByteCount();
        assertTrue("Storyboard plus 2 MiB detail LRU fits the previous 4 MiB cap",bytes+2*1024*1024<=4*1024*1024);
        assertEquals(75_000,ScrubStoryboardCache.step(7_200_000));
    }

    @Test public void anInteractiveTargetPreemptsRemainingPreparationAfterOneInFlightFrame() throws Exception {
        Events events = new Events(); Frames frames = frames(); frames.blockFirst=true;
        ScrubPreviewLoader loader=loader(events,frames);loader.setSource(A,100_000);loader.prepare(0);
        advance(350);assertTrue(frames.entered.await(5,TimeUnit.SECONDS));
        for(int i=0;i<20;i++){loader.request(5_000+i*500L);advance(90);}
        frames.finish.countDown();drain(loader);
        assertEquals("Only the current warm tile and newest interactive target run",2,frames.calls.get());
        assertEquals(Long.valueOf(14_500),frames.sampleTimes.get(1));
        assertEquals(14_500,events.images.get(events.images.size()-1).sampleAt);
    }

    @Test public void pauseAndSourceChangeCancelPreparationWithoutPublishingOrConcurrentClose() throws Exception {
        Events events=new Events();Frames frames=frames();frames.blockFirst=true;
        ScrubPreviewLoader loader=loader(events,frames);loader.setSource(A,20_000);loader.prepare(0);
        advance(350);assertTrue(frames.entered.await(5,TimeUnit.SECONDS));
        loader.pausePreparation();loader.setSource(B,20_000);frames.finish.countDown();drain(loader);advanceAndDrain(loader,1000);
        assertEquals(1,frames.calls.get());assertTrue(events.images.isEmpty());
        loader.request(0);advanceAndDrain(loader,90);
        assertEquals(Color.BLUE,events.images.get(0).image.getPixel(0,0));
    }

    @Test public void preparedDiskCacheReopensWithoutDecodingAndModifiedSourceCannotReuseIt() throws Exception {
        java.io.File file=java.io.File.createTempFile("preview-source-",".mp4",app.getCacheDir());
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(new byte[]{1,2,3});}
        Uri uri=Uri.fromFile(file);
        Events firstEvents=new Events();Frames first=frames();ScrubPreviewLoader a=loader(firstEvents,first);
        a.setSource(uri,2000);a.prepare(0);advanceAndDrain(a,350);for(int i=0;i<6;i++)advanceAndDrain(a,60);
        assertEquals(4,first.calls.get());a.release();assertTrue(worker(a).awaitTermination(5,TimeUnit.SECONDS));
        Events cachedEvents=new Events();Frames cached=frames();ScrubPreviewLoader b=loader(cachedEvents,cached);
        b.setSource(uri,2000);b.prepare(0);advanceAndDrain(b,350);for(int i=0;i<6;i++)advanceAndDrain(b,60);
        b.request(1000);assertEquals(1,cachedEvents.images.size());assertEquals(0,cached.calls.get());
        b.release();assertTrue(worker(b).awaitTermination(5,TimeUnit.SECONDS));
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file,true)){out.write(4);}
        Events changedEvents=new Events();Frames changed=frames();ScrubPreviewLoader c=loader(changedEvents,changed);
        c.setSource(uri,2000);c.prepare(0);advanceAndDrain(c,350);
        assertEquals("Changed file identity must regenerate, not display old persistent tiles",1,changed.calls.get());
        c.pausePreparation();file.delete();
    }

    @Test public void slowCompletionCannotReplaceANewerImmediateHotCacheHit() throws Exception {
        Events events=new Events();Frames frames=frames();frames.blockFirst=true;frames.blockAt=2;
        ScrubPreviewLoader loader=loader(events,frames);loader.setSource(A,100_000);
        loader.request(1000);advanceAndDrain(loader,90);Bitmap hot=events.images.get(0).image;
        loader.request(5000);advance(90);assertTrue(frames.entered.await(5,TimeUnit.SECONDS));
        loader.request(1000);assertSame(hot,events.images.get(events.images.size()-1).image);
        frames.finish.countDown();drain(loader);
        assertSame("Old decode cannot push the image backwards after a hot hit",hot,events.images.get(events.images.size()-1).image);
        assertEquals(1000,events.images.get(events.images.size()-1).sampleAt);
    }

    @Test public void pausedPreparationCanResumeTheAbandonedTile() throws Exception {
        Events events=new Events();Frames frames=frames();frames.blockFirst=true;
        ScrubPreviewLoader loader=loader(events,frames);loader.setSource(A,500);loader.prepare(0);
        advance(350);assertTrue(frames.entered.await(5,TimeUnit.SECONDS));loader.pausePreparation();
        frames.finish.countDown();drain(loader);loader.prepare(0);advanceAndDrain(loader,350);
        loader.request(0);assertEquals(2,frames.calls.get());assertEquals(1,events.images.size());
    }

    @Test public void burstWithinThrottleWindowDecodesOnlyTheNewestTime() throws Exception {
        Events events = new Events(); Frames frames = frames(); ScrubPreviewLoader loader = loader(events, frames);
        loader.setSource(A, 100_000);
        for (int i = 1; i <= 200; i++) loader.request(i * 100L);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(89, TimeUnit.MILLISECONDS); drainWorker(loader);
        assertEquals(0, frames.calls.get());
        advanceAndDrain(loader, 1);
        assertEquals(1, frames.calls.get()); assertEquals(1, events.images.size());
        assertEquals(20_000, events.images.get(0).at); assertEquals(Long.valueOf(20_000), frames.sampleTimes.get(0));
    }

    @Test public void continuousMovesBelowNinetyMillisecondsStillPublishWhileTheFingerMoves() throws Exception {
        Events events = new Events(); Frames frames = frames(); ScrubPreviewLoader loader = loader(events, frames);
        loader.setSource(A, 100_000);
        for (int i = 0; i < 20; i++) {
            loader.request(1_000 + i * 500L);
            advanceAndDrain(loader, 30);
        }
        assertTrue("A 600 ms continuous drag must publish before the finger pauses", events.images.size() >= 4);
        assertTrue("Throttle bounds extraction work instead of decoding every MOVE", frames.calls.get() <= 7);
    }

    @Test public void manyRequestsDuringOneSlowFrameKeepOnlyOneLatestPendingDecode() throws Exception {
        Events events = new Events(); Frames frames = frames(); frames.blockFirst = true;
        ScrubPreviewLoader loader = loader(events, frames); loader.setSource(A, 100_000);
        loader.request(1_000); advance(90); assertTrue(frames.entered.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 30; i++) { loader.request(2_000 + i * 500L); advance(90); }
        assertEquals("The first native call is still the only active call", 1, frames.calls.get());
        frames.finish.countDown(); drain(loader);
        assertEquals("Intermediate drags must not form a decode backlog", 2, frames.calls.get());
        assertEquals(1, frames.opens.size()); assertEquals(2, events.images.size());
        assertEquals("A completed active-scrub frame must not starve while targets move",1_000, events.images.get(0).at);
        assertEquals(16_500, events.images.get(1).at);
    }

    @Test public void slowFrameCanPublishBetweenThrottleWindowsWithoutRelabellingItsSample() throws Exception {
        Events events = new Events(); Frames frames = frames(); frames.blockFirst = true;
        ScrubPreviewLoader loader = loader(events, frames); loader.setSource(A, 100_000);
        loader.request(1_234); advance(90); assertTrue(frames.entered.await(5, TimeUnit.SECONDS));
        loader.request(5_000); advance(30); loader.request(5_500); advance(30);
        frames.finish.countDown(); drain(loader);
        assertEquals("A newer moving target does not invalidate a useful active-scrub frame",1,events.images.size());
        assertEquals(1_234,events.images.get(0).at); assertEquals(1_000,events.images.get(0).sampleAt);
        loader.request(6_000); advanceAndDrain(loader,30);
        assertEquals(2,events.images.size()); assertEquals(6_000,events.images.get(1).at);
        loader.request(1_299); advanceAndDrain(loader,90);
        assertEquals("The completed earlier bucket is cached even when the target moved",2,frames.calls.get());
        assertEquals(1_000,events.images.get(2).sampleAt);
    }

    @Test public void cancelAndRestartWhileOldDecodeRunsCannotReplayThePreviousScrub() throws Exception {
        Events events = new Events(); Frames frames = frames(); frames.blockFirst = true;
        ScrubPreviewLoader loader = loader(events, frames); loader.setSource(A,100_000);
        loader.request(1_000); advance(90); assertTrue(frames.entered.await(5,TimeUnit.SECONDS));
        loader.clear(); loader.request(3_000); advance(90);
        frames.finish.countDown(); drain(loader);
        assertEquals(1,events.images.size()); assertEquals(3_000,events.images.get(0).at);
    }

    @Test public void sourceSwitchAlsoInvalidatesAResultAlreadyQueuedForMain() throws Exception {
        Events events = new Events(); Frames frames = frames(); frames.blockFirst=true; ScrubPreviewLoader loader = loader(events,frames);
        loader.setSource(A,100_000);loader.request(1_000);advance(90);assertTrue(frames.entered.await(5,TimeUnit.SECONDS));
        frames.finish.countDown();drainWorker(loader);
        loader.setSource(B,100_000);advance(0);
        assertTrue("Queued old-source callbacks cannot cross setSource",events.images.isEmpty());
        loader.request(5_000);advanceAndDrain(loader,90);
        assertEquals(1,events.images.size());assertEquals(Color.BLUE,events.images.get(0).image.getPixel(0,0));
    }

    @Test public void switchingSourcesDropsTheOldImageAndClosesItsDecoderOnWorker() throws Exception {
        Events events = new Events(); Frames frames = frames(); frames.blockFirst = true;
        ScrubPreviewLoader loader = loader(events, frames); loader.setSource(A, 100_000);
        loader.request(1_000); advance(90); assertTrue(frames.entered.await(5, TimeUnit.SECONDS));
        loader.setSource(B, 50_000); loader.request(5_000); advance(90);
        assertTrue("Main must not close a decoder while its frame call runs", frames.closes.isEmpty());
        frames.finish.countDown(); drain(loader);
        assertEquals(2, frames.opens.size()); assertEquals(A.toString(), frames.closes.get(0));
        assertEquals(1, events.images.size()); assertEquals(5_000, events.images.get(0).at);
        assertEquals("A late first-video frame must never appear over the second", Color.BLUE, events.images.get(0).image.getPixel(0, 0));
    }

    @Test public void stoppingTheScrubHidesLateResultsAndReleasesItsRetriever() throws Exception {
        Events events = new Events(); Frames frames = frames(); frames.blockFirst = true;
        ScrubPreviewLoader loader = loader(events, frames); loader.setSource(A, 100_000);
        loader.request(1_000); advance(90); assertTrue(frames.entered.await(5, TimeUnit.SECONDS));
        loader.clear(); assertTrue(frames.closes.isEmpty());
        frames.finish.countDown(); drain(loader);
        assertTrue(events.images.isEmpty()); assertTrue(events.unavailable.isEmpty()); assertEquals(1, frames.closes.size());
        loader.request(3_000); advanceAndDrain(loader, 90);
        assertEquals(2, frames.opens.size()); assertEquals(3_000, events.images.get(0).at);
    }

    @Test public void releaseDuringDecodeNeverPublishesOrClosesAcrossThreads() throws Exception {
        Events events = new Events(); Frames frames = frames(); frames.blockFirst = true;
        ScrubPreviewLoader loader = loader(events, frames); loader.setSource(A, 100_000);
        loader.request(1_000); advance(90); assertTrue(frames.entered.await(5, TimeUnit.SECONDS));
        loader.release(); loader.request(5_000);
        assertTrue("Release returns without waiting on the blocked frame", frames.closes.isEmpty());
        frames.finish.countDown(); assertTrue(worker(loader).awaitTermination(5, TimeUnit.SECONDS)); advance(100);
        assertTrue(events.images.isEmpty()); assertTrue(events.unavailable.isEmpty()); assertEquals(1, frames.calls.get());
        assertEquals(1, frames.closes.size());
    }

    @Test public void cachedFramesKeepOriginalRequestedTimeAndRemainValidAfterStopAndRelease() throws Exception {
        Events events = new Events(); Frames frames = frames(); frames.width = 720; frames.height = 1280;
        ScrubPreviewLoader loader = loader(events, frames); loader.setSource(A, 100_000);
        loader.request(5_001); advanceAndDrain(loader, 90);
        Bitmap shown = events.images.get(0).image;
        assertEquals(5_001, events.images.get(0).at); assertEquals(Long.valueOf(5_000), frames.sampleTimes.get(0));
        assertEquals(81, shown.getWidth()); assertEquals(144, shown.getHeight());
        assertTrue("An oversized temporary frame must be promptly recycled", frames.lastFull.isRecycled());
        loader.clear(); drain(loader);
        loader.request(5_299); advanceAndDrain(loader, 90);
        assertEquals(1, frames.calls.get()); assertEquals(1, frames.opens.size());
        assertEquals(5_299, events.images.get(1).at); assertEquals(5_000, events.images.get(1).sampleAt); assertSame(shown, events.images.get(1).image);
        loader.release(); assertTrue(worker(loader).awaitTermination(5, TimeUnit.SECONDS));
        assertFalse("Cache cleanup cannot recycle a Bitmap still held by ImageView", shown.isRecycled());
    }

    @Test public void failedPreviewFallsBackOnMainAndNextRequestCanRecover() throws Exception {
        Events events = new Events(); Frames frames = frames(); frames.failFirst = true;
        ScrubPreviewLoader loader = loader(events, frames); loader.setSource(A, 1_000);
        loader.request(9_999); advanceAndDrain(loader, 90);
        assertEquals(Collections.singletonList(1_000L), events.unavailable); assertEquals(Long.valueOf(999), frames.sampleTimes.get(0));
        loader.request(100); advanceAndDrain(loader, 90);
        assertEquals(1, events.images.size()); assertEquals(100, events.images.get(0).at);
        assertEquals(2, frames.opens.size());
    }

    @Test public void networkUrisNeverReachTheDecoder() {
        Events events = new Events(); Frames frames = frames(); ScrubPreviewLoader loader = loader(events, frames);
        try { loader.setSource(Uri.parse("https://example.com/video.mp4"), 1_000); fail("Remote preview should be rejected"); }
        catch (IllegalArgumentException expected) {}
        loader.request(100);
        assertEquals(Collections.singletonList(100L), events.unavailable); assertTrue(frames.opens.isEmpty());
    }

    private Frames frames() { Frames frames = new Frames(); factories.add(frames); return frames; }
    private ScrubPreviewLoader loader(Events events, Frames frames) {
        ScrubPreviewLoader loader = new ScrubPreviewLoader(app, events, frames); loaders.add(loader); return loader;
    }
    private static void advance(long millis) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS); }
    private static void advanceAndDrain(ScrubPreviewLoader loader, long millis) throws Exception { advance(millis); drain(loader); }
    private static void drain(ScrubPreviewLoader loader) throws Exception { drainWorker(loader); advance(0); }
    private static void drainWorker(ScrubPreviewLoader loader) throws Exception { worker(loader).submit(() -> {}).get(5, TimeUnit.SECONDS); }
    private static ExecutorService worker(ScrubPreviewLoader loader) throws Exception {
        Field field = ScrubPreviewLoader.class.getDeclaredField("worker"); field.setAccessible(true); return (ExecutorService) field.get(loader);
    }

    private static final class Events implements ScrubPreviewLoader.Listener {
        final List<Image> images = new ArrayList<>(); final List<Long> unavailable = new ArrayList<>();
        @Override public void onPreview(long positionMs, long sampleMs, Bitmap bitmap) { assertSame(Looper.getMainLooper(), Looper.myLooper()); images.add(new Image(positionMs, sampleMs, bitmap)); }
        @Override public void onUnavailable(long positionMs) { assertSame(Looper.getMainLooper(), Looper.myLooper()); unavailable.add(positionMs); }
    }
    private static final class Image {
        final long at, sampleAt; final Bitmap image;
        Image(long at, long sampleAt, Bitmap image) { this.at = at; this.sampleAt = sampleAt; this.image = image; }
    }
    private static final class Frames implements ScrubPreviewLoader.DecoderFactory {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean onMain = new AtomicBoolean(), closedDuringFrame = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        final List<String> opens = Collections.synchronizedList(new ArrayList<>()), closes = Collections.synchronizedList(new ArrayList<>());
        final List<Long> sampleTimes = Collections.synchronizedList(new ArrayList<>());
        boolean blockFirst, failFirst; int blockAt = 1, width = 160, height = 90;
        volatile Bitmap lastFull;
        @Override public ScrubPreviewLoader.FrameDecoder open(Context app, Uri uri) {
            checkThread(); opens.add(uri.toString());
            return new ScrubPreviewLoader.FrameDecoder() {
                private boolean decoding;
                @Override public Bitmap frameAt(long positionMs) throws Exception {
                    checkThread(); decoding = true;
                    try {
                        int call = calls.incrementAndGet(); sampleTimes.add(positionMs);
                        if (call == blockAt && blockFirst) { entered.countDown(); if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture frame timed out"); }
                        if (call == 1 && failFirst) throw new IOException("Fixture unavailable frame");
                        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        frame.eraseColor(uri.equals(B) ? Color.BLUE : Color.RED); lastFull = frame; return frame;
                    } finally { decoding = false; }
                }
                @Override public void close() { checkThread(); if (decoding) closedDuringFrame.set(true); closes.add(uri.toString()); }
            };
        }
        private void checkThread() { if (Looper.myLooper() == Looper.getMainLooper()) onMain.set(true); }
    }
}
