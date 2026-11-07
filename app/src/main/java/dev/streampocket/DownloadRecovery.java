package dev.streampocket;

/** Durable user intent is separate from the state of the last native process. */
final class DownloadRecovery {
    static final String INTENT = "runIntent";
    static final String RUN = "run", PAUSE = "pause", CANCEL = "cancel";
    private DownloadRecovery() {}

    static String restartStatus(String status, String intent, boolean pauseRequested, boolean queuePaused) {
        if ("completed".equals(status)) return status;
        if (!DownloadQueuePolicy.active(status)) return status;
        if (CANCEL.equals(intent)) return "cancelled";
        if (PAUSE.equals(intent) || ("pausing".equals(status) && pauseRequested)
                || (queuePaused && "queued".equals(status))) return "paused";
        return "interrupted";
    }

    static boolean canRecover(String status, String intent, String phase, boolean transferComplete,
            boolean publicationStarted, boolean hasPublishedOutput, boolean queuePaused, boolean owned) {
        if (!"interrupted".equals(status) || !RUN.equals(intent) || queuePaused || owned
                || transferComplete || publicationStarted || hasPublishedOutput) return false;
        // Unknown/new phases and publication work require an explicit user retry.
        return "queued".equals(phase) || "preparing".equals(phase) || "video".equals(phase)
                || "audio".equals(phase) || "media".equals(phase) || "retrying".equals(phase);
    }
}
