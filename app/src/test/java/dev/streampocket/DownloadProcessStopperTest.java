package dev.streampocket;

import org.junit.Test;

public final class DownloadProcessStopperTest {
    @Test public void aSlowStopDoesNotBlockTheCallerOrAnotherAttempt() throws Exception {
        DownloadProcessStopperSelfTest.blockedStopDoesNotBlockTheCallerOrAnotherAttempt();
    }
    @Test public void registrationRetryTargetsOnlyItsOwnAttempt() throws Exception {
        DownloadProcessStopperSelfTest.registrationRetryKeepsTheOriginalIdentityAndIsDeduplicated();
    }
    @Test public void aFinishedAttemptCannotStopItsReplacement() throws Exception {
        DownloadProcessStopperSelfTest.finishedAttemptCannotSendALateRegistrationRetry();
    }
    @Test public void signalTimeoutDoesNotFinishTheExecution() throws Exception {
        DownloadProcessStopperSelfTest.stopDeadlineInterruptsTheSignalWithoutCompletingTheDownload();
    }
    @Test public void uncooperativeSignalsRemainBounded() throws Exception {
        DownloadProcessStopperSelfTest.uncooperativeStopsKeepThreadAndQueueGrowthBounded();
    }
}
