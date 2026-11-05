package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import obsidivision.ghostpath.preview.MorphTracker;
import obsidivision.ghostpath.preview.PathMorph;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;

/** Session D, item 4: the morph as pure data (no rendering, no world). */
public class PathMorphTest {
    /** A line of {@code n} points from tick {@code t0}: x = x0 + dx * i, y = y0 - i * i * 0.01, z = i. */
    static PathSnapshot.Line line(String type, int index, long t0, int n, double x0, double dx, double y0) {
        long[] ticks = new long[n];
        double[] xyz = new double[3 * n];
        for (int i = 0; i < n; i++) {
            ticks[i] = t0 + i;
            xyz[3 * i] = x0 + dx * i;
            xyz[3 * i + 1] = y0 - i * i * 0.01;
            xyz[3 * i + 2] = i;
        }
        return new PathSnapshot.Line(type, FlightKind.TNT, 1, t0, ticks, xyz, new double[3 * n], new double[6 * n], new int[n], new int[] {index},
            new PathSnapshot.End("exploded", t0 + n - 1, xyz[3 * n - 3], xyz[3 * n - 2], xyz[3 * n - 1]), null, null);
    }

    static PathSnapshot snap(long serial, String source, String dim, int x, PathSnapshot.Line... lines) {
        return new PathSnapshot(serial, source, dim, 100, false, List.of(lines)).withOrigin(new PathSnapshot.Origin(x, 64, 0, 0L));
    }

    private static boolean same(PathMorph.MLine a, PathMorph.MLine b) {
        return Arrays.equals(a.ticks(), b.ticks()) && Arrays.equals(a.xyz(), b.xyz()) && a.alpha() == b.alpha();
    }

    @GameTest
    public void progressZeroIsOldAndOneIsNewBitForBit(GameTestHelper helper) {
        PathSnapshot o = snap(1, "recorded", "d", 0, line("minecraft:tnt", 0, 5, 12, 0.1, 0.3, 70), line("minecraft:tnt", 1, 5, 6, 3.0, -0.2, 70));
        PathSnapshot n = snap(2, "rerun", "d", 0, line("minecraft:tnt", 0, 5, 20, -0.0, 0.31, 70.5), line("minecraft:tnt", 7, 9, 4, 1.0, 1.0, 66));
        PathMorph m = new PathMorph(PathMorph.Frame.of(o), n);
        PathMorph.Frame f0 = m.at(0.0);
        PathMorph.Frame f1 = m.at(1.0);
        helper.assertTrue(f0.lines().size() == 2 && same(f0.lines().get(0), PathMorph.Frame.of(o).lines().get(0)) && same(f0.lines().get(1), PathMorph.Frame.of(o).lines().get(1)), "progress 0 is not the old geometry");
        helper.assertTrue(f1.lines().size() == 2, "progress 1: lines");
        for (int i = 0; i < 2; i++) {
            PathSnapshot.Line want = n.lines().get(i);
            PathMorph.MLine got = f1.lines().get(i);
            boolean bits = got.xyz().length == want.xyz().length;
            for (int k = 0; bits && k < want.xyz().length; k++) {
                bits = Double.doubleToRawLongBits(got.xyz()[k]) == Double.doubleToRawLongBits(want.xyz()[k]);
            }
            helper.assertTrue(bits && Arrays.equals(got.ticks(), want.ticks()) && got.alpha() == 1.0, "progress 1 differs from the new line " + i);
        }
        helper.succeed();
    }

    @GameTest
    public void matchedPathsInterpolateMonotonically(GameTestHelper helper) {
        PathSnapshot o = snap(1, "recorded", "d", 0, line("minecraft:tnt", 0, 5, 12, 0.0, 0.3, 70));
        PathSnapshot n = snap(2, "rerun", "d", 0, line("minecraft:tnt", 0, 5, 12, 10.0, 0.5, 60));
        PathMorph m = new PathMorph(PathMorph.Frame.of(o), n);
        double[] last = {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        for (int s = 1; s < 100; s++) {
            PathMorph.MLine l = m.at(s / 100.0).lines().getFirst();
            helper.assertTrue(l.points() == 12 && l.alpha() == 1.0, "points or alpha changed for a matched path of the same length");
            double x = l.xyz()[3 * 6]; // point 6: x from 1.8 to 13.0, y from 69.64 down to 59.64
            double y = l.xyz()[3 * 6 + 1];
            helper.assertTrue(x >= last[0] && y <= last[1], "not monotonic at " + s + "/100");
            last[0] = x;
            last[1] = y;
            helper.assertTrue(x > 1.8 - 1e-9 && x < 13.0 + 1e-9, "outside the old and new");
        }
        helper.assertTrue(PathMorph.ease(0) == 0.0 && PathMorph.ease(1) == 1.0 && PathMorph.ease(0.5) == 0.5, "ease endpoints");
        double prev = 0;
        for (int i = 1; i <= 1000; i++) {
            double e = PathMorph.ease(i / 1000.0);
            helper.assertTrue(e >= prev, "ease not monotonic");
            prev = e;
        }
        helper.succeed();
    }

    @GameTest
    public void unmatchedFadeOutAndFadeIn(GameTestHelper helper) {
        PathSnapshot o = snap(1, "recorded", "d", 0, line("minecraft:tnt", 0, 5, 10, 0.0, 0.3, 70), line("minecraft:tnt", 1, 5, 10, 4.0, 0.3, 70));
        PathSnapshot n = snap(2, "rerun", "d", 0, line("minecraft:tnt", 0, 5, 10, 0.0, 0.3, 70), line("minecraft:tnt", 5, 8, 10, 2.0, 0.3, 70), line("minecraft:arrow", 1, 8, 10, 2.0, 0.3, 70));
        PathMorph m = new PathMorph(PathMorph.Frame.of(o), n);
        double lastOut = 1.1;
        double lastIn = -0.1;
        int lastPoints = 0;
        for (int s = 1; s < 50; s++) {
            PathMorph.Frame f = m.at(s / 50.0);
            PathMorph.MLine out = f.lines().get(1); // old index 1: no match (the new one has index 5, and the arrow has the same index but another type)
            PathMorph.MLine in = f.lines().get(2);
            helper.assertTrue(out.index() == 1 && out.type().equals("minecraft:tnt") && out.points() == 10 && out.alpha() < lastOut && out.alpha() > 0.0, "old unmatched does not fade out at " + s);
            helper.assertTrue(in.index() == 5 && in.alpha() > lastIn && in.points() >= lastPoints && in.points() <= 10, "new unmatched does not fade in / draw on at " + s);
            helper.assertTrue(f.lines().get(3).type().equals("minecraft:arrow"), "the arrow with the same index as an old TNT was matched to it");
            lastOut = out.alpha();
            lastIn = in.alpha();
            lastPoints = in.points();
            // labels follow: the new one's label alpha equals its line alpha, the old one's the old line's
            helper.assertTrue(Math.abs(in.labels().getFirst().alpha() - in.alpha()) < 1e-12, "label alpha");
        }
        PathMorph.MLine early = m.at(0.1).lines().get(2);
        helper.assertTrue(early.points() >= 1 && early.points() < 10, "a new path draws on from its start");
        helper.succeed();
    }

    @GameTest
    public void pathsThatChangeLengthExtendAndRetract(GameTestHelper helper) {
        PathSnapshot shortOne = snap(1, "recorded", "d", 0, line("minecraft:tnt", 0, 5, 6, 0.0, 0.3, 70));
        PathSnapshot longOne = snap(2, "rerun", "d", 0, line("minecraft:tnt", 0, 5, 16, 0.0, 0.3, 70));
        PathMorph grow = new PathMorph(PathMorph.Frame.of(shortOne), longOne);
        int last = 6;
        for (int s = 1; s < 100; s++) {
            PathMorph.MLine l = grow.at(s / 100.0).lines().getFirst();
            helper.assertTrue(l.points() >= last && l.points() <= 16, "growing path shrinks at " + s);
            helper.assertTrue(l.ticks()[0] == 5 && l.ticks()[l.points() - 1] >= 10, "ticks of a growing path");
            // the common part equals the new one's (same x positions here), the extra part lies on the new line
            last = l.points();
        }
        helper.assertTrue(grow.at(0.6).lines().getFirst().points() > 6 && grow.at(0.6).lines().getFirst().points() < 16, "in between");
        PathMorph shrink = new PathMorph(PathMorph.Frame.of(longOne), shortOne);
        last = 16;
        for (int s = 1; s < 100; s++) {
            PathMorph.MLine l = shrink.at(s / 100.0).lines().getFirst();
            helper.assertTrue(l.points() <= last && l.points() >= 6, "retracting path grows at " + s);
            last = l.points();
        }
        helper.assertTrue(shrink.at(0.99).lines().getFirst().points() <= 7, "the cut-off part did not retract");
        // a path that starts earlier and one that starts later
        PathSnapshot early = snap(1, "recorded", "d", 0, line("minecraft:tnt", 0, 3, 10, 0.0, 0.3, 70));
        PathSnapshot late = snap(2, "rerun", "d", 0, line("minecraft:tnt", 0, 6, 10, 0.0, 0.3, 70));
        PathMorph shift = new PathMorph(PathMorph.Frame.of(early), late);
        for (int s = 1; s < 100; s++) {
            PathMorph.MLine l = shift.at(s / 100.0).lines().getFirst();
            for (int i = 1; i < l.points(); i++) {
                helper.assertTrue(l.ticks()[i] > l.ticks()[i - 1], "ticks not ascending at " + s);
            }
        }
        // no common tick: the old one fades, the new one comes in
        PathSnapshot far = snap(2, "rerun", "d", 0, line("minecraft:tnt", 0, 50, 5, 0.0, 0.3, 70));
        PathMorph apart = new PathMorph(PathMorph.Frame.of(early), far);
        helper.assertTrue(apart.at(0.5).lines().size() == 2, "disjoint ticks were matched");
        helper.succeed();
    }

    @GameTest
    public void aSecondMorphStartsFromWhatIsDrawn(GameTestHelper helper) {
        PathSnapshot s0 = snap(1, "recorded", "d", 0, line("minecraft:tnt", 0, 5, 12, 0.0, 0.3, 70), line("minecraft:tnt", 1, 5, 12, 4.0, 0.3, 70));
        PathSnapshot s1 = snap(2, "rerun", "d", 0, line("minecraft:tnt", 0, 5, 12, 8.0, 0.1, 50));
        PathSnapshot s2 = snap(3, "rerun", "d", 0, line("minecraft:tnt", 0, 5, 14, -5.0, 0.7, 90), line("minecraft:tnt", 3, 5, 5, 0.0, 0.1, 70));
        MorphTracker t = new MorphTracker();
        long sec = 1_000_000_000L;
        helper.assertTrue(t.frame(s0, 0, 0.4, 30000) == null && !t.active(), "the first snapshot is shown as it is");
        PathMorph.Frame f = t.frame(s1, 10 * sec, 0.4, 30000);
        helper.assertTrue(f != null && same(f.lines().getFirst(), PathMorph.Frame.of(s0).lines().getFirst()), "a morph starts at the old lines");
        PathMorph.Frame mid = t.frame(s1, 10 * sec + 200_000_000L, 0.4, 30000);
        helper.assertTrue(mid != null && !same(mid.lines().getFirst(), PathMorph.Frame.of(s1).lines().getFirst()), "half way");
        // the next rerun arrives now: what is drawn does not jump
        PathMorph.Frame drawnBefore = t.frame(s1, 10 * sec + 250_000_000L, 0.4, 30000);
        PathMorph.Frame next = t.frame(s2, 10 * sec + 250_000_000L, 0.4, 30000);
        helper.assertTrue(next != null && next.lines().size() == drawnBefore.lines().size(), "the second morph starts with exactly the drawn lines");
        for (int i = 0; i < drawnBefore.lines().size(); i++) {
            helper.assertTrue(same(next.lines().get(i), drawnBefore.lines().get(i)), "jump: line " + i + " is not where it was drawn");
        }
        PathMorph.Frame later = t.frame(s2, 10 * sec + 250_000_000L + 100_000_000L, 0.4, 30000);
        helper.assertTrue(later != null && later.lines().stream().anyMatch(l -> l.index() == 3 && l.alpha() > 0.0), "the new line of the second rerun does not come in");
        // and it ends on the final data
        PathMorph.Frame end = t.frame(s2, 12 * sec, 0.4, 30000);
        helper.assertTrue(end == null && !t.active(), "the morph did not end");
        helper.succeed();
    }

    @GameTest
    public void whenToSkipTheAnimation(GameTestHelper helper) {
        PathSnapshot o = snap(1, "recorded", "d", 5, line("minecraft:tnt", 0, 5, 10, 0.0, 0.3, 70));
        long sec = 1_000_000_000L;
        // not a rerun, other dimension, other machine, animation off: shown as it is
        for (PathSnapshot n : new PathSnapshot[] {snap(2, "recorded", "d", 5, line("minecraft:tnt", 0, 5, 10, 9, 0.3, 70)), snap(2, "rerun", "other", 5, line("minecraft:tnt", 0, 5, 10, 9, 0.3, 70)),
            snap(2, "rerun", "d", 6, line("minecraft:tnt", 0, 5, 10, 9, 0.3, 70))}) {
            MorphTracker t = new MorphTracker();
            t.frame(o, 0, 0.4, 30000);
            helper.assertTrue(t.frame(n, sec, 0.4, 30000) == null, "animated " + n.source() + " " + n.dimension());
        }
        PathSnapshot rerun = snap(2, "rerun", "d", 5, line("minecraft:tnt", 0, 5, 10, 9, 0.3, 70));
        MorphTracker off = new MorphTracker();
        off.frame(o, 0, 0.0, 30000);
        helper.assertTrue(off.frame(rerun, sec, 0.0, 30000) == null, "animated with seconds 0");
        MorphTracker on = new MorphTracker();
        on.frame(o, 0, 0.4, 30000);
        helper.assertTrue(on.frame(rerun, sec, 0.4, 30000) != null, "a rerun of the same machine is not animated");
        // 500 paths are still animated, 501 are not (on either side); the segment cap too
        PathSnapshot.Line[] many = new PathSnapshot.Line[501];
        for (int i = 0; i < many.length; i++) {
            many[i] = line("minecraft:tnt", i, 5, 4, i, 0.1, 70);
        }
        PathSnapshot big = snap(1, "recorded", "d", 5, many);
        PathSnapshot ok = snap(2, "rerun", "d", 5, Arrays.copyOf(many, 500));
        PathSnapshot over = snap(2, "rerun", "d", 5, many);
        helper.assertTrue(PathMorph.shouldAnimate(PathMorph.Frame.of(ok), ok, 30000) && !PathMorph.shouldAnimate(PathMorph.Frame.of(over), ok, 30000)
            && !PathMorph.shouldAnimate(PathMorph.Frame.of(ok), over, 30000) && !PathMorph.shouldAnimate(PathMorph.Frame.of(big), ok, 30000), "the 500 paths rule");
        PathSnapshot longer = snap(2, "rerun", "d", 5, line("minecraft:tnt", 0, 5, 200, 0.0, 0.3, 70));
        helper.assertTrue(PathMorph.shouldAnimate(PathMorph.Frame.of(o), longer, 300) && !PathMorph.shouldAnimate(PathMorph.Frame.of(o), longer, 200), "the segment cap rule");
        helper.succeed();
    }

    /** The morph writes into nothing: the arrays of both snapshots are the same after thousands of frames. */
    @GameTest
    public void theMorphNeverWritesIntoASnapshot(GameTestHelper helper) {
        PathSnapshot o = snap(1, "recorded", "d", 0, line("minecraft:tnt", 0, 5, 12, 0.1, 0.3, 70), line("minecraft:tnt", 1, 5, 6, 3.0, -0.2, 70));
        PathSnapshot n = snap(2, "rerun", "d", 0, line("minecraft:tnt", 0, 7, 20, -0.0, 0.31, 70.5), line("minecraft:tnt", 7, 9, 4, 1.0, 1.0, 66));
        List<double[]> before = new ArrayList<>();
        List<long[]> beforeT = new ArrayList<>();
        for (PathSnapshot s : List.of(o, n)) {
            for (PathSnapshot.Line l : s.lines()) {
                before.add(l.xyz().clone());
                beforeT.add(l.ticks().clone());
            }
        }
        PathMorph m = new PathMorph(PathMorph.Frame.of(o), n);
        for (int i = 0; i <= 1000; i++) {
            m.at(i / 1000.0);
        }
        int k = 0;
        for (PathSnapshot s : List.of(o, n)) {
            for (PathSnapshot.Line l : s.lines()) {
                helper.assertTrue(Arrays.equals(before.get(k), l.xyz()) && Arrays.equals(beforeT.get(k), l.ticks()), "the morph changed a snapshot (line " + k + ")");
                k++;
            }
        }
        helper.succeed();
    }
}
