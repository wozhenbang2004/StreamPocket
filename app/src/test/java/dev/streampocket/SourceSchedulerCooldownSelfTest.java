package dev.streampocket;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** SDK-free checks; the restart case loads a disk journal in a separate JVM. */
public final class SourceSchedulerCooldownSelfTest {
    private static final String X = "https://x.com/u/status/1?session=do-not-save";
    private static final long WALL = 1_800_000_000_000L;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("restart-child")) {
            SourceScheduler scheduler = new SourceScheduler(() -> 17, () -> WALL + 20_000);
            scheduler.setPersistence(new FileStore(Path.of(args[1])));
            assertCooling(scheduler, X, 40);
            System.out.println("PASS: fresh JVM restored 40 seconds from a disk journal");
            return;
        }
        restartInAnotherJvm();
        backoffSurvivesAndSuccessClears();
        successPreservesDefaultSpacing();
        wallClockChangesStayBounded();
        expiredAndMalformedRowsAreRemoved();
        fifoCancellationAndIsolation();
        countdownIsThrottledAndCancellable();
        storageFailuresDoNotFailDownloads();
        storageDoesNotBlockCancellationOrOtherSources();
        unknownHostsAreBoundedAndExpire();
        System.out.println("PASS: cooldown restart, backoff, clock changes, expiry, FIFO/cancel, progress, storage failures, bounded hosts");
    }

    static void restartInAnotherJvm() throws Exception {
        Path journal = Files.createTempFile("source-cooldown-test-", ".bin");
        try {
            SourceScheduler first = new SourceScheduler(() -> 987_000, () -> WALL);
            first.setPersistence(new FileStore(journal));
            first.outcomeForUrl(X, new Exception("HTTP Error 429"));
            String classes = new File(SourceScheduler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
            String tests = new File(SourceSchedulerCooldownSelfTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
            Process child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                    "-cp", classes + File.pathSeparator + tests, SourceSchedulerCooldownSelfTest.class.getName(),
                    "restart-child", journal.toString()).redirectErrorStream(true).start();
            try {
                check(child.waitFor(8, TimeUnit.SECONDS), "Restart child never completed");
                String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                check(child.exitValue() == 0 && output.contains("fresh JVM restored 40 seconds"), "Restart child failed: " + output);
            } finally {
                if (child.isAlive()) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); }
            }
        } finally { Files.deleteIfExists(journal); }
    }

    static void backoffSurvivesAndSuccessClears() throws Exception {
        MemoryStore store = new MemoryStore();
        SourceScheduler first = new SourceScheduler(() -> 90_000, () -> WALL);
        first.setPersistence(store);
        first.outcomeForUrl(X, new Exception("timed out"));
        check(store.rows.get(0).deadlineWallMillis - WALL == 1500, "Initial timeout backoff changed");
        SourceScheduler second = new SourceScheduler(() -> 10, () -> WALL + 500);
        second.setPersistence(store);
        assertCooling(second, X, 1);
        second.outcomeForUrl(X, new Exception("connection reset"));
        check(store.rows.get(0).failures == 2 && store.rows.get(0).deadlineWallMillis - (WALL + 500) == 3000,
                "Restart lost consecutive failure count");
        for (int i = 0; i < 8; i++) second.outcomeForUrl(X, new Exception("timeout"));
        check(store.rows.get(0).failures == 5 && store.rows.get(0).deadlineWallMillis - (WALL + 500) == 24_000,
                "Network backoff exceeded its existing 24 second ceiling");
        second.outcomeForUrl(X, null);
        check(store.rows.isEmpty(), "Success did not delete the durable cooldown");
        SourceScheduler third = new SourceScheduler(() -> 0, () -> WALL + 500);
        third.setPersistence(store);
        assertAdmitted(third, X);
    }

    static void successPreservesDefaultSpacing() throws Exception {
        AtomicLong mono = new AtomicLong(10_000);
        SourceScheduler scheduler = new SourceScheduler(mono::get, () -> WALL);
        MemoryStore store = new MemoryStore();
        scheduler.setPersistence(store);
        try (SourceScheduler.Lease first = scheduler.acquire(X, () -> false, text -> {})) {
            first.outcome(new Exception("HTTP Error 429"));
            first.outcome(null);
        }
        try (Pending next = new Pending(scheduler, X)) {
            check(next.message().startsWith("等待同平台请求"), "Success retained the failure delay or erased normal spacing");
            check(!next.result.isDone(), "Default spacing was skipped");
            mono.addAndGet(1500); wake(scheduler);
            check(next.result.get(2, TimeUnit.SECONDS), "Cleared cooldown still blocked admission");
        }
        check(store.rows.isEmpty(), "Success left a persistent cooldown");
        check(SourceScheduler.spacing("x") == 1500 && SourceScheduler.spacing("douyin") == 2000
                && SourceScheduler.spacing("bilibili") == 1200, "Default source intervals changed");
    }

    static void wallClockChangesStayBounded() throws Exception {
        MemoryStore original = new MemoryStore();
        SourceScheduler initial = new SourceScheduler(() -> 10_000, () -> WALL);
        initial.setPersistence(original);
        initial.outcomeForUrl(X, new Exception("HTTP Error 429"));
        for (long shiftedWall : new long[]{WALL - 1, WALL - 86_400_000, WALL + 60_000, WALL + 86_400_000}) {
            MemoryStore store = original.copy();
            SourceScheduler restarted = new SourceScheduler(() -> 1, () -> shiftedWall);
            restarted.setPersistence(store);
            assertAdmitted(restarted, X);
            check(store.rows.isEmpty(), "Clock rollback/expired deadline was rebased into another delay");
        }
        AtomicLong mono = new AtomicLong(2000), wall = new AtomicLong(WALL);
        SourceScheduler running = new SourceScheduler(mono::get, wall::get);
        running.setPersistence(new MemoryStore());
        running.outcomeForUrl(X, new Exception("HTTP Error 429"));
        try (Pending wait = new Pending(running, X)) {
            check(wait.message().contains("60 秒"), "Initial cooldown missing");
            wall.addAndGet(86_400_000); mono.addAndGet(1000); wake(running);
            check(wait.message().contains("59 秒"), "Forward wall-clock jump changed an in-process wait");
            wall.addAndGet(-172_800_000); mono.addAndGet(1000); wake(running);
            check(wait.message().contains("58 秒"), "Backward wall-clock jump extended an in-process wait");
        }
    }

    static void expiredAndMalformedRowsAreRemoved() throws Exception {
        MemoryStore store = new MemoryStore();
        store.rows.add(new SourceScheduler.Cooldown("x", WALL - 120_000, WALL - 60_000, 1));
        store.rows.add(new SourceScheduler.Cooldown("bilibili", WALL, WALL + 60_001, 1));
        store.rows.add(new SourceScheduler.Cooldown("douyin", WALL, Long.MAX_VALUE, 1));
        store.rows.add(new SourceScheduler.Cooldown("youtube", WALL, WALL + 5000, -1));
        store.rows.add(new SourceScheduler.Cooldown("https://private.example/v?cookie=secret", WALL, WALL + 5000, 1));
        store.rows.add(new SourceScheduler.Cooldown("vimeo", WALL, WALL + 5000, 2));
        SourceScheduler scheduler = new SourceScheduler(() -> 0, () -> WALL);
        scheduler.setPersistence(store);
        check(store.rows.size() == 1 && store.rows.get(0).key.equals("vimeo"), "Stale or unsafe rows survived restore");
        assertCooling(scheduler, "https://vimeo.com/76979871", 5);
        assertAdmitted(scheduler, X);
    }

    static void fifoCancellationAndIsolation() throws Exception {
        AtomicLong mono = new AtomicLong(10_000);
        SourceScheduler scheduler = new SourceScheduler(mono::get);
        SourceScheduler.Lease first = scheduler.acquire(X, () -> false, text -> {});
        try (Pending cancelled = new Pending(scheduler, X)) {
            cancelled.message();
            try (Pending next = new Pending(scheduler, X)) {
                next.message();
                cancelled.cancel.set(true); wake(scheduler);
                check(!cancelled.result.get(2, TimeUnit.SECONDS), "Cancelled FIFO head acquired a lease");
                assertAdmitted(scheduler, "https://b23.tv/example");
                check(!next.result.isDone(), "A waiter passed a still active lease");
                first.close(); mono.addAndGet(1500); wake(scheduler);
                check(next.result.get(2, TimeUnit.SECONDS), "Cancelled ticket blocked its FIFO successor");
            }
        } finally { first.close(); }
    }

    static void countdownIsThrottledAndCancellable() throws Exception {
        AtomicLong mono = new AtomicLong(10_000);
        SourceScheduler scheduler = new SourceScheduler(mono::get, () -> WALL);
        scheduler.outcomeForUrl(X, new Exception("HTTP Error 429"));
        try (Pending wait = new Pending(scheduler, X)) {
            check(wait.message().contains("60 秒"), "Cooldown has no remaining seconds");
            mono.addAndGet(100); wake(scheduler);
            check(wait.messages.poll(220, TimeUnit.MILLISECONDS) == null, "Progress was emitted more than once a second");
            mono.addAndGet(900); wake(scheduler);
            check(wait.message().contains("59 秒"), "Cooldown progress did not count down");
            wait.cancel.set(true); wake(scheduler);
            check(!wait.result.get(2, TimeUnit.SECONDS), "Countdown ignored cancellation");
        }
        mono.addAndGet(60_000); wake(scheduler);
        assertAdmitted(scheduler, X);
    }

    static void storageFailuresDoNotFailDownloads() throws Exception {
        SourceScheduler scheduler = new SourceScheduler(() -> 10_000, () -> WALL);
        scheduler.setPersistence(new SourceScheduler.Persistence() {
            @Override public List<SourceScheduler.Cooldown> load() throws Exception { throw new java.io.IOException("read failed"); }
            @Override public void save(List<SourceScheduler.Cooldown> rows) throws Exception { throw new java.io.IOException("disk full"); }
        });
        scheduler.outcomeForUrl(X, new Exception("HTTP Error 429"));
        assertCooling(scheduler, X, 60);
        scheduler.outcomeForUrl(X, null);
        assertAdmitted(scheduler, X);
    }

    static void storageDoesNotBlockCancellationOrOtherSources() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean();
        SourceScheduler scheduler = new SourceScheduler(() -> 10_000, () -> WALL);
        MemoryStore durable = new MemoryStore();
        scheduler.setPersistence(new SourceScheduler.Persistence() {
            @Override public List<SourceScheduler.Cooldown> load() { return new ArrayList<>(); }
            @Override public void save(List<SourceScheduler.Cooldown> rows) throws Exception {
                if (block.get()) { writing.countDown(); check(release.await(4, TimeUnit.SECONDS), "Test storage was never released"); }
                durable.save(rows);
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            block.set(true);
            Future<?> failed = pool.submit(() -> scheduler.outcomeForUrl(X, new Exception("HTTP Error 429")));
            check(writing.await(2, TimeUnit.SECONDS), "Failure did not reach storage");
            try (Pending waiting = new Pending(scheduler, X)) {
                check(waiting.message().contains("60 秒"), "Storage write held the admission monitor");
                waiting.cancel.set(true); wake(scheduler);
                check(!waiting.result.get(2, TimeUnit.SECONDS), "Storage write blocked cancellation");
            }
            assertAdmitted(scheduler, "https://b23.tv/example");
            Future<?> success = pool.submit(() -> scheduler.outcomeForUrl(X, null));
            release.countDown();
            failed.get(2, TimeUnit.SECONDS); success.get(2, TimeUnit.SECONDS);
            check(durable.rows.isEmpty(), "An older failed outcome overwrote a successful cleanup");
        } finally {
            release.countDown(); pool.shutdownNow(); check(pool.awaitTermination(2, TimeUnit.SECONDS), "Storage worker leaked");
        }
    }

    static void unknownHostsAreBoundedAndExpire() throws Exception {
        AtomicLong mono = new AtomicLong(10_000);
        SourceScheduler scheduler = new SourceScheduler(mono::get, () -> WALL);
        MemoryStore store = new MemoryStore(); scheduler.setPersistence(store);
        scheduler.outcomeForUrl(X, new Exception("HTTP Error 429"));
        SourceScheduler.Lease active = scheduler.acquire("https://held.example/video", () -> false, text -> {});
        try {
            for (int i = 0; i < 100; i++) {
                mono.incrementAndGet();
                scheduler.outcomeForUrl("https://host" + i + ".example/video?token=never-save", new Exception("HTTP Error 429"));
            }
            Map<?, ?> states = states(scheduler);
            check(states.containsKey("held.example"), "Cache pressure evicted an active lease");
            check(states.size() <= SourceScheduler.MAX_IDLE_HOSTS + 1, "Unknown hosts accumulated without a capacity limit");
            check(store.rows.size() <= SourceScheduler.MAX_SAVED_COOLDOWNS, "Journal grew without a bound");
            check(store.rows.stream().anyMatch(row -> row.key.equals("x")), "Unknown hosts displaced a known platform cooldown");
            for (SourceScheduler.Cooldown row : store.rows) check(!row.key.contains("/") && !row.key.contains("token"), "Journal retained a source URL");
        } finally { active.close(); }
        mono.addAndGet(SourceScheduler.IDLE_HOST_TTL_MILLIS + 1);
        scheduler.outcomeForUrl(X, null);
        check(states(scheduler).size() == 1, "Idle unknown hosts did not expire");
        check(store.rows.isEmpty(), "Expired cooldowns stayed in the journal");
    }

    private static Map<?, ?> states(SourceScheduler scheduler) throws Exception {
        java.lang.reflect.Field field = SourceScheduler.class.getDeclaredField("platforms");
        field.setAccessible(true); return (Map<?, ?>) field.get(scheduler);
    }
    static void assertCooling(SourceScheduler scheduler, String url, long seconds) throws Exception {
        try (Pending wait = new Pending(scheduler, url)) {
            check(wait.message().contains(seconds + " 秒"), "Expected " + seconds + " seconds of cooldown");
            check(!wait.result.isDone(), "Cooldown admitted a request early");
            wait.cancel.set(true); wake(scheduler);
            check(!wait.result.get(2, TimeUnit.SECONDS), "A restored cooldown ignored cancellation");
        }
    }
    private static void assertAdmitted(SourceScheduler scheduler, String url) throws Exception {
        try (Pending request = new Pending(scheduler, url)) {
            check(request.result.get(2, TimeUnit.SECONDS), "An eligible request was not admitted");
        }
    }
    private static void wake(SourceScheduler scheduler) { synchronized (scheduler) { scheduler.notifyAll(); } }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Pending implements AutoCloseable {
        final AtomicBoolean cancel = new AtomicBoolean();
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        final Future<Boolean> result;
        final SourceScheduler scheduler;
        Pending(SourceScheduler scheduler, String url) {
            this.scheduler = scheduler;
            result = pool.submit(() -> {
                try (SourceScheduler.Lease ignored = scheduler.acquire(url, cancel::get, messages::add)) { return true; }
                catch (InterruptedException cancelled) { return false; }
            });
        }
        String message() throws Exception {
            String message = messages.poll(2, TimeUnit.SECONDS);
            check(message != null, "No waiting progress received"); return message;
        }
        @Override public void close() throws Exception {
            cancel.set(true); wake(scheduler); pool.shutdownNow();
            check(pool.awaitTermination(2, TimeUnit.SECONDS), "Scheduler test worker leaked");
        }
    }
    private static final class MemoryStore implements SourceScheduler.Persistence {
        List<SourceScheduler.Cooldown> rows = new ArrayList<>();
        @Override public List<SourceScheduler.Cooldown> load() { return new ArrayList<>(rows); }
        @Override public void save(List<SourceScheduler.Cooldown> rows) { this.rows = new ArrayList<>(rows); }
        MemoryStore copy() { MemoryStore copy = new MemoryStore(); copy.rows = new ArrayList<>(rows); return copy; }
    }
    private static final class FileStore implements SourceScheduler.Persistence {
        private final Path path;
        FileStore(Path path) { this.path = path; }
        @Override public List<SourceScheduler.Cooldown> load() throws Exception {
            List<SourceScheduler.Cooldown> rows = new ArrayList<>();
            if (Files.size(path) == 0) return rows;
            try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
                int count = in.readInt();
                for (int i = 0; i < count; i++) rows.add(new SourceScheduler.Cooldown(in.readUTF(), in.readLong(), in.readLong(), in.readInt()));
            }
            return rows;
        }
        @Override public void save(List<SourceScheduler.Cooldown> rows) throws Exception {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
                out.writeInt(rows.size());
                for (SourceScheduler.Cooldown row : rows) {
                    out.writeUTF(row.key); out.writeLong(row.savedAtWallMillis); out.writeLong(row.deadlineWallMillis); out.writeInt(row.failures);
                }
            }
        }
    }
}
