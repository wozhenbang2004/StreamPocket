package dev.streampocket;

import java.util.Arrays;
import java.util.List;

/** Queue-intent regression checks that also run with a plain JDK, without an Android SDK. */
public final class DownloadQueuePolicySelfTest {
    private static int checks;
    public static void main(String[] args) {
        check(DownloadQueuePolicy.held("paused", "queue"), "queue pause is a held waiter");
        check(!DownloadQueuePolicy.held("paused", "manual"), "manual pause is never a global waiter");
        check(!DownloadQueuePolicy.held("paused", ""), "legacy pause remains manual");
        check(DownloadQueuePolicy.waiting("queued", ""), "accepted queue is waiting");
        check(DownloadQueuePolicy.waiting("paused", "queue"), "held queue retains its position");
        check(!DownloadQueuePolicy.waiting("downloading", "queue"), "active transfer is not reorderable");
        check(DownloadQueuePolicy.stoppingQueue("pausing", "queue"), "global stop has not exited");
        check(!DownloadQueuePolicy.stoppingQueue("paused", "queue"), "stopped transfer has exited");
        for (String status : Arrays.asList("queued", "downloading", "merging", "saving", "pausing", "cancelling", "completed", "cancelled", "failed", "interrupted"))
            check(!DownloadQueuePolicy.accepts(status, "queue", DownloadQueuePolicy.Admission.RESUME_QUEUE), "global resume rejects " + status);
        check(DownloadQueuePolicy.accepts("paused", "queue", DownloadQueuePolicy.Admission.RESUME_QUEUE), "global resume accepts only its pauses");
        check(!DownloadQueuePolicy.accepts("paused", "manual", DownloadQueuePolicy.Admission.RESUME_QUEUE), "global resume honors explicit manual stop");
        for (String status : Arrays.asList("queued", "downloading", "merging", "saving", "pausing", "cancelling", "completed", "cancelled", "paused"))
            check(!DownloadQueuePolicy.accepts(status, "manual", DownloadQueuePolicy.Admission.RETRY_FAILED), "failure retry rejects " + status);
        check(DownloadQueuePolicy.accepts("failed", "", DownloadQueuePolicy.Admission.RETRY_FAILED), "failed work can retry");
        check(DownloadQueuePolicy.accepts("interrupted", "", DownloadQueuePolicy.Admission.RETRY_FAILED), "interrupted work can retry");
        check(DownloadQueuePolicy.accepts("paused", "manual", DownloadQueuePolicy.Admission.RESUME), "explicit selected resume accepts a manual pause");
        check(!DownloadQueuePolicy.accepts("paused", "queue", DownloadQueuePolicy.Admission.RESUME), "duplicate held resume cannot reorder a task");
        check(!DownloadQueuePolicy.accepts("paused", "queue", DownloadQueuePolicy.Admission.ANY), "duplicate held enqueue cannot reorder a task");
        check(DownloadQueuePolicy.accepts("cancelled", "", DownloadQueuePolicy.Admission.ANY), "an explicit individual cancelled-task retry remains available");
        check(!DownloadQueuePolicy.accepts("pausing", "manual", DownloadQueuePolicy.Admission.ANY), "stopping work cannot start another attempt");
        check(!DownloadQueuePolicy.accepts("cancelling", "", DownloadQueuePolicy.Admission.ANY), "cancelling work cannot start another attempt");
        check(!DownloadQueuePolicy.accepts("completed", "", DownloadQueuePolicy.Admission.ANY), "saved output cannot be enqueued");
        List<String> waiting = Arrays.asList("a", "b", "c");
        check(DownloadQueuePolicy.move(waiting, "c", true).equals(Arrays.asList("c", "a", "b")), "next preserves other waiters");
        check(DownloadQueuePolicy.move(waiting, "a", false).equals(Arrays.asList("b", "c", "a")), "last preserves other waiters");
        check(DownloadQueuePolicy.move(waiting, "missing", true).equals(waiting), "stale action cannot create a task");
        check(waiting.equals(Arrays.asList("a", "b", "c")), "priority does not mutate its caller");
        System.out.println("DownloadQueuePolicySelfTest: " + checks + " checks passed");
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
