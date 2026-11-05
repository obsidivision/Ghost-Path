package obsidivision.ghostpath.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.style.Style;
import obsidivision.ghostpath.preview.MorphTracker;
import obsidivision.ghostpath.preview.PathMorph;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;
import org.jspecify.annotations.Nullable;

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

    private static final MorphTracker MORPH = new MorphTracker();
    /** The frame drawn by the last animated draw (null: the snapshot itself), for the client test. */
    public static volatile PathMorph.@Nullable Frame lastFrame;
    public static volatile boolean lastMorphing;
    /** Shown by the HUD when the segment cap cut paths while saved paths are drawn. */
    public static volatile String capNote = "";

    /** A cap on the segments drawn in one frame (a path of 6000 ticks, times many paths, would cost more than it shows). */
    private static final int MAX_SEGMENTS = 30_000;

    private PathRenderer() {
    }

    public static int color(FlightKind kind) {
        return Style.current().color(kind);
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
    static final int STASIS_COLOR = Style.STASIS_COLOR;
    /** Length of a dash (and of the gap after it) of a move while frozen, in blocks. */
    private static final double DASH = 0.25;

    /** Colour of the dot and the label where the real world freezes a path. */
    static final int FREEZE_COLOR = Style.FREEZE_COLOR;

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
        Labels.begin();
        capNote = "";
        if (!PathSettings.showPaths) {
            return;
        }
        PathSnapshot s = PathStore.latest();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        String dim = level.dimension().identifier().toString();
        cam = mc.gameRenderer.mainCamera().position();
        int interval = Math.max(1, PathSettings.dotInterval);
        int offset = PathSettings.dotOffset;
        int[] budget = {MAX_SEGMENTS};
        framesDrawn++;
        lastLines = 0;
        // the animation is a view: it only decides what is drawn; the snapshot (hover, pins, inspector) is never touched
        PathMorph.Frame morphed = MORPH.frame(s, System.nanoTime(), PathSettings.animationSeconds(), MAX_SEGMENTS);
        lastMorphing = morphed != null;
        lastFrame = morphed;
        if (s != null && dim.equals(s.dimension())) {
            List<PathSnapshot.Line> lines = s.lines();
            lastLines = lines.size();
            if (morphed != null) {
                for (PathMorph.MLine ml : morphed.lines()) {
                    drawMorphed(ml, interval, offset, budget);
                }
            } else {
                for (int i = 0; i < lines.size(); i++) {
                    Tails.Job job = Tails.job(s, i);
                    drawLine(lines.get(i), job == null ? null : job.copyOut(), null, interval, offset, budget);
                }
            }
        }
        // saved paths (session D): lighter, with their name on the start and end labels; only in their own dimension
        for (obsidivision.ghostpath.saved.SavedPath sp : obsidivision.ghostpath.saved.SavedStore.inDimension(obsidivision.ghostpath.saved.SavedStore.visible(), dim)) {
            for (int i = 0; i < sp.lines().size(); i++) {
                drawLine(sp.lines().get(i), sp.continuations().get(i), sp.name(), interval, offset, budget);
            }
        }
        lastSegments = MAX_SEGMENTS - budget[0];
        capNote = budget[0] <= 0 && !obsidivision.ghostpath.saved.SavedStore.inDimension(obsidivision.ghostpath.saved.SavedStore.visible(), dim).isEmpty()
            ? "Segment cap (" + MAX_SEGMENTS + ") reached: some paths are cut, saved ones first" : "";
    }

    /** One path while a morph runs: its polyline (dots at the interval, the end dot at the last drawn point) and its labels, each with its alpha. */
    private static void drawMorphed(PathMorph.MLine l, int interval, int offset, int[] budget) {
        int n = l.points();
        if (n == 0 || l.alpha() <= 0.01) {
            return;
        }
        double up = lift(l.kind());
        int base = color(l.kind());
        segments(l.xyz(), n, l.ticks(), up, withAlpha(base, alphaByte(l.alpha())), interval, offset, budget, true, true, n, k -> false);
        Vec3 start = at(l.xyz(), 0, up);
        Vec3 end = at(l.xyz(), n - 1, up);
        for (PathMorph.Lab lab : l.labels()) {
            if (lab.alpha() <= 0.05) {
                continue;
            }
            int c = withAlpha(base, alphaByte(lab.alpha() * l.alpha()));
            if (lab.line().count() > 1) {
                label("×" + lab.line().count(), start.add(0.0, 0.3, 0.0), c);
            }
            endLabel(PathSnapshot.endText(lab.line()), end.add(0.0, 0.3, 0.0), c, lab.line().count());
        }
    }

    static int alphaByte(double a) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, a)) * 255.0);
    }

    /** Alpha of a saved path's colours (they are drawn lighter than the live prediction). */

    static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | alpha << 24;
    }

    /**
     * One path. {@code cont}: its continuation points (the live client's, or the stored ones of a saved path), or null; {@code savedName}:
     * null for the live prediction, else the name of the saved path (lighter colours, the name on the start and end label).
     */
    private static void drawLine(PathSnapshot.Line l, obsidivision.ghostpath.saved.SavedPath.@Nullable Cont cont, @Nullable String savedName, int interval, int offset, int[] budget) {
        boolean saved = savedName != null;
        String tag = saved ? "[" + savedName + "] " : "";
        int color = saved ? withAlpha(lighter(color(l.kind())), Style.current().savedAlpha()) : color(l.kind());
        double up = lift(l.kind());
        int n = l.points();
        if (n == 0) {
            return;
        }
        boolean continued = cont != null && cont.points() > 0;
        int freezeAt = l.freeze() == null ? n : l.freeze().point();
        segments(l.xyz(), n, l.ticks(), up, color, interval, offset, budget, true, !continued, freezeAt, l::movedWhileFrozen);
        for (PathSnapshot.Stasis st : l.stasis()) {
            Vec3 sp = at(l.xyz(), st.point(), up);
            dot(sp, STASIS_COLOR, Style.current().endDotSize());
            if (st.resumed() || st.resume() >= 0) {
                label(tag + "frozen " + st.frozenTicks() + " ticks (" + st.kind() + ")", sp.add(0.0, -0.45, 0.0), STASIS_COLOR);
            }
        }
        Vec3 start = at(l.xyz(), 0, up);
        double endLift = 0.3;
        if (l.freeze() != null) {
            Vec3 fp = at(l.xyz(), freezeAt, up);
            dot(fp, FREEZE_COLOR, Style.current().endDotSize());
            label(tag + "real: freezes here (" + l.freeze().kind() + ")", fp.add(0.0, -0.45, 0.0), FREEZE_COLOR);
        }
        if (l.count() > 1 || saved) {
            label(tag.trim() + (l.count() > 1 ? (saved ? " " : "") + "×" + l.count() : ""), start.add(0.0, 0.3, 0.0), color);
        }
        Vec3 end = at(l.xyz(), n - 1, up);
        if (cont == null) {
            endLabel(tag + PathSnapshot.endText(l), end.add(0.0, endLift, 0.0), color, l.count());
            return;
        }
        // the run stopped here at the tick limit; the rest is the continuation
        dot(end, color, Style.current().endDotSize());
        endLabel(tag + PathSnapshot.endText(l), end.add(0.0, endLift, 0.0), color, l.count());
        int m = cont.points();
        if (m > 0) {
            double[] xyz = new double[m * 3 + 3];
            xyz[0] = l.xyz()[3 * n - 3];
            xyz[1] = l.xyz()[3 * n - 2];
            xyz[2] = l.xyz()[3 * n - 1];
            System.arraycopy(cont.xyz(), 0, xyz, 3, m * 3);
            long[] all = new long[m + 1];
            all[0] = l.end().tick();
            System.arraycopy(cont.ticks(), 0, all, 1, m);
            int light = saved ? color : lighter(color);
            segments(xyz, m + 1, all, up, light, interval, offset, budget, false, true, m + 1, k -> false);
            Vec3 last = at(xyz, m, up);
            String why = cont.done() ? cont.endReason() + " t+" + all[m] : "continuing... t+" + all[m];
            endLabel(tag + "continued: " + why, last.add(0.0, 0.3, 0.0), light);
        }
    }

    /** A label for the HUD pass ({@link Labels}): a dark pill with the text, a marker in {@code color}; declutter, distance fade and scale happen there. */
    public static void label(String text, Vec3 pos, int color) {
        Labels.add(text, pos, color, color);
    }

    /** The end label of a path: the marker shows the end reason (exploded, hit, stuck, frozen, limit, continued). */
    static void endLabel(String text, Vec3 pos, int color) {
        endLabel(text, pos, color, 1);
    }

    /** {@code weight}: how many entities the label stands for; labels with the same text at one place are drawn as one with the total ({@code Labels}). */
    static void endLabel(String text, Vec3 pos, int color, int weight) {
        Labels.add(text, pos, color, Style.current().marker(text.substring(text.indexOf(']') + 1).trim()), weight);
    }

    private static Vec3 cam = Vec3.ZERO;

    /** A dot whose size grows a little with distance, with a thin outline in the styles that have one. */
    private static void dot(Vec3 p, int color, float base) {
        Style st = Style.current();
        float size = st.dotSize(base, cam.distanceTo(p));
        if (st.outlineExtra() > 0.0F) {
            Gizmos.point(p, st.outlineColor(), size + st.outlineExtra());
        }
        Gizmos.point(p, color, size);
    }

    /** A line segment that fades towards the far end (distance from the camera). */
    private static void seg(Vec3 a, Vec3 b, int color) {
        Style st = Style.current();
        double al = st.lineAlpha(cam.distanceTo(a.add(b).scale(0.5)));
        Gizmos.line(a, b, al < 1.0 ? Style.withAlpha(color, al) : color, st.lineWidth());
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
            dot(prev, color, Style.current().endDotSize());
        }
        for (int i = 1; i < n && budget[0] > 0; i++) {
            Vec3 cur = at(xyz, i, up);
            boolean beyond = i >= freezeAt;
            int c = beyond ? faded(color) : color;
            if (cur.distanceToSqr(prev) > 1.0E-8) {
                if (movedWhileFrozen.test(i)) {
                    dashed(prev, cur, STASIS_COLOR, budget); // moved while frozen (a piston push): not a flight
                } else if (!beyond || ((i - freezeAt) / 2) % 2 == 0) {
                    seg(prev, cur, c);
                    budget[0]--;
                }
                prev = cur;
            }
            boolean last = i == n - 1;
            if (last && endDot) {
                dot(cur, c, Style.current().endDotSize());
            } else if (dotAt(ticks[i], interval, offset)) {
                dot(cur, c, Style.current().dotSize());
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
            seg(from, from.add(step), color);
            budget[0]--;
        }
    }
}
