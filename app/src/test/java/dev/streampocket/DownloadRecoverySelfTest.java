package dev.streampocket;

/** Recovery decisions run without Android; persisted/service behavior has separate Robolectric coverage. */
public final class DownloadRecoverySelfTest {
    private static int checks;
    public static void main(String[] args) {
        check("cancelled".equals(DownloadRecovery.restartStatus("cancelling", DownloadRecovery.CANCEL, false, false)),
                "a committed cancel survives death before native unwind");
        check("interrupted".equals(DownloadRecovery.restartStatus("cancelling", DownloadRecovery.RUN, false, false)),
                "a system stop is distinct from user cancellation");
        check("interrupted".equals(DownloadRecovery.restartStatus("cancelling", "", false, false)),
                "legacy cancelling remains ambiguous");
        check(!eligible("interrupted", "", "video"), "ambiguous legacy work never starts automatically");
        check("paused".equals(DownloadRecovery.restartStatus("pausing", DownloadRecovery.PAUSE, true, false)),
                "manual pause survives native-process death");
        check("paused".equals(DownloadRecovery.restartStatus("queued", DownloadRecovery.RUN, false, true)),
                "cold start honors the global hold");
        check("completed".equals(DownloadRecovery.restartStatus("completed", DownloadRecovery.CANCEL, false, true)),
                "late cancellation cannot undo a committed output");
        for (String phase : new String[]{"queued", "preparing", "video", "audio", "media", "retrying"})
            check(eligible("interrupted", DownloadRecovery.RUN, phase), "known transfer can recover: " + phase);
        for (String phase : new String[]{"", "merging", "processing", "saving", "completed", "future-phase"})
            check(!eligible("interrupted", DownloadRecovery.RUN, phase), "non-transfer phase requires explicit retry: " + phase);
        for (String status : new String[]{"paused", "pausing", "cancelled", "cancelling", "completed", "failed", "downloading", "queued"})
            check(!eligible(status, DownloadRecovery.RUN, "video"), "recovery cannot seize another state: " + status);
        check(!eligible("interrupted", DownloadRecovery.PAUSE, "video"), "manual pause is not recovery intent");
        check(!eligible("interrupted", DownloadRecovery.CANCEL, "video"), "cancel is not recovery intent");
        check(!DownloadRecovery.canRecover("interrupted", DownloadRecovery.RUN, "video", true, false, false, false, false),
                "completed transfer is not replayed through recovery");
        check(!DownloadRecovery.canRecover("interrupted", DownloadRecovery.RUN, "video", false, true, false, false, false),
                "publication barrier is durable even when the last phase is stale");
        check(!DownloadRecovery.canRecover("interrupted", DownloadRecovery.RUN, "video", false, false, true, false, false),
                "published output prevents duplicate export");
        check(!DownloadRecovery.canRecover("interrupted", DownloadRecovery.RUN, "video", false, false, false, true, false),
                "queue pause blocks recovery");
        check(!DownloadRecovery.canRecover("interrupted", DownloadRecovery.RUN, "video", false, false, false, false, true),
                "a still-owned native attempt cannot be seized");
        System.out.println("DownloadRecoverySelfTest: " + checks + " checks passed");
    }
    private static boolean eligible(String status, String intent, String phase) {
        return DownloadRecovery.canRecover(status, intent, phase, false, false, false, false, false);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
