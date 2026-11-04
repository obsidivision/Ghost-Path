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

    static int color(FlightKind kind) {
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

    /** Height of the point the line goes through above the entity's feet. */
    static double lift(FlightKind kind) {
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

    static void render() {
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
            segments(l.xyz(), n, l.ticks(), up, color, interval, offset, budget, true, !continued);
            Vec3 start = at(l.xyz(), 0, up);
            if (l.count() > 1) {
                Gizmos.billboardText("×" + l.count(), start.add(0.0, 0.3, 0.0), TextGizmo.Style.forColorAndCentered(color));
            }
            Vec3 end = at(l.xyz(), n - 1, up);
            if (job == null) {
                Gizmos.billboardText(l.end().reason() + " t+" + l.end().tick(), end.add(0.0, 0.3, 0.0), TextGizmo.Style.forColorAndCentered(color));
            } else {
                // the ghost run stopped here at the tick limit; the rest is the client's own continuation
                Gizmos.point(end, color, END_DOT_SIZE);
                Gizmos.billboardText(l.end().reason() + " t+" + l.end().tick(), end.add(0.0, 0.3, 0.0), TextGizmo.Style.forColorAndCentered(color));
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
                    segments(xyz, m + 1, all, up, light, interval, offset, budget, false, true);
                    Vec3 last = at(xyz, m, up);
                    String why = job.done() ? job.endReason() + " t+" + all[m] : "continuing... t+" + all[m];
                    Gizmos.billboardText("continued: " + why, last.add(0.0, 0.3, 0.0), TextGizmo.Style.forColorAndCentered(light));
                }
            }
        }
        lastSegments = MAX_SEGMENTS - budget[0];
    }

    private static Vec3 at(double[] xyz, int i, double up) {
        return new Vec3(xyz[3 * i], xyz[3 * i + 1] + up, xyz[3 * i + 2]);
    }

    /**
     * A polyline through the points, and dots at the ticks that match the interval; the first point (when
     * {@code startDot}) and the last (when {@code endDot}) always get one.
     */
    private static void segments(double[] xyz, int n, long[] ticks, double up, int color, int interval, int offset, int[] budget, boolean startDot, boolean endDot) {
        Vec3 prev = at(xyz, 0, up);
        if (startDot) {
            Gizmos.point(prev, color, END_DOT_SIZE);
        }
        for (int i = 1; i < n && budget[0] > 0; i++) {
            Vec3 cur = at(xyz, i, up);
            if (cur.distanceToSqr(prev) > 1.0E-8) {
                Gizmos.line(prev, cur, color, LINE_WIDTH);
                budget[0]--;
                prev = cur;
            }
            boolean last = i == n - 1;
            if (last && endDot) {
                Gizmos.point(cur, color, END_DOT_SIZE);
            } else if (dotAt(ticks[i], interval, offset)) {
                Gizmos.point(cur, color, DOT_SIZE);
            }
        }
    }
}
