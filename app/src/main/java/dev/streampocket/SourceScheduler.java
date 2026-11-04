package dev.streampocket;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Shared local admission control for inspect and download metadata. No account or IP rotation. */
public final class SourceScheduler {
    public static final SourceScheduler SHARED = new SourceScheduler();
    static final long MAX_COOLDOWN_MILLIS = 60_000;
    static final long IDLE_HOST_TTL_MILLIS = 15 * 60_000;
    static final int MAX_IDLE_HOSTS = 64;
    static final int MAX_SAVED_COOLDOWNS = 64;
    interface Clock { long now(); }
    private final Clock clock;
    private final Clock wallClock;
    private final Map<String, State> platforms = new HashMap<>();
    private final Object persistenceLock = new Object();
    private volatile Persistence persistence;
    private int twitterCursor;
    public SourceScheduler() { this(() -> System.nanoTime() / 1_000_000, System::currentTimeMillis); }
    SourceScheduler(Clock clock) { this(clock, System::currentTimeMillis); }
    SourceScheduler(Clock clock, Clock wallClock) { this.clock = clock; this.wallClock = wallClock; }

    /** Storage contains only source keys and bounded cooldowns, never URLs or account data. */
    public interface Persistence {
        List<Cooldown> load() throws Exception;
        void save(List<Cooldown> cooldowns) throws Exception;
    }
    public static final class Cooldown {
        public final String key;
        public final long savedAtWallMillis, deadlineWallMillis;
        public final int failures;
        public Cooldown(String key, long savedAtWallMillis, long deadlineWallMillis, int failures) {
            this.key = key; this.savedAtWallMillis = savedAtWallMillis;
            this.deadlineWallMillis = deadlineWallMillis; this.failures = failures;
        }
    }

    /** Call once, before starting inspect/download workers. Later Engine instances are harmless. */
    public void setPersistence(Persistence store) {
        if (store == null) return;
        synchronized (persistenceLock) {
            if (persistence != null) return;
            List<Cooldown> saved = null;
            try { saved = store.load(); } catch (Exception ignored) { /* Keep in-memory admission usable. */ }
            synchronized (this) {
                long now = clock.now(), wallNow = wallClock.now();
                if (saved != null) {
                    int accepted = 0;
                    for (Cooldown row : saved) {
                        if (!validCooldown(row) || wallNow < row.savedAtWallMillis || wallNow >= row.deadlineWallMillis) continue;
                        if (accepted++ >= MAX_SAVED_COOLDOWNS) break;
                        State state = platforms.computeIfAbsent(row.key, unused -> new State(now));
                        // A wall-clock rollback invalidates a saved row; it must not extend a wait.
                        long remaining = row.deadlineWallMillis - wallNow;
                        state.cooldownUntil = Math.max(state.cooldownUntil, now + remaining);
                        state.failures = Math.max(state.failures, row.failures);
                    }
                }
                pruneStates(now);
                persistence = store;
                notifyAll();
            }
            // Rebase valid rows and remove stale/corrupt rows, but never erase after a failed read.
            if (saved != null) saveCooldowns();
        }
    }

    private static final class State {
        final ArrayDeque<Object> waiters = new ArrayDeque<>();
        boolean busy;
        long nextStart = Long.MIN_VALUE;
        long cooldownUntil = Long.MIN_VALUE;
        long lastTouched;
        int failures;
        State(long now) { lastTouched = now; }
    }
    public static String platform(String url) {
        VideoPlatform platform = VideoPlatform.detect(url);
        if (platform != null) return platform.id;
        try {
            String host = new URI(url).getHost().toLowerCase(java.util.Locale.ROOT);
            if (host.equals("douyin.com") || host.endsWith(".douyin.com") || host.equals("iesdouyin.com") || host.endsWith(".iesdouyin.com")) return "douyin";
            return host;
        } catch (Exception invalid) { return "other"; }
    }
    static long spacing(String key) { return key.equals("x") ? 1500 : key.equals("douyin") ? 2000 : 1200; }
    public Lease acquire(String url, BooleanSupplier cancelled, Consumer<String> progress) throws InterruptedException {
        String key = platform(url);
        State state;
        Object ticket = new Object();
        synchronized (this) {
            long now = clock.now();
            pruneStates(now);
            state = platforms.computeIfAbsent(key, unused -> new State(now));
            state.lastTouched = now;
            state.waiters.addLast(ticket);
            pruneStates(now);
        }
        String lastMessage = null;
        long lastReportAt = 0;
        try {
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("已取消等待");
                String report = null;
                synchronized (this) {
                    long now = clock.now();
                    long cooldown = remaining(state.cooldownUntil, now);
                    long remaining = Math.max(remaining(state.nextStart, now), cooldown);
                    if (!state.busy && state.waiters.peekFirst() == ticket && remaining <= 0) {
                        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("已取消等待");
                        state.waiters.removeFirst(); state.busy = true; state.nextStart = now + spacing(key);
                        state.lastTouched = now;
                        return new Lease(this, key, state);
                    }
                    String message = cooldown > 0
                            ? "平台冷却中，还需 " + ((cooldown + 999) / 1000) + " 秒，随后自动继续…"
                            : "等待同平台请求，稍后自动继续…";
                    if (!message.equals(lastMessage) && (lastMessage == null || now - lastReportAt >= 1000)) {
                        report = message; lastMessage = message; lastReportAt = now;
                    } else {
                        wait(150);
                    }
                }
                // UI callbacks and storage I/O must not hold the FIFO monitor.
                if (report != null) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("已取消等待");
                    progress.accept(report);
                }
            }
        } finally {
            synchronized (this) { state.waiters.remove(ticket); pruneStates(clock.now()); notifyAll(); }
        }
    }
    void outcome(String key, Throwable failure) {
        synchronized (this) {
            long now = clock.now();
            State state = platforms.computeIfAbsent(key, unused -> new State(now));
            state.lastTouched = now;
            if (failure == null) {
                state.failures = 0;
                state.cooldownUntil = Long.MIN_VALUE;
            } else {
                SourceFailure.Kind kind = SourceFailure.kind(failure);
                if (kind == SourceFailure.Kind.RATE_LIMIT || kind == SourceFailure.Kind.TIMEOUT || kind == SourceFailure.Kind.NETWORK) {
                    state.failures = Math.min(5, state.failures + 1);
                    long delay = kind == SourceFailure.Kind.RATE_LIMIT ? MAX_COOLDOWN_MILLIS : 1500L << (state.failures - 1);
                    state.cooldownUntil = Math.max(state.cooldownUntil, now + delay);
                }
            }
            pruneStates(now);
            notifyAll();
        }
        saveCooldowns();
    }

    private void saveCooldowns() {
        if (persistence == null) return;
        synchronized (persistenceLock) {
            List<Cooldown> snapshot;
            synchronized (this) { snapshot = cooldownSnapshot(); }
            try { persistence.save(snapshot); } catch (Exception ignored) { /* A storage failure cannot fail a download. */ }
        }
    }

    private List<Cooldown> cooldownSnapshot() {
        long now = clock.now(), wallNow = wallClock.now();
        List<Map.Entry<String, State>> entries = new ArrayList<>(platforms.entrySet());
        entries.sort((a, b) -> {
            int knownFirst = Boolean.compare(isUnknownHost(a.getKey()), isUnknownHost(b.getKey()));
            return knownFirst != 0 ? knownFirst : Long.compare(b.getValue().lastTouched, a.getValue().lastTouched);
        });
        List<Cooldown> result = new ArrayList<>();
        for (Map.Entry<String, State> entry : entries) {
            State state = entry.getValue();
            long delay = Math.min(MAX_COOLDOWN_MILLIS, remaining(state.cooldownUntil, now));
            if (delay == 0 || state.failures == 0 || wallNow < 0 || wallNow > Long.MAX_VALUE - delay) continue;
            Cooldown row = new Cooldown(entry.getKey(), wallNow, wallNow + delay, state.failures);
            if (validCooldown(row)) result.add(row);
            if (result.size() == MAX_SAVED_COOLDOWNS) break;
        }
        return result;
    }

    static boolean validCooldown(Cooldown row) {
        return row != null && row.key != null && row.key.length() <= 253
                && row.key.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
                && row.failures >= 1 && row.failures <= 5 && row.savedAtWallMillis >= 0
                && row.deadlineWallMillis > row.savedAtWallMillis
                && row.deadlineWallMillis - row.savedAtWallMillis <= MAX_COOLDOWN_MILLIS;
    }

    private static long remaining(long deadline, long now) { return deadline > now ? deadline - now : 0; }
    private static boolean isUnknownHost(String key) { return !key.equals("other") && VideoPlatform.fromId(key) == null; }

    /** Retain at most 64 idle unknown hosts; leases and FIFO waiters are never evicted. */
    private void pruneStates(long now) {
        List<Map.Entry<String, State>> idleHosts = new ArrayList<>();
        int hostCount = 0;
        for (Iterator<Map.Entry<String, State>> it = platforms.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, State> entry = it.next();
            if (!isUnknownHost(entry.getKey())) continue;
            State state = entry.getValue();
            if (!state.busy && state.waiters.isEmpty()) {
                if (now - state.lastTouched >= IDLE_HOST_TTL_MILLIS) { it.remove(); continue; }
                idleHosts.add(entry);
            }
            hostCount++;
        }
        idleHosts.sort((a, b) -> Long.compare(a.getValue().lastTouched, b.getValue().lastTouched));
        for (Map.Entry<String, State> entry : idleHosts) {
            if (hostCount <= MAX_IDLE_HOSTS) break;
            platforms.remove(entry.getKey()); hostCount--;
        }
    }
    public synchronized List<String> twitterApis() {
        List<String> apis = Arrays.asList("graphql", "syndication", "legacy");
        List<String> result = new ArrayList<>();
        int start = twitterCursor++ % apis.size();
        for (int i = 0; i < apis.size(); i++) result.add(apis.get((start + i) % apis.size()));
        return result;
    }
    public void outcomeForUrl(String url, Throwable failure) { outcome(platform(url), failure); }
    public static final class Lease implements AutoCloseable {
        private final SourceScheduler owner;
        private final String key;
        private final State state;
        private boolean closed;
        private Lease(SourceScheduler owner, String key, State state) { this.owner = owner; this.key = key; this.state = state; }
        public void outcome(Throwable failure) { owner.outcome(key, failure); }
        @Override public void close() {
            synchronized (owner) {
                if (!closed) {
                    closed = true; state.busy = false; state.lastTouched = owner.clock.now();
                    owner.pruneStates(state.lastTouched); owner.notifyAll();
                }
            }
        }
    }
}
