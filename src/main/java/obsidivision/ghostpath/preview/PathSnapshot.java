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
     * position (exact doubles). For the inspector (step 6), per point: {@code vel[3i..3i+2]} the velocity after the tick,
     * {@code box[6i..6i+5]} the bounding box (minX, minY, minZ, maxX, maxY, maxZ), {@code fuse[i]} a primed TNT's fuse (else -1).
     * {@code members}: the chain entity indices of the identical paths merged into this line ({@code count} of them, in order);
     * an index is the entity's place in the order the chain created entities, the same in a rerun, so a pin finds it again.
     */
    public record Line(String type, FlightKind kind, int count, long startTick, long[] ticks, double[] xyz, double[] vel, double[] box, int[] fuse, int[] members,
                       End end, @Nullable Tail tail, @Nullable Freeze freeze) {
        public int points() {
            return this.ticks.length;
        }

        /** Index of the point at {@code tick}, or -1 (ticks are strictly increasing, one point each). */
        public int indexOfTick(long tick) {
            int i = java.util.Arrays.binarySearch(this.ticks, tick);
            return i >= 0 ? i : -1;
        }
    }

    /**
     * Where the real world would stop this path: the first point (index {@code point}, tick {@code tick}, position) in a chunk whose
     * entities do not tick in the real world. {@code kind} is "unloaded" (the chunk is not loaded, the ghost took it from the save) or
     * "lazy" (loaded, but its entities do not tick). Only reruns have it; from that point on the path is what the ghost predicts for a
     * world where the chunk ticked.
     */
    public record Freeze(int point, long tick, String kind, double x, double y, double z) {
    }

    /**
     * The end of the path as text: "exploded t+89", or, when the real world freezes the entity earlier, both:
     * "real: frozen at t+36; ghost: hit block at t+52".
     */
    public static String endText(Line l) {
        Freeze f = l.freeze();
        if (f == null) {
            return l.end().reason() + " t+" + l.end().tick();
        }
        return "real: frozen at t+" + f.tick() + "; ghost: " + l.end().reason() + " at t+" + l.end().tick();
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
