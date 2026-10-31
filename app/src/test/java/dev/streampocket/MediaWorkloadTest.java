package dev.streampocket;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MediaWorkloadTest {
    @Before @After public void reset() {
        while (MediaWorkload.isPlayerActive()) MediaWorkload.playerStopped();
        MediaWorkload.setExporting(false);
    }

    @Test public void overlappingPlayersKeepCoversPausedUntilTheLastPlayerStops() {
        assertFalse(MediaWorkload.shouldPauseCovers());
        MediaWorkload.playerStarted(); MediaWorkload.playerStarted();
        MediaWorkload.playerStopped(); assertTrue(MediaWorkload.isPlayerActive()); assertTrue(MediaWorkload.shouldPauseCovers());
        MediaWorkload.playerStopped(); assertFalse(MediaWorkload.isPlayerActive()); assertFalse(MediaWorkload.shouldPauseCovers());
        MediaWorkload.playerStopped(); MediaWorkload.playerStarted(); assertTrue("An extra stop must never make the count negative", MediaWorkload.isPlayerActive());
    }

    @Test public void exportAndPlaybackIndependentlyHoldTheCoverGate() {
        MediaWorkload.setExporting(true); assertTrue(MediaWorkload.shouldPauseCovers());
        MediaWorkload.playerStarted(); MediaWorkload.setExporting(false); assertTrue(MediaWorkload.shouldPauseCovers());
        MediaWorkload.setExporting(true); MediaWorkload.playerStopped(); assertTrue(MediaWorkload.shouldPauseCovers());
        MediaWorkload.setExporting(false); assertFalse(MediaWorkload.shouldPauseCovers());
    }
}
