package obsidivision.ghostpath.watch;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import obsidivision.ghostpath.record.Recording;
import org.jspecify.annotations.Nullable;

/**
 * One watched machine (build step 4): the recording it was made from (trigger, seed, kick setting, tick count, the clocks
 * and weather at its start), its current footprint, and what the reruns did so far. Server thread only.
 */
public final class Watched {
    private final Recording recording;
    final Consumer<String> chat;
    Footprint footprint;
    /** What the last run (or the recording) looked like, for the "changed since last" line. */
    Prediction.Summary last;
    @Nullable Prediction latest;
    /** The chunk rectangle of the last run, so that a later run does not copy less than a run that needed more. */
    int @Nullable [] lastRect;
    /** A footprint change has not been rerun yet. */
    boolean dirty;
    /** Server tick of the last footprint change. */
    long lastChange;
    /** What the last change was (for /path watch list). */
    String lastWhat = "-";
    int changes;
    int reruns;
    int cancelled;
    /** Widenings in a row of the current rerun (0 for a run that started from a change). */
    int widenRounds;
    int failures;
    int widenings;
    String status = "idle";
    final EntityWatch entities = new EntityWatch();
    /** Entity changes counted (added, removed, settled at a new position); see {@link EntityWatch}. */
    int entityChanges;

    Watched(Recording recording, Consumer<String> chat) {
        this.recording = recording;
        this.chat = chat;
        this.footprint = Footprint.of(recording);
        this.last = Prediction.Summary.of(recording);
    }

    public Recording recording() {
        return this.recording;
    }

    public BlockPos trigger() {
        return this.recording.trigger();
    }

    /** The footprint as it is now: the recording's, then each rerun's. */
    public Footprint footprint() {
        return this.footprint;
    }

    /** The latest prediction, or null before the first rerun. */
    public @Nullable Prediction latest() {
        return this.latest;
    }

    public boolean dirty() {
        return this.dirty;
    }

    /** Reruns completed (a rerun that had to widen its area counts once). */
    public int reruns() {
        return this.reruns;
    }

    /** Runs cancelled because the footprint changed again, or because a recording started. */
    public int cancelled() {
        return this.cancelled;
    }

    /** Times a run reached beyond its copied area and was rerun with a larger one. */
    public int widenings() {
        return this.widenings;
    }

    public int failures() {
        return this.failures;
    }

    /** Footprint changes seen since the watch started. */
    public int changes() {
        return this.changes;
    }

    /** Real entity changes at the footprint that were counted as footprint changes. */
    public int entityChanges() {
        return this.entityChanges;
    }

    /** Real entities added to the footprint (not counting the first look). */
    public int entitiesAdded() {
        return this.entities.added();
    }

    /** Real entities removed from the footprint. */
    public int entitiesRemoved() {
        return this.entities.removed();
    }

    /** Real entities that came to rest at a new place in the footprint. */
    public int entitiesSettled() {
        return this.entities.settled();
    }

    public String status() {
        return this.status;
    }

    /** The last copied rectangle, as "min..max" chunk coordinates, or "-". */
    public String lastArea() {
        if (this.lastRect == null) {
            return "-";
        }
        return new ChunkPos(this.lastRect[0], this.lastRect[1]).toString() + ".." + new ChunkPos(this.lastRect[2], this.lastRect[3]);
    }
}
