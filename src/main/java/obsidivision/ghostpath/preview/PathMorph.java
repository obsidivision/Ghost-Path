package obsidivision.ghostpath.preview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;

/**
 * The animation from the old prediction to the new one (session D, item 4), as pure data: no world, no rendering, nothing written into a
 * snapshot. {@link #at} gives the geometry to draw at a progress from 0 to 1: 0 is the old frame itself, 1 is the new snapshot's own data
 * (the same arrays, so bit for bit), in between a {@link Frame} of fresh arrays. Hover, pins, the inspector and every test read the
 * snapshot, never this.
 *
 * <p>Paths are matched by (entity type, chain entity index). For a matched pair, each tick that both have is interpolated; ticks only the
 * new path has draw on from the end of the common part (and from its start, if the new path starts earlier), ticks only the old path has
 * retract. An old path without a match fades out, a new one fades in and draws on from its start. Labels fade from the old texts to the
 * new ones. A morph that starts while another one runs takes the drawn frame of that one as its "old" ({@link #at} of the running morph),
 * so nothing jumps.
 */
public final class PathMorph {
    /** More paths than this (old or new): no animation. */
    public static final int MAX_PATHS = 500;

    /** A label source with its own alpha (0..1): the text comes from the line ({@link PathSnapshot#endText}, the name, the count). */
    public record Lab(PathSnapshot.Line line, double alpha) {
    }

    /**
     * One drawn path: its identity ({@code type}, chain entity {@code index}), the polyline ({@code xyz} three doubles per tick in {@code ticks}),
     * its alpha and the labels that fade in it (at the start and end of the drawn polyline).
     */
    public record MLine(String type, int index, FlightKind kind, int count, long[] ticks, double[] xyz, double alpha, List<Lab> labels) {
        public int points() {
            return this.ticks.length;
        }
    }

    public record Frame(List<MLine> lines) {
        /** A snapshot as it is: alpha 1, the snapshot's own arrays (shared, never written). */
        public static Frame of(PathSnapshot s) {
            List<MLine> out = new ArrayList<>(s.lines().size());
            for (PathSnapshot.Line l : s.lines()) {
                out.add(new MLine(l.type(), l.members().length == 0 ? -1 : l.members()[0], l.kind(), l.count(), l.ticks(), l.xyz(), 1.0, List.of(new Lab(l, 1.0))));
            }
            return new Frame(List.copyOf(out));
        }

        public int paths() {
            return this.lines.size();
        }

        /** Segments a frame draws (one per point after the first). */
        public int segments() {
            int n = 0;
            for (MLine l : this.lines) {
                n += Math.max(0, l.points() - 1);
            }
            return n;
        }
    }

    private final Frame from;
    private final Frame to;

    public PathMorph(Frame from, PathSnapshot to) {
        this.from = from;
        this.to = Frame.of(to);
    }

    public Frame from() {
        return this.from;
    }

    public Frame target() {
        return this.to;
    }

    /**
     * Whether to animate at all: not when either side has more than {@link #MAX_PATHS} paths, or when both together would draw more than
     * {@code segmentCap} segments (the renderer's cap would cut them).
     */
    public static boolean shouldAnimate(Frame from, PathSnapshot to, int segmentCap) {
        Frame t = Frame.of(to);
        return from.paths() <= MAX_PATHS && t.paths() <= MAX_PATHS && (long) from.segments() + t.segments() <= segmentCap;
    }

    /** Ease-in-out (cubic): 0 at 0, 1 at 1 exactly, monotonic. */
    public static double ease(double p) {
        if (p <= 0.0) {
            return 0.0;
        }
        if (p >= 1.0) {
            return 1.0;
        }
        return p < 0.5 ? 4.0 * p * p * p : 1.0 - Math.pow(-2.0 * p + 2.0, 3.0) / 2.0;
    }

    /** The geometry and alphas to draw at {@code progress} (clamped to 0..1; the easing is applied here). */
    public Frame at(double progress) {
        double e = ease(progress);
        if (e <= 0.0) {
            return this.from;
        }
        if (e >= 1.0) {
            return this.to;
        }
        Map<String, MLine> target = new HashMap<>();
        for (MLine l : this.to.lines()) {
            target.put(key(l), l);
        }
        List<MLine> out = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (MLine a : this.from.lines()) {
            MLine b = target.get(key(a));
            if (b != null && overlaps(a, b)) {
                used.add(key(a));
                out.add(pair(a, b, e));
            } else {
                double alpha = a.alpha() * (1.0 - e);
                out.add(new MLine(a.type(), a.index(), a.kind(), a.count(), a.ticks(), a.xyz(), alpha, scaled(a.labels(), 1.0 - e)));
            }
        }
        for (MLine b : this.to.lines()) {
            if (!used.contains(key(b))) {
                out.add(drawOn(b, e));
            }
        }
        return new Frame(List.copyOf(out));
    }

    private static String key(MLine l) {
        return l.type() + "#" + l.index();
    }

    private static boolean overlaps(MLine a, MLine b) {
        return a.points() > 0 && b.points() > 0 && Math.max(a.ticks()[0], b.ticks()[0]) <= Math.min(a.ticks()[a.points() - 1], b.ticks()[b.points() - 1]);
    }

    private static List<Lab> scaled(List<Lab> labs, double f) {
        List<Lab> out = new ArrayList<>(labs.size());
        for (Lab l : labs) {
            out.add(new Lab(l.line(), l.alpha() * f));
        }
        return out;
    }

    private static double lerp(double a, double b, double e) {
        return a + (b - a) * e;
    }

    /** New path without an old one: its points from the start, the visible length growing with {@code e}; fades in. */
    private static MLine drawOn(MLine b, double e) {
        int m = b.points();
        double f = e * (m - 1);
        int k = (int) Math.floor(f);
        int count = Math.min(m, k + 1) + (k + 1 < m && f - k > 0.0 ? 1 : 0);
        long[] ticks = Arrays.copyOf(b.ticks(), count);
        double[] xyz = Arrays.copyOf(b.xyz(), count * 3);
        if (count > k + 1) { // the partial point: between point k and point k + 1
            for (int c = 0; c < 3; c++) {
                xyz[3 * k + 3 + c] = lerp(b.xyz()[3 * k + c], b.xyz()[3 * k + 3 + c], f - k);
            }
        }
        return new MLine(b.type(), b.index(), b.kind(), b.count(), ticks, xyz, b.alpha() * e, scaled(b.labels(), e));
    }

    /** A matched pair at eased progress {@code e}, 0 < e < 1. */
    private static MLine pair(MLine a, MLine b, double e) {
        int na = a.points();
        int nb = b.points();
        long lo = Math.max(a.ticks()[0], b.ticks()[0]);
        long hi = Math.min(a.ticks()[na - 1], b.ticks()[nb - 1]);
        // walk both tick lists in order; points before lo and after hi are "extras" of one of them
        List<double[]> head = new ArrayList<>(); // extras before lo, nearest first
        List<Long> headT = new ArrayList<>();
        boolean headIsNew = false;
        List<double[]> mid = new ArrayList<>();
        List<Long> midT = new ArrayList<>();
        List<double[]> tail = new ArrayList<>(); // extras after hi, nearest first
        List<Long> tailT = new ArrayList<>();
        boolean tailIsNew = false;
        int i = 0;
        int j = 0;
        while (i < na || j < nb) {
            long ta = i < na ? a.ticks()[i] : Long.MAX_VALUE;
            long tb = j < nb ? b.ticks()[j] : Long.MAX_VALUE;
            long t = Math.min(ta, tb);
            double[] pa = ta == t ? new double[] {a.xyz()[3 * i], a.xyz()[3 * i + 1], a.xyz()[3 * i + 2]} : null;
            double[] pb = tb == t ? new double[] {b.xyz()[3 * j], b.xyz()[3 * j + 1], b.xyz()[3 * j + 2]} : null;
            if (ta == t) {
                i++;
            }
            if (tb == t) {
                j++;
            }
            if (t < lo) {
                head.add(pa != null ? pa : pb);
                headT.add(t);
                headIsNew = pa == null;
            } else if (t > hi) {
                tail.add(pa != null ? pa : pb);
                tailT.add(t);
                tailIsNew = pa == null;
            } else {
                double[] p = pa != null && pb != null ? new double[] {lerp(pa[0], pb[0], e), lerp(pa[1], pb[1], e), lerp(pa[2], pb[2], e)} : pa != null ? pa : pb;
                mid.add(p);
                midT.add(t);
            }
        }
        // tail (nearest first) grows or shrinks; head (nearest to lo first = highest tick first) the same
        java.util.Collections.reverse(head);
        java.util.Collections.reverse(headT);
        List<double[]> headOut = extras(head, mid.getFirst(), headIsNew ? e : 1.0 - e);
        List<double[]> tailOut = extras(tail, mid.getLast(), tailIsNew ? e : 1.0 - e);
        int total = headOut.size() + mid.size() + tailOut.size();
        long[] ticks = new long[total];
        double[] xyz = new double[total * 3];
        int n = 0;
        for (int k = headOut.size() - 1; k >= 0; k--) { // back to ascending ticks
            ticks[n] = headT.get(k);
            System.arraycopy(headOut.get(k), 0, xyz, 3 * n, 3);
            n++;
        }
        for (int k = 0; k < mid.size(); k++) {
            ticks[n] = midT.get(k);
            System.arraycopy(mid.get(k), 0, xyz, 3 * n, 3);
            n++;
        }
        for (int k = 0; k < tailOut.size(); k++) {
            ticks[n] = tailT.get(k);
            System.arraycopy(tailOut.get(k), 0, xyz, 3 * n, 3);
            n++;
        }
        List<Lab> labels = new ArrayList<>(scaled(a.labels(), 1.0 - e));
        labels.addAll(scaled(b.labels(), e));
        return new MLine(b.type(), b.index(), b.kind(), b.count(), ticks, xyz, lerp(a.alpha(), b.alpha(), e), List.copyOf(labels));
    }

    /**
     * The first {@code f * m} of {@code m} extra points (ordered nearest to the common part first), the last one partial: between the point
     * before it (or {@code anchor}) and itself.
     */
    private static List<double[]> extras(List<double[]> pts, double[] anchor, double f) {
        int m = pts.size();
        List<double[]> out = new ArrayList<>();
        double len = f * m;
        int k = (int) Math.floor(len);
        for (int q = 0; q < Math.min(k, m); q++) {
            out.add(pts.get(q));
        }
        if (k < m && len - k > 0.0) {
            double[] prev = k == 0 ? anchor : pts.get(k - 1);
            double[] nx = pts.get(k);
            double r = len - k;
            out.add(new double[] {lerp(prev[0], nx[0], r), lerp(prev[1], nx[1], r), lerp(prev[2], nx[2], r)});
        }
        return out;
    }
}
