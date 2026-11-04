package dev.streampocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the production stop executor without Android or a real native process. */
public final class DownloadProcessStopperSelfTest {
    public static void main(String[] args) throws Exception {
        blockedStopDoesNotBlockTheCallerOrAnotherAttempt();
        registrationRetryKeepsTheOriginalIdentityAndIsDeduplicated();
        finishedAttemptCannotSendALateRegistrationRetry();
        stopDeadlineInterruptsTheSignalWithoutCompletingTheDownload();
        uncooperativeStopsKeepThreadAndQueueGrowthBounded();
        System.out.println("DownloadProcessStopperSelfTest: 5 checks passed");
    }

    static void blockedStopDoesNotBlockTheCallerOrAnotherAttempt() throws Exception {
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (DownloadProcessStopper stops = new DownloadProcessStopper(20, 2000)) {
            DownloadProcessStopper.Attempt first = stops.newAttempt("same-task", id -> {
                calls.incrementAndGet(); entered.countDown(); awaitUninterruptibly(release); return true;
            });
            Thread caller = new Thread(() -> { stops.requestStop(first); returned.countDown(); }, "stop-test-caller");
            caller.setDaemon(true); caller.start();
            try {
                await(returned, "Stop blocked its caller"); await(entered, "First signal never started");
                for (int i = 0; i < 5; i++) stops.requestStop(first);
                CountDownLatch secondStopped = new CountDownLatch(1);
                DownloadProcessStopper.Attempt second = stops.newAttempt("same-task", id -> { secondStopped.countDown(); return true; });
                check(!first.processId.equals(second.processId), "A retry reused a process ID");
                stops.requestStop(second); await(secondStopped, "One blocked stop prevented another attempt from stopping");
                check(calls.get() == 1, "Repeated Stop started duplicate signals");
                stops.finish(second); stops.finish(first);
            } finally { release.countDown(); caller.join(2000); }
        }
    }

    static void registrationRetryKeepsTheOriginalIdentityAndIsDeduplicated() throws Exception {
        List<String> observed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch retried = new CountDownLatch(1);
        try (DownloadProcessStopper stops = new DownloadProcessStopper(30, 1000)) {
            DownloadProcessStopper.Attempt attempt = stops.newAttempt("late-registration", id -> {
                observed.add(id);
                if (observed.size() == 1) return false;
                retried.countDown(); return true;
            });
            for (int i = 0; i < 10; i++) stops.requestStop(attempt);
            await(retried, "A stop before process registration was not retried");
            stops.finish(attempt);
            check(observed.size() == 2, "Registration retry was duplicated");
            check(attempt.processId.equals(observed.get(0)) && observed.get(0).equals(observed.get(1)), "Retry targeted another process");
        }
    }

    static void finishedAttemptCannotSendALateRegistrationRetry() throws Exception {
        CountDownLatch firstCall = new CountDownLatch(1), lateCall = new CountDownLatch(1), replacementCall = new CountDownLatch(1);
        AtomicInteger oldCalls = new AtomicInteger();
        try (DownloadProcessStopper stops = new DownloadProcessStopper(100, 1000)) {
            DownloadProcessStopper.Attempt old = stops.newAttempt("same-task", id -> {
                if (oldCalls.incrementAndGet() > 1) lateCall.countDown();
                firstCall.countDown(); return false;
            });
            stops.requestStop(old); await(firstCall, "First signal never ran"); stops.finish(old);
            DownloadProcessStopper.Attempt current = stops.newAttempt("same-task", id -> { replacementCall.countDown(); return true; });
            stops.requestStop(old);
            stops.requestStop(current); await(replacementCall, "Replacement signal never ran");
            check(!lateCall.await(250, TimeUnit.MILLISECONDS), "A finished attempt sent a delayed stop");
            check(!old.processId.equals(current.processId), "Replacement reused the finished process ID");
            stops.finish(current);
        }
    }

    static void stopDeadlineInterruptsTheSignalWithoutCompletingTheDownload() throws Exception {
        CountDownLatch signalInterrupted = new CountDownLatch(1), neverReleased = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (DownloadProcessStopper stops = new DownloadProcessStopper(20, 80)) {
            DownloadProcessStopper.Attempt attempt = stops.newAttempt("slow-stop", id -> {
                calls.incrementAndGet();
                try { neverReleased.await(); }
                catch (InterruptedException timeout) { signalInterrupted.countDown(); }
                return true;
            });
            stops.requestStop(attempt); await(signalInterrupted, "The native signal wait had no deadline");
            // Neither timeout nor a successful signal finishes the execution.
            // Another stop is still deduplicated until the worker explicitly finishes it.
            stops.requestStop(attempt);
            check(calls.get() == 1, "Signal timeout discarded the attempt's stop ownership");
            stops.finish(attempt);
        }
    }

    static void uncooperativeStopsKeepThreadAndQueueGrowthBounded() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2), release = new CountDownLatch(1);
        Set<Thread> nativeThreads = ConcurrentHashMap.newKeySet();
        List<DownloadProcessStopper.Attempt> attempts = new ArrayList<>();
        try (DownloadProcessStopper stops = new DownloadProcessStopper(10, 50)) {
            try {
                for (int i = 0; i < 2; i++) {
                    DownloadProcessStopper.Attempt attempt = stops.newAttempt("blocked-" + i, id -> {
                        nativeThreads.add(Thread.currentThread()); bothEntered.countDown(); awaitUninterruptibly(release); return true;
                    });
                    attempts.add(attempt); stops.requestStop(attempt);
                }
                await(bothEntered, "Both bounded signal slots were not used");
                for (int i = 0; i < 30; i++) {
                    DownloadProcessStopper.Attempt queued = stops.newAttempt("queued-" + i, id -> {
                        nativeThreads.add(Thread.currentThread()); return true;
                    });
                    attempts.add(queued); stops.requestStop(queued);
                }
                check(nativeThreads.size() == 2, "Uncooperative stops caused unbounded signal threads");
                for (DownloadProcessStopper.Attempt attempt : attempts) stops.finish(attempt);
            } finally { release.countDown(); }
        }
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), message);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) try { latch.await(); break; }
        catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
