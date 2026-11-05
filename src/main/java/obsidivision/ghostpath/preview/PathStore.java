package obsidivision.ghostpath.preview;

import org.jspecify.annotations.Nullable;

/**
 * The handoff to the client (build step 5). The server thread builds an immutable {@link PathSnapshot} when a recording or a
 * rerun is finished and publishes it here; the client thread reads the latest. A volatile reference to an object that is
 * never changed again: no lock, and the client never sees ghost data, because the snapshot is built from copies after the
 * ghost is gone.
 */
public final class PathStore {
    private static volatile @Nullable PathSnapshot latest;
    private static long serial;

    private PathStore() {
    }

    /** Server thread. */
    public static PathSnapshot publish(java.util.function.LongFunction<PathSnapshot> build) {
        PathSnapshot s = build.apply(++serial);
        latest = s;
        return s;
    }

    /** Any thread. The latest snapshot, or null. */
    public static @Nullable PathSnapshot latest() {
        return latest;
    }

    /**
     * Registered by the client: the continuation points of line {@code index} of a snapshot (data the client computed, see {@code Tails}), or
     * null. Absent without a client; a saved path then has no continuation points.
     */
    public static volatile java.util.function.@Nullable BiFunction<PathSnapshot, Integer, obsidivision.ghostpath.saved.SavedPath.@Nullable Cont> continuationSource;

    /** Continuation points of every line of {@code s} that has any, by line index. */
    public static java.util.Map<Integer, obsidivision.ghostpath.saved.SavedPath.Cont> continuations(PathSnapshot s) {
        java.util.function.BiFunction<PathSnapshot, Integer, obsidivision.ghostpath.saved.SavedPath.Cont> src = continuationSource;
        java.util.Map<Integer, obsidivision.ghostpath.saved.SavedPath.Cont> out = new java.util.HashMap<>();
        if (src != null) {
            for (int i = 0; i < s.lines().size(); i++) {
                if (s.lines().get(i).tail() != null) {
                    obsidivision.ghostpath.saved.SavedPath.Cont c = src.apply(s, i);
                    if (c != null) {
                        out.put(i, c);
                    }
                }
            }
        }
        return out;
    }

    public static void clear() {
        latest = null;
    }
}
