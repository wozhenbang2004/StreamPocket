package dev.streampocket;

import org.junit.Test;

public final class DownloadFlowTest {
    @Test public void screenshotIsANetworkTimeout() { DownloadFlowSelfTest.screenshotTimeout(); }
    @Test public void orderedBatchRetainsDistinctVideosAndParts() { DownloadFlowSelfTest.links(); }
    @Test public void errorClassificationUsesSourceEvidence() { DownloadFlowSelfTest.failures(); }
    @Test public void sourceLimitsAreSharedCancellableAndIsolated() throws Exception { DownloadFlowSelfTest.scheduler(); }
}
