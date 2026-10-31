package dev.streampocket;

import java.util.concurrent.atomic.AtomicInteger;

/** Process-local hints for yielding optional cover work to playback and exports. */
public final class MediaWorkload {
    private static final AtomicInteger players = new AtomicInteger();
    private static volatile boolean exporting;
    private MediaWorkload() {}

    public static void playerStarted() { players.incrementAndGet(); }
    public static void playerStopped() { players.updateAndGet(count -> Math.max(0, count - 1)); }
    public static boolean isPlayerActive() { return players.get() > 0; }
    public static void setExporting(boolean active) { exporting = active; }
    public static boolean shouldPauseCovers() { return isPlayerActive() || exporting; }
}
