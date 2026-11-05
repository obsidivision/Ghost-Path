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
 * @param area       the ghost area overlay's data from the same run (footprint boxes, watched margin, copied chunks, last trigger), or null
 * @param origin     the machine's trigger and the game time of the press (for saved paths), or null
 */
public record PathSnapshot(long serial, String source, String dimension, int ticks, boolean limitHit, List<Line> lines, @Nullable AreaData area, @Nullable Origin origin) {

    public PathSnapshot(long serial, String source, String dimension, int ticks, boolean limitHit, List<Line> lines) {
        this(serial, source, dimension, ticks, limitHit, lines, null, null);
    }

    public PathSnapshot(long serial, String source, String dimension, int ticks, boolean limitHit, List<Line> lines, @Nullable AreaData area) {
        this(serial, source, dimension, ticks, limitHit, lines, area, null);
    }

    /** The same snapshot with the area data (the paths are shared, not copied). */
    public PathSnapshot withArea(@Nullable AreaData area) {
        return new PathSnapshot(this.serial, this.source, this.dimension, this.ticks, this.limitHit, this.lines, area, this.origin);
    }

    /** The same snapshot with the machine's trigger and press time. */
    public PathSnapshot withOrigin(@Nullable Origin origin) {
        return new PathSnapshot(this.serial, this.source, this.dimension, this.ticks, this.limitHit, this.lines, this.area, origin);
    }

    /** The machine's trigger block and the level's game time when it was pressed. */
    public record Origin(int x, int y, int z, long gameTime) {
    }

    /**
     * One path (or several identical ones, {@code count}): the chain entity's position after each of its ticks, from the
     * tick it was added. {@code ticks[i]} is the tick relative to the start of the recording, {@code xyz[3i..3i+2]} the
     * position (exact doubles). For the inspector (step 6), per point: {@code vel[3i..3i+2]} the velocity after the tick,
     * {@code box[6i..6i+5]} the bounding box (minX, minY, minZ, maxX, maxY, maxZ), {@code fuse[i]} a primed TNT's fuse (else -1).
     * {@code members}: the chain entity indices of the identical paths merged into this line ({@code count} of them, in order);
     * an index is the entity's place in the order the chain created entities, the same in a rerun, so a pin finds it again.
     */
    public record Line(String type, FlightKind kind, int count, long startTick, long[] ticks, double[] xyz, double[] vel, double[] box, int[] fuse, int[] members,
                       End end, @Nullable Tail tail, @Nullable Freeze freeze, List<Stasis> stasis) {
        public Line(String type, FlightKind kind, int count, long startTick, long[] ticks, double[] xyz, double[] vel, double[] box, int[] fuse, int[] members,
                    End end, @Nullable Tail tail, @Nullable Freeze freeze) {
            this(type, kind, count, startTick, ticks, xyz, vel, box, fuse, members, end, tail, freeze, List.of());
        }

        /** The stretch frozen right after point {@code i} (its freeze point), or null. */
        public @Nullable Stasis stasisAt(int i) {
            for (Stasis st : this.stasis) {
                if (st.point() == i) {
                    return st;
                }
            }
            return null;
        }

        /** Whether the segment from point {@code i - 1} to point {@code i} is a move while frozen (a piston push): drawn dashed. */
        public boolean movedWhileFrozen(int i) {
            for (Stasis st : this.stasis) {
                if (st.resume() == i && st.point() == i - 1) {
                    return true;
                }
            }
            return false;
        }

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

    /**
     * A stretch of ticks in which the entity did not tick in the run itself (its chunk does not tick entities: a lazy chunk, or not loaded),
     * session "frozen entities". Nothing is drawn for those ticks.
     *
     * @param point     index of the freeze point: the last point before the frozen ticks (where it stopped), or, for an entity frozen from
     *                  its first point on, that point
     * @param resume    index of the point where it is at the end of the stretch, if it was moved while frozen (a piston push; drawn as a
     *                  dashed line from {@code point}), else -1 (the path goes on from the freeze point)
     * @param fromTick  first frozen tick, {@code toTick} the last
     * @param kind      "lazy chunk" or "unloaded"
     * @param resumed   it ticked again after the stretch (else the run ended with it frozen)
     * @param ticks     every frozen tick; {@code vel[3i..3i+2]} its velocity at the end of that tick (explosions add to it: lazy acceleration)
     * @param pushes    chain explosions that pushed it while frozen: "explosion #k t+N", in order
     */
    public record Stasis(int point, int resume, long fromTick, long toTick, String kind, boolean resumed, long[] ticks, double[] vel, List<String> pushes) {
        public int frozenTicks() {
            return this.ticks.length;
        }

        /** A text that is equal for equal stretches (the arrays compare by content), for merging identical paths. */
        public String signature() {
            return this.point + "/" + this.resume + "/" + this.fromTick + "/" + this.toTick + "/" + this.kind + "/" + this.resumed + "/" + java.util.Arrays.toString(this.ticks)
                + "/" + java.util.Arrays.toString(this.vel) + "/" + this.pushes;
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
