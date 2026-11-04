package dev.streampocket;

/** One interpretation of saved playback, shared by catalogue queries, badges and playback. */
final class LibraryWatchState {
    static final String ALL = "all", CONTINUE = "continue", UNWATCHED = "unwatched", WATCHED = "watched";
    private static final String WATCHED_SQL = "(duration_ms>0 AND playback_ms>0 AND playback_ms>=duration_ms-MIN(500,duration_ms/20))";
    private LibraryWatchState() {}

    static String normalizeFilter(String filter) {
        return CONTINUE.equals(filter) || UNWATCHED.equals(filter) || WATCHED.equals(filter) ? filter : ALL;
    }

    static boolean isWatched(long positionMs, long durationMs) {
        return durationMs > 0 && positionMs > 0 && positionMs >= durationMs - Math.min(500, durationMs / 20);
    }

    static boolean isContinuing(long positionMs, long durationMs) {
        return positionMs > 0 && !isWatched(positionMs, durationMs);
    }

    static String sql(String filter) {
        switch (normalizeFilter(filter)) {
            case CONTINUE: return "(playback_ms>0 AND NOT " + WATCHED_SQL + ")";
            case UNWATCHED: return "playback_ms<=0";
            case WATCHED: return WATCHED_SQL;
            default: return "1=1";
        }
    }
}
