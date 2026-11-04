package obsidivision.ghostpath.record;

import org.jspecify.annotations.Nullable;

/** The latest finished recording, in memory only, for step 2 (the ghost copy). Replaced by the next one. */
public final class RecordingStore {
    private static volatile @Nullable Recording latest;

    private RecordingStore() {
    }

    public static @Nullable Recording latest() {
        return latest;
    }

    static void set(Recording recording) {
        latest = recording;
    }

    public static void clear() {
        latest = null;
    }
}
