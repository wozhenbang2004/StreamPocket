package dev.streampocket;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Shared local admission control for inspect and download metadata. No account or IP rotation. */
public final class SourceScheduler {
    public static final SourceScheduler SHARED = new SourceScheduler();
    interface Clock { long now(); }
    private final Clock clock;
    private final Map<String, State> platforms = new HashMap<>();
    private int twitterCursor;
    public SourceScheduler() { this(() -> System.nanoTime() / 1_000_000); }
    SourceScheduler(Clock clock) { this.clock = clock; }
    private static final class State {
        final ArrayDeque<Object> waiters = new ArrayDeque<>();
        boolean busy;
        long nextStart;
        int failures;
    }
    public static String platform(String url) {
        if (CoreRules.isTwitterUrl(url)) return "x";
        if (CoreRules.isBilibiliUrl(url)) return "bilibili";
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
        synchronized (this) { state = platforms.computeIfAbsent(key, unused -> new State()); state.waiters.addLast(ticket); }
        boolean reported = false;
        try {
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("已取消等待");
                synchronized (this) {
                    long remaining = state.nextStart - clock.now();
                    if (!state.busy && state.waiters.peekFirst() == ticket && remaining <= 0) {
                        state.waiters.removeFirst(); state.busy = true; state.nextStart = clock.now() + spacing(key);
                        return new Lease(this, key, state);
                    }
                    if (!reported) {
                        progress.accept(remaining > spacing(key) ? "平台冷却中，稍后自动继续…" : "等待同平台请求，稍后自动继续…");
                        reported = true;
                    }
                    wait(150);
                }
            }
        } finally {
            synchronized (this) { state.waiters.remove(ticket); notifyAll(); }
        }
    }
    synchronized void outcome(String key, Throwable failure) {
        State state = platforms.computeIfAbsent(key, unused -> new State());
        if (failure == null) { state.failures = 0; return; }
        SourceFailure.Kind kind = SourceFailure.kind(failure);
        if (kind == SourceFailure.Kind.RATE_LIMIT || kind == SourceFailure.Kind.TIMEOUT || kind == SourceFailure.Kind.NETWORK) {
            state.failures = Math.min(5, state.failures + 1);
            long delay = kind == SourceFailure.Kind.RATE_LIMIT ? 60_000 : 1500L << (state.failures - 1);
            state.nextStart = Math.max(state.nextStart, clock.now() + delay);
        }
        notifyAll();
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
            synchronized (owner) { if (!closed) { closed = true; state.busy = false; owner.notifyAll(); } }
        }
    }
}
