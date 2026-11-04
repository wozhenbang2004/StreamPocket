package dev.streampocket;

import org.junit.Test;

public final class SourceSchedulerCooldownTest {
    @Test public void originalSingleClockContractStillWorks() throws Exception { DownloadFlowSelfTest.scheduler(); }
    @Test public void freshProcessRestoresRemainingCooldown() throws Exception { SourceSchedulerCooldownSelfTest.restartInAnotherJvm(); }
    @Test public void consecutiveBackoffAndSuccessSurviveRestart() throws Exception { SourceSchedulerCooldownSelfTest.backoffSurvivesAndSuccessClears(); }
    @Test public void successfulRequestsKeepDefaultSpacing() throws Exception { SourceSchedulerCooldownSelfTest.successPreservesDefaultSpacing(); }
    @Test public void wallClockChangesCannotExtendWaits() throws Exception { SourceSchedulerCooldownSelfTest.wallClockChangesStayBounded(); }
    @Test public void expiredAndMalformedStateIsDiscarded() throws Exception { SourceSchedulerCooldownSelfTest.expiredAndMalformedRowsAreRemoved(); }
    @Test public void cancellationPreservesFifoAndPlatformIsolation() throws Exception { SourceSchedulerCooldownSelfTest.fifoCancellationAndIsolation(); }
    @Test public void countdownUpdatesAreThrottledAndCancellable() throws Exception { SourceSchedulerCooldownSelfTest.countdownIsThrottledAndCancellable(); }
    @Test public void storageFailureIsNonFatal() throws Exception { SourceSchedulerCooldownSelfTest.storageFailuresDoNotFailDownloads(); }
    @Test public void storageIoDoesNotHoldAdmissionMonitor() throws Exception { SourceSchedulerCooldownSelfTest.storageDoesNotBlockCancellationOrOtherSources(); }
    @Test public void unknownHostStateHasCapacityAndExpiry() throws Exception { SourceSchedulerCooldownSelfTest.unknownHostsAreBoundedAndExpire(); }
}
