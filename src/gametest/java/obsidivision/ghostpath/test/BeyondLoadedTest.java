package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import obsidivision.ghostpath.inspect.PanelText;
import obsidivision.ghostpath.preview.PathBuilder;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;
import obsidivision.ghostpath.record.CauseTracker.FlightNote;
import obsidivision.ghostpath.record.CauseTracker.FlightPoint;

/**
 * Marking where the real world would freeze a path (beyond-loaded change, item 1), on plain data: a TNT flying east 2 blocks a tick
 * crosses from chunk 0 (ticks) into chunk 1 and chunk 2. With chunk 1 "lazy" the path gets its freeze at the first point inside it,
 * with chunk 1 ticking and chunk 2 "unloaded" at the first point inside chunk 2; the end text gives both; a path that stays in
 * ticking chunks has no freeze; two identical paths still merge, a path that differs in its freeze does not merge with them.
 */
public class BeyondLoadedTest {
    private static List<FlightPoint> path(int index, double x0, double dx, int n, boolean last) {
        List<FlightPoint> pts = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            boolean end = last && k == n - 1;
            pts.add(new FlightPoint(5 + k, index, "minecraft:tnt", FlightKind.TNT, x0 + dx * k, 64.0, 8.0, dx, 0.0, 0.0, false, end, false));
        }
        return pts;
    }

    @GameTest
    public void aPathIsMarkedWhereTheRealWorldFreezesIt(GameTestHelper helper) {
        // x = 4, 6, ..., 4 + 2*19 = 42: chunk 0 up to x < 16 (points 0..5), chunk 1 for x in 16..31 (points 6..13), chunk 2 from x >= 32 (points 14..19)
        List<FlightPoint> pts = path(0, 4.0, 2.0, 20, true);
        List<FlightNote> notes = List.of(new FlightNote(5 + 19, 0, "removed DISCARDED"));
        long c1 = ChunkPos.pack(1, 0);
        long c2 = ChunkPos.pack(2, 0);

        PathSnapshot lazy = PathBuilder.build(1, "rerun", "minecraft:overworld", 30, false, pts, notes, List.of(), Map.of(c1, "lazy"));
        PathSnapshot.Line l = lazy.lines().getFirst();
        helper.assertTrue(l.freeze() != null && l.freeze().point() == 6 && l.freeze().tick() == 11 && l.freeze().kind().equals("lazy") && l.freeze().x() == 16.0,
            "lazy chunk: " + l.freeze());
        helper.assertTrue(PathSnapshot.endText(l).equals("real: frozen at t+11; ghost: exploded at t+24"), "end text: " + PathSnapshot.endText(l));
        helper.assertTrue(PanelText.ghost(l, 2, 0, "rerun").stream().anyMatch(s -> s.equals("real: freezes at t+11 (lazy)")), "panel before: " + PanelText.ghost(l, 2, 0, "rerun"));
        helper.assertTrue(PanelText.ghost(l, 8, 0, "rerun").stream().anyMatch(s -> s.contains("BEYOND the real ticking area (lazy)")), "panel after: " + PanelText.ghost(l, 8, 0, "rerun"));
        helper.assertTrue(PanelText.ghost(l, 19, 0, "rerun").stream().anyMatch(s -> s.equals("ends: real: frozen at t+11; ghost: exploded at t+24")), "panel end: " + PanelText.ghost(l, 19, 0, "rerun"));

        PathSnapshot unloaded = PathBuilder.build(1, "rerun", "minecraft:overworld", 30, false, pts, notes, List.of(), Map.of(c2, "unloaded"));
        PathSnapshot.Line u = unloaded.lines().getFirst();
        helper.assertTrue(u.freeze() != null && u.freeze().point() == 14 && u.freeze().tick() == 19 && u.freeze().kind().equals("unloaded"), "unloaded chunk: " + u.freeze());

        PathSnapshot none = PathBuilder.build(1, "rerun", "minecraft:overworld", 30, false, pts, notes, List.of(), Map.of(ChunkPos.pack(7, 7), "unloaded"));
        helper.assertTrue(none.lines().getFirst().freeze() == null && PathSnapshot.endText(none.lines().getFirst()).equals("exploded t+24"), "a path that stays in ticking chunks: " + none.lines().getFirst().freeze());
        PathSnapshot recorded = PathBuilder.build(1, "recorded", "minecraft:overworld", 30, false, pts, notes, List.of());
        helper.assertTrue(recorded.lines().getFirst().freeze() == null, "a real recording has no freeze");

        // two identical paths merge, with the same freeze; a third one that stops in chunk 0 is a different path
        List<FlightPoint> two = new ArrayList<>(pts);
        two.addAll(path(1, 4.0, 2.0, 20, true));
        List<FlightNote> twoNotes = List.of(new FlightNote(24, 0, "removed DISCARDED"), new FlightNote(24, 1, "removed DISCARDED"));
        PathSnapshot merged = PathBuilder.build(1, "rerun", "minecraft:overworld", 30, false, two, twoNotes, List.of(), Map.of(c1, "lazy"));
        helper.assertTrue(merged.lines().size() == 1 && merged.lines().getFirst().count() == 2 && merged.lines().getFirst().freeze() != null, "merge: " + merged.lines().size() + " lines");
        helper.succeed();
    }
}
