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

    public static void clear() {
        latest = null;
    }
}
