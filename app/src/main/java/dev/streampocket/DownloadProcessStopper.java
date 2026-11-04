package dev.streampocket;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Sends bounded, asynchronous stop signals. Only the download worker can finish an attempt. */
final class DownloadProcessStopper implements AutoCloseable {
    interface StopOperation { boolean stop(String processId) throws Exception; }

    static final class Attempt {
        final String processId;
        private final StopOperation operation;
        private boolean stopping, finished, retryScheduled, signalRunning;
        private Future<?> call, deadline, retry;

        private Attempt(String taskId, StopOperation operation) {
            processId = "download-" + taskId + "-" + UUID.randomUUID();
            this.operation = operation;
        }
    }

    private final ThreadPoolExecutor signals;
    private final ScheduledThreadPoolExecutor timer;
    private final long retryDelayMillis, callTimeoutMillis;

    DownloadProcessStopper() { this(350, 2000); }

    DownloadProcessStopper(long retryDelayMillis, long callTimeoutMillis) {
        if (retryDelayMillis < 0 || callTimeoutMillis <= 0) throw new IllegalArgumentException("Invalid stop timing");
        this.retryDelayMillis = retryDelayMillis;
        this.callTimeoutMillis = callTimeoutMillis;
        // One slow native stop must not occupy the only signal thread. Both the
        // thread count and waiting calls remain bounded across service instances.
        signals = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), threads("download-stop-"));
        signals.allowCoreThreadTimeOut(true);
        timer = new ScheduledThreadPoolExecutor(1, threads("download-stop-timer-"));
        timer.setRemoveOnCancelPolicy(true);
    }

    Attempt newAttempt(String taskId, StopOperation operation) { return new Attempt(taskId, operation); }

    void requestStop(Attempt attempt) {
        if (attempt == null) return;
        synchronized (attempt) {
            if (attempt.finished || attempt.stopping) return;
            attempt.stopping = true;
        }
        submit(attempt, true);
    }

    /** Called after execute and request cleanup return, never when a stop signal times out. */
    void finish(Attempt attempt) {
        if (attempt == null) return;
        synchronized (attempt) {
            attempt.finished = true;
            cancel(attempt.retry, false);
            cancel(attempt.deadline, false);
            cancel(attempt.call, true);
            if (attempt.call instanceof Runnable) signals.remove((Runnable) attempt.call);
        }
    }

    private void submit(Attempt attempt, boolean allowRetry) {
        AtomicBoolean started = new AtomicBoolean();
        AtomicReference<Future<?>> deadline = new AtomicReference<>();
        FutureTask<Void> call = new FutureTask<>(() -> {
            started.set(true);
            boolean signalled = false, ownsSignal = false;
            try {
                synchronized (attempt) {
                    if (attempt.finished || attempt.signalRunning || Thread.currentThread().isInterrupted()) return null;
                    attempt.signalRunning = true; ownsSignal = true;
                }
                signalled = attempt.operation.stop(attempt.processId);
            } catch (Exception unavailable) {
                // The worker still owns the task. A failed signal cannot make it retryable.
            } finally {
                if (ownsSignal) synchronized (attempt) { attempt.signalRunning = false; }
                cancel(deadline.get(), false);
                if (allowRetry && !signalled) retry(attempt);
            }
            return null;
        });
        synchronized (attempt) {
            if (attempt.finished || timer.isShutdown()) return;
            attempt.call = call;
            attempt.deadline = timer.schedule(() -> {
                call.cancel(true);
                signals.remove(call);
                if (allowRetry && !started.get()) retry(attempt);
            }, callTimeoutMillis, TimeUnit.MILLISECONDS);
            deadline.set(attempt.deadline);
        }
        try { signals.execute(call); }
        catch (RejectedExecutionException full) {
            call.cancel(false);
            cancel(deadline.get(), false);
            if (allowRetry) retry(attempt);
        }
    }

    private void retry(Attempt attempt) {
        synchronized (attempt) {
            if (attempt.finished || attempt.retryScheduled || timer.isShutdown()) return;
            attempt.retryScheduled = true;
            // A stop can race ProcessBuilder.start() before the wrapper registers
            // its handle. This one retry keeps the same, never-reused process ID.
            attempt.retry = timer.schedule(() -> submit(attempt, false), retryDelayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private static void cancel(Future<?> future, boolean interrupt) {
        if (future != null) future.cancel(interrupt);
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger count = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override public void close() {
        timer.shutdownNow(); signals.shutdownNow();
    }
}
