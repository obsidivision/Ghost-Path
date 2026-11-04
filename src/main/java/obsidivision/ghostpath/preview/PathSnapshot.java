package obsidivision.ghostpath.preview;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;
import org.jspecify.annotations.Nullable;

/**
 * What the flight preview draws (build step 5): the latest prediction as plain data, copied on the server thread when a
 * recording or a rerun is finished (the ghost is gone by then). Immutable after it is published; the client reads it from
 * {@link PathStore} without packets (singleplayer: the built-in server shares the JVM). Nothing here refers to a world,
 * an entity or the ghost.
 *
 * @param serial     counts up with each published snapshot; the client redoes what it derived (the continuations) when it changes
 * @param source     "recorded" (the real run of {@code /path machine}) or "rerun" (a ghost rerun after a footprint change)
 * @param dimension  dimension id of the machine; the client draws only in that dimension
 * @param ticks      ticks the run lasted
 * @param limitHit   the run stopped at the tick limit
 * @param lines      one entry per path, identical paths merged ({@link Line#count})
 */
public record PathSnapshot(long serial, String source, String dimension, int ticks, boolean limitHit, List<Line> lines) {

    /**
     * One path (or several identical ones, {@code count}): the chain entity's position after each of its ticks, from the
     * tick it was added. {@code ticks[i]} is the tick relative to the start of the recording, {@code xyz[3i..3i+2]} the
     * position (exact doubles).
     */
    public record Line(String type, FlightKind kind, int count, long startTick, long[] ticks, double[] xyz, End end, @Nullable Tail tail) {
        public int points() {
            return this.ticks.length;
        }
    }

    /** How a path ends: one of {@link PathBuilder#REASONS}, the tick, and where. */
    public record End(String reason, long tick, double x, double y, double z) {
    }

    /**
     * A projectile that was still flying when the run stopped at the tick limit: its saved state, for the client to continue
     * with a copy that never joins the world. {@code nbt} is the entity's saved data (position, motion, rotation, ...);
     * the rest is what the save does not hold.
     */
    public record Tail(String type, CompoundTag nbt, int tickCount, double xo, double yo, double zo, boolean onGround) {
    }
}
