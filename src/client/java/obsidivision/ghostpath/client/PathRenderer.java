package obsidivision.ghostpath.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;

/**
 * Draws the latest {@link PathSnapshot} (build step 5) with the game's own gizmos (lines, points, billboard text), added in
 * Fabric's {@code LevelRenderEvents.BEFORE_GIZMOS}, i.e. just before the frame's gizmos are collected: for each path a thin
 * line through the entity's position after each tick and a small dot every {@code /path interval} ticks (start and end
 * always), the end reason at the last dot, "&times;N" at the start of N identical paths. A continuation computed on the
 * client ({@link Tails}) is drawn in a lighter colour and labelled "continued". Nothing is read from the ghost or from a
 * world: only the snapshot.
 */
public final class PathRenderer {
    /** Frames in which paths were submitted, and the lines and segments of the last such frame (read by the client game test). */
    public static volatile int framesDrawn;
    public static volatile int lastLines;
    public static volatile int lastSegments;

    private static final float LINE_WIDTH = 2.0F;
    private static final float DOT_SIZE = 6.0F;
    private static final float END_DOT_SIZE = 10.0F;
    /** A cap on the segments drawn in one frame (a path of 6000 ticks, times many paths, would cost more than it shows). */
    private static final int MAX_SEGMENTS = 30_000;

    private PathRenderer() {
    }

    public static int color(FlightKind kind) {
        return switch (kind) {
            case TNT -> 0xFFFF5050;
            case PROJECTILE -> 0xFF50D8FF;
            case ITEM -> 0xFFFFE050;
            case FALLING_BLOCK -> 0xFFFF9A3C;
            case VEHICLE -> 0xFF70E070;
            case OTHER -> 0xFFFFFFFF;
        };
    }

    /** The colour mixed half-way with white: the continued part. */
    static int lighter(int argb) {
        int r = (argb >> 16 & 0xFF) + (255 - (argb >> 16 & 0xFF)) / 2;
        int g = (argb >> 8 & 0xFF) + (255 - (argb >> 8 & 0xFF)) / 2;
        int b = (argb & 0xFF) + (255 - (argb & 0xFF)) / 2;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** The part of a path beyond the point where the real world freezes it: the colour pulled half-way to a mid grey (and drawn dashed). */
    static int faded(int argb) {
        int r = ((argb >> 16 & 0xFF) + 0x70) / 2;
        int g = ((argb >> 8 & 0xFF) + 0x70) / 2;
        int b = ((argb & 0xFF) + 0x70) / 2;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Colour of the dot and the label where the entity froze in the run itself (a lazy or unloaded chunk). */
    static final int STASIS_COLOR = 0xFF9FE4FF;
    /** Length of a dash (and of the gap after it) of a move while frozen, in blocks. */
    private static final double DASH = 0.25;

    /** Colour of the dot and the label where the real world freezes a path. */
    static final int FREEZE_COLOR = 0xFFFFB030;

    /** Height of the point the line goes through above the entity's feet. */
    public static double lift(FlightKind kind) {
        return switch (kind) {
            case TNT, FALLING_BLOCK -> 0.49;
            case VEHICLE -> 0.3;
            default -> 0.125;
        };
    }

    /** Whether a dot sits at {@code tick}: every {@code interval} ticks counted from {@code offset}. */
    static boolean dotAt(long tick, int interval, int offset) {
        return Math.floorMod(tick - offset, (long) Math.max(1, interval)) == 0;
    }

    /** Client-thread time spent submitting the paths (render() only), summed over {@link #framesDrawn} frames: for the client test's frame-time report. */
    public static volatile long totalNanos;

    static void render() {
        long t0 = System.nanoTime();
        try {
            draw();
        } finally {
            totalNanos += System.nanoTime() - t0;
        }
    }

    private static void draw() {
        if (!PathSettings.showPaths) {
            return;
        }
        PathSnapshot s = PathStore.latest();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (s == null || level == null || !level.dimension().identifier().toString().equals(s.dimension())) {
            return;
        }
        int interval = Math.max(1, PathSettings.dotInterval);
        int offset = PathSettings.dotOffset;
        int[] budget = {MAX_SEGMENTS};
        List<PathSnapshot.Line> lines = s.lines();
        framesDrawn++;
        lastLines = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            PathSnapshot.Line l = lines.get(i);
            int color = color(l.kind());
            double up = lift(l.kind());
            int n = l.points();
            if (n == 0) {
                continue;
            }
            Tails.Job job = Tails.job(s, i);
            boolean continued = job != null && job.points() > 0;
            int freezeAt = l.freeze() == null ? n : l.freeze().point();
            segments(l.xyz(), n, l.ticks(), up, color, interval, offset, budget, true, !continued, freezeAt, l::movedWhileFrozen);
            // frozen in the run itself (session "frozen entities"): a dot where it stopped ticking, labelled with the frozen ticks
            for (PathSnapshot.Stasis st : l.stasis()) {
                Vec3 sp = at(l.xyz(), st.point(), up);
                Gizmos.point(sp, STASIS_COLOR, END_DOT_SIZE);
                if (st.resumed() || st.resume() >= 0) {
                    label("frozen " + st.frozenTicks() + " ticks (" + st.kind() + ")", sp.add(0.0, -0.45, 0.0), STASIS_COLOR);
                }
            }
            Vec3 start = at(l.xyz(), 0, up);
            // where the real world freezes it: from there the path is what the ghost predicts for a ticking chunk
            double endLift = 0.3;
            if (l.freeze() != null) {
                Vec3 fp = at(l.xyz(), freezeAt, up);
                Gizmos.point(fp, FREEZE_COLOR, END_DOT_SIZE);
                // below the line: the end label (above it) is long and would cover this one
                label("real: freezes here (" + l.freeze().kind() + ")", fp.add(0.0, -0.45, 0.0), FREEZE_COLOR);
            }
            if (l.count() > 1) {
                label("×" + l.count(), start.add(0.0, 0.3, 0.0), color);
            }
            Vec3 end = at(l.xyz(), n - 1, up);
            if (job == null) {
                label(PathSnapshot.endText(l), end.add(0.0, endLift, 0.0), color);
            } else {
                // the ghost run stopped here at the tick limit; the rest is the client's own continuation
                Gizmos.point(end, color, END_DOT_SIZE);
                label(PathSnapshot.endText(l), end.add(0.0, endLift, 0.0), color);
                int m = job.points();
                if (m > 0) {
                    long[] ticks = job.ticks.toLongArray();
                    double[] xyz = new double[m * 3 + 3];
                    xyz[0] = l.xyz()[3 * n - 3];
                    xyz[1] = l.xyz()[3 * n - 2];
                    xyz[2] = l.xyz()[3 * n - 1];
                    System.arraycopy(job.xyz.elements(), 0, xyz, 3, m * 3);
                    long[] all = new long[m + 1];
                    all[0] = l.end().tick();
                    System.arraycopy(ticks, 0, all, 1, m);
                    int light = lighter(color);
                    segments(xyz, m + 1, all, up, light, interval, offset, budget, false, true, m + 1, k -> false);
                    Vec3 last = at(xyz, m, up);
                    String why = job.done() ? job.endReason() + " t+" + all[m] : "continuing... t+" + all[m];
                    label("continued: " + why, last.add(0.0, 0.3, 0.0), light);
                }
            }
        }
        lastSegments = MAX_SEGMENTS - budget[0];
    }

    /**
     * A label that stays readable: gizmo text has a fixed size in the world, tiny at 35 blocks (seen in a capture), so it grows with the
     * distance from 12 blocks (its normal size) up to 3 times that at 36 blocks and beyond.
     */
    public static void label(String text, Vec3 pos, int color) {
        double dist = Minecraft.getInstance().gameRenderer.mainCamera().position().distanceTo(pos);
        float scale = (float) Math.max(1.0, Math.min(3.0, dist / 12.0));
        Gizmos.billboardText(text, pos, TextGizmo.Style.forColorAndCentered(color).withScale(scale));
    }

    private static Vec3 at(double[] xyz, int i, double up) {
        return new Vec3(xyz[3 * i], xyz[3 * i + 1] + up, xyz[3 * i + 2]);
    }

    /**
     * A polyline through the points, and dots at the ticks that match the interval; the first point (when
     * {@code startDot}) and the last (when {@code endDot}) always get one. From point {@code freezeAt} on (the real world freezes the
     * entity there, a rerun's prediction over chunks that do not tick) the line is dashed (two segments on, two off) and faded.
     */
    private static void segments(double[] xyz, int n, long[] ticks, double up, int color, int interval, int offset, int[] budget, boolean startDot, boolean endDot, int freezeAt,
                                 java.util.function.IntPredicate movedWhileFrozen) {
        Vec3 prev = at(xyz, 0, up);
        if (startDot) {
            Gizmos.point(prev, color, END_DOT_SIZE);
        }
        for (int i = 1; i < n && budget[0] > 0; i++) {
            Vec3 cur = at(xyz, i, up);
            boolean beyond = i >= freezeAt;
            int c = beyond ? faded(color) : color;
            if (cur.distanceToSqr(prev) > 1.0E-8) {
                if (movedWhileFrozen.test(i)) {
                    dashed(prev, cur, STASIS_COLOR, budget); // moved while frozen (a piston push): not a flight
                } else if (!beyond || ((i - freezeAt) / 2) % 2 == 0) {
                    Gizmos.line(prev, cur, c, LINE_WIDTH);
                    budget[0]--;
                }
                prev = cur;
            }
            boolean last = i == n - 1;
            if (last && endDot) {
                Gizmos.point(cur, c, END_DOT_SIZE);
            } else if (dotAt(ticks[i], interval, offset)) {
                Gizmos.point(cur, c, DOT_SIZE);
            }
        }
    }

    /** A dashed straight line from {@code a} to {@code b}: dashes of {@link #DASH} blocks with gaps of the same length (at least one dash). */
    private static void dashed(Vec3 a, Vec3 b, int color, int[] budget) {
        double len = a.distanceTo(b);
        int pieces = Math.max(1, (int) Math.floor(len / (2 * DASH)));
        Vec3 step = b.subtract(a).scale(1.0 / (pieces * 2.0));
        for (int k = 0; k < pieces && budget[0] > 0; k++) {
            Vec3 from = a.add(step.scale(2.0 * k));
            Gizmos.line(from, from.add(step), color, LINE_WIDTH);
            budget[0]--;
        }
    }
}
