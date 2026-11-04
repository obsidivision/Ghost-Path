package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.inspect.EntityInspector;
import obsidivision.ghostpath.inspect.EntityReadout;
import obsidivision.ghostpath.inspect.PanelText;
import obsidivision.ghostpath.inspect.Picker;
import obsidivision.ghostpath.inspect.Pin;
import obsidivision.ghostpath.preview.PathBuilder;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;
import obsidivision.ghostpath.record.CauseTracker.FlightNote;
import obsidivision.ghostpath.record.CauseTracker.FlightPoint;

/**
 * Build step 6, what can be checked without a screen: picking a path point with a ray, pins finding their entity and tick again
 * in a new snapshot, the panel text (doubles read back bit for bit) and the server-side lookup of real entities and stacks. The
 * hover, click, scroll and drawing are checked on the real client ({@code InspectorGameTest}).
 */
public class InspectorTest {
    private static final Vec3 Z = new Vec3(0, 0, 1);

    /** A TNT at (x0 + k * dx, y, z) for k = 0..n-1, at ticks start+k, exploding at the last point; fuse counts down from 80. */
    private static void tnt(List<FlightPoint> pts, List<FlightNote> notes, int index, long start, int n, double x0, double dx, double y, double z) {
        for (int k = 0; k < n; k++) {
            double x = x0 + k * dx;
            boolean last = k == n - 1;
            pts.add(new FlightPoint(start + k, index, "minecraft:tnt", FlightKind.TNT, x, y, z, dx, -0.04, 0.0, false, last, false,
                new AABB(x - 0.49, y, z - 0.49, x + 0.49, y + 0.98, z + 0.49), 80 - k));
        }
        notes.add(new FlightNote(start + n - 1, index, "removed DISCARDED"));
    }

    private static PathSnapshot snapshot(List<FlightPoint> pts, List<FlightNote> notes) {
        return PathBuilder.build(1, "recorded", "minecraft:overworld", 40, false, pts, notes, List.of());
    }

    private static Picker.Hit pick(PathSnapshot s, Vec3 origin, Vec3 dir, double max) {
        return Picker.pick(s, origin, dir, max, l -> 0.0);
    }

    @GameTest
    public void aRayPicksThePathPointItPassesNearest(GameTestHelper helper) {
        List<FlightPoint> pts = new ArrayList<>();
        List<FlightNote> notes = new ArrayList<>();
        tnt(pts, notes, 3, 5, 11, 0.0, 1.0, 0.0, 0.0); // x = 0..10 at t+5..t+15
        PathSnapshot s = snapshot(pts, notes);
        // through the path at x = 3.2: the segment between x=3 (t+8) and x=4 (t+9), nearer to x=3
        Picker.Hit h = pick(s, new Vec3(3.2, 0, -10), Z, 64);
        helper.assertTrue(h != null && h.line() == 0 && h.point() == 3 && Math.abs(h.along() - 10) < 1.0E-9 && h.lateral() < 1.0E-9, "hit at x=3.2: " + h);
        h = pick(s, new Vec3(3.8, 0, -10), Z, 64);
        helper.assertTrue(h != null && h.point() == 4, "hit at x=3.8: " + h);
        // a miss by a block
        helper.assertTrue(pick(s, new Vec3(3.2, 1, -10), Z, 64) == null, "a miss by 1 block was a hit");
        // the tolerance grows with the distance: 0.3 off is a miss at 5 blocks and a hit at 30
        helper.assertTrue(pick(s, new Vec3(3, 0.3, -5), Z, 64) == null, "0.3 off at 5 blocks");
        helper.assertTrue(pick(s, new Vec3(3, 0.3, -30), Z, 64) != null, "0.3 off at 30 blocks");
        // a block in the way (the longest distance is shorter than the hit): nothing
        helper.assertTrue(pick(s, new Vec3(3.2, 0, -10), Z, 5) == null, "a point behind the longest distance was picked");
        // a ray along the path (parallel to its segments) still finds it, at the first point
        h = pick(s, new Vec3(-5, 0, 0), new Vec3(1, 0, 0), 64);
        helper.assertTrue(h != null && h.point() == 0 && Math.abs(h.along() - 5) < 1.0E-9, "ray along the path: " + h);
        // behind the camera: nothing
        helper.assertTrue(pick(s, new Vec3(3.2, 0, 10), Z, 64) == null, "a path behind the camera was picked");
        // the drawn lift: the line is drawn 0.49 above the feet
        helper.assertTrue(Picker.pick(s, new Vec3(3.2, 0.49, -10), Z, 64, l -> 0.49) != null && Picker.pick(s, new Vec3(3.2, 0.0, -10), Z, 64, l -> 0.49) == null, "lift");
        helper.succeed();
    }

    @GameTest
    public void theNearestPathToTheCameraWins(GameTestHelper helper) {
        List<FlightPoint> pts = new ArrayList<>();
        List<FlightNote> notes = new ArrayList<>();
        tnt(pts, notes, 1, 5, 11, 0.0, 1.0, 0.0, 0.0);
        tnt(pts, notes, 2, 5, 11, 0.0, 1.0, 0.05, -4.0); // 4 blocks nearer to a camera at z = -10, and slightly higher
        PathSnapshot s = snapshot(pts, notes);
        helper.assertTrue(s.lines().size() == 2, "lines " + s.lines().size());
        Picker.Hit h = pick(s, new Vec3(6.0, 0, -10), Z, 64);
        helper.assertTrue(h != null && s.lines().get(h.line()).members()[0] == 2 && Math.abs(h.along() - 6) < 1.0E-9, "the nearer path must win: " + h);
        // a block in the way at 8 blocks: the near path (6 blocks away) is still picked
        h = pick(s, new Vec3(6.0, 0, -10), Z, 8);
        helper.assertTrue(h != null && s.lines().get(h.line()).members()[0] == 2, "the near path with a block behind it: " + h);
        // a block at 5 blocks hides both (the near one is at 6, the far one at 10)
        helper.assertTrue(pick(s, new Vec3(6.0, 0, -10), Z, 5) == null, "a path behind the block was picked");
        helper.succeed();
    }

    @GameTest
    public void aPinSticksToItsTickAcrossARerun(GameTestHelper helper) {
        // before: entities #3 and #4 are identical (one line, x2), #9 is another TNT
        List<FlightPoint> pts = new ArrayList<>();
        List<FlightNote> notes = new ArrayList<>();
        tnt(pts, notes, 3, 5, 11, 0.0, 1.0, 0.0, 0.0);
        tnt(pts, notes, 4, 5, 11, 0.0, 1.0, 0.0, 0.0);
        tnt(pts, notes, 9, 7, 5, 20.0, 0.5, 2.0, 0.0);
        PathSnapshot before = snapshot(pts, notes);
        helper.assertTrue(before.lines().size() == 2 && before.lines().getFirst().count() == 2 && before.lines().getFirst().members().length == 2, "lines: " + before.lines().size());
        Pin pin = new Pin("minecraft:tnt", 4, 9);
        Pin.Resolution r = pin.resolve(before);
        helper.assertTrue(r.found() && r.line() == 0 && before.lines().get(0).ticks()[r.point()] == 9, "before: " + r);
        // after the rerun: #4 moved (a piston): it takes another path, ends at t+8; #3 and #9 as before
        pts = new ArrayList<>();
        notes = new ArrayList<>();
        tnt(pts, notes, 3, 5, 11, 0.0, 1.0, 0.0, 0.0);
        tnt(pts, notes, 4, 5, 4, 0.0, 2.0, 0.0, 3.0);
        tnt(pts, notes, 9, 7, 5, 20.0, 0.5, 2.0, 0.0);
        PathSnapshot after = snapshot(pts, notes);
        helper.assertTrue(after.lines().size() == 3, "lines after: " + after.lines().size());
        // t+7 is on #4's new path: the pin is now on that line, at that tick, at that path's position
        Pin early = new Pin("minecraft:tnt", 4, 7);
        r = early.resolve(after);
        PathSnapshot.Line moved = after.lines().get(r.line());
        helper.assertTrue(r.found() && moved.members().length == 1 && moved.members()[0] == 4 && moved.ticks()[r.point()] == 7 && moved.xyz()[3 * r.point() + 2] == 3.0, "after, t+7: " + r + " " + moved.end());
        // t+9 is past the new end (t+8): the pin stays, pointing nowhere, with the reason
        r = pin.resolve(after);
        helper.assertTrue(!r.found() && r.problem().contains("no point at t+9"), "after, t+9: " + r.problem());
        // an entity or type that is not there at all
        helper.assertTrue(!new Pin("minecraft:tnt", 77, 7).resolve(after).found() && !new Pin("minecraft:arrow", 4, 7).resolve(after).found(), "unknown entity or type was found");
        // and it comes back when the path comes back
        helper.assertTrue(pin.resolve(before).found(), "the pin must find its point again in the old snapshot");
        // toggle
        List<Pin> pins = new ArrayList<>();
        helper.assertTrue(Pin.toggle(pins, pin) && pins.size() == 1 && !Pin.toggle(pins, new Pin("minecraft:tnt", 4, 9)) && pins.isEmpty(), "toggle");
        helper.succeed();
    }

    @GameTest
    public void thePanelTextReadsBackBitForBit(GameTestHelper helper) {
        double[][] cases = {{0.1 + 0.2, -0.0, 1.0E-7}, {-12.5, 64.0625, 8.03125}, {Double.MIN_VALUE, 123456789.123456789, -Double.MAX_VALUE}, {Math.PI, Math.E, 1.0 / 3.0}};
        for (double[] c : cases) {
            List<FlightPoint> pts = new ArrayList<>();
            List<FlightNote> notes = new ArrayList<>();
            for (int k = 0; k < 3; k++) {
                pts.add(new FlightPoint(5 + k, 1, "minecraft:tnt", FlightKind.TNT, c[0], c[1], c[2], c[2], c[0], c[1], false, k == 2, false,
                    new AABB(c[0], c[1], c[2], c[0] + 1, c[1] + 1, c[2] + 1), 40 - k));
            }
            notes.add(new FlightNote(7, 1, "removed DISCARDED"));
            PathSnapshot.Line l = snapshot(pts, notes).lines().getFirst();
            List<String> lines = PanelText.ghost(l, 1, 0, "recorded");
            helper.assertTrue(bits(value(lines, "pos x ")) == bits(c[0]) && bits(value(lines, "pos y ")) == bits(c[1]) && bits(value(lines, "pos z ")) == bits(c[2]), "pos did not read back: " + lines);
            helper.assertTrue(bits(value(lines, "vel x ")) == bits(c[2]) && bits(value(lines, "vel y ")) == bits(c[0]) && bits(value(lines, "vel z ")) == bits(c[1]), "vel did not read back: " + lines);
            helper.assertTrue(bits(value(lines, "in block x ")) == bits(c[0] - Math.floor(c[0])) && bits(value(lines, "in block y ")) == bits(c[1] - Math.floor(c[1]))
                && bits(value(lines, "in block z ")) == bits(c[2] - Math.floor(c[2])), "fraction: " + lines);
            helper.assertTrue(lines.contains("block " + (int) Math.floor(c[0]) + " " + (int) Math.floor(c[1]) + " " + (int) Math.floor(c[2])), "block: " + lines);
            helper.assertTrue(lines.stream().allMatch(row -> row.length() < 50), "a line is too wide for the screen: " + lines);
            helper.assertTrue(lines.contains("fuse 39") && lines.getFirst().contains("t+6") && lines.stream().anyMatch(s -> s.equals("single")), "text: " + lines);
            List<String> end = PanelText.ghost(l, 2, 0, "rerun");
            helper.assertTrue(end.stream().anyMatch(s -> s.equals("ends: exploded t+7")), "end text: " + end);
        }
        helper.succeed();
    }

    private static double value(List<String> lines, String prefix) {
        return Double.parseDouble(lines.stream().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow().substring(prefix.length()));
    }

    private static long bits(double d) {
        return Double.doubleToLongBits(d);
    }

    /** The server's own values for a hovered real entity, and its stack. */
    @GameTest
    public void theServerLookupGivesTheExactValuesAndTheStack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        double x = at.getX() + 0.1 + 0.2;
        double y = at.getY() + 0.0625;
        double z = at.getZ() + 1.0 / 3.0;
        List<PrimedTnt> stack = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PrimedTnt t = new PrimedTnt(level, x, y, z, null);
            t.setNoGravity(true);
            t.setDeltaMovement(0.125, 0.0, -0.25);
            t.setFuse(77);
            level.addFreshEntity(t);
            stack.add(t);
        }
        PrimedTnt other = new PrimedTnt(level, x + 2, y, z, null); // elsewhere: not in the stack
        other.setNoGravity(true);
        level.addFreshEntity(other);
        Arrow arrow = new Arrow(net.minecraft.world.entity.EntityTypes.ARROW, level);
        arrow.setPos(x, y + 3, z);
        arrow.setDeltaMovement(0.5, 0.25, 0.125);
        arrow.setNoGravity(true);
        level.addFreshEntity(arrow);
        helper.runAfterDelay(2, () -> {
            PrimedTnt t = stack.get(1);
            EntityReadout r = EntityInspector.read(level, t.getId());
            helper.assertTrue(r != null && r.id() == t.getId() && r.uuid().equals(t.getUUID()) && r.type().equals("minecraft:tnt"), "readout: " + r);
            helper.assertTrue(bits(r.x()) == bits(t.getX()) && bits(r.y()) == bits(t.getY()) && bits(r.z()) == bits(t.getZ()), "position is not the entity's");
            helper.assertTrue(bits(r.vx()) == bits(t.getDeltaMovement().x) && bits(r.vy()) == bits(t.getDeltaMovement().y) && bits(r.vz()) == bits(t.getDeltaMovement().z), "velocity");
            helper.assertTrue(r.box().equals(t.getBoundingBox()) && r.fuse() == t.getFuse() && r.fuse() < 77 && r.tickCount() == t.tickCount, "box " + r.box() + ", fuse " + r.fuse());
            EntityInspector.Stack s = EntityInspector.stack(level, t.getId());
            helper.assertTrue(s != null && s.members().size() == 3 && s.identical(), "stack: " + (s == null ? null : s.members().size() + " identical " + s.identical()));
            helper.assertTrue(s.members().stream().map(EntityReadout::id).toList().equals(stack.stream().map(PrimedTnt::getId).sorted().toList()), "members not sorted by id");
            // a member with another fuse makes the stack not identical
            stack.get(2).setFuse(50);
            s = EntityInspector.stack(level, t.getId());
            helper.assertTrue(s.members().size() == 3 && !s.identical(), "different fuse: identical " + s.identical());
            // a projectile
            EntityReadout a = EntityInspector.read(level, arrow.getId());
            helper.assertTrue(a != null && a.type().equals("minecraft:arrow") && a.fuse() == -1 && bits(a.vx()) == bits(arrow.getDeltaMovement().x) && a.box().equals(arrow.getBoundingBox()), "arrow readout: " + a);
            EntityInspector.Stack as = EntityInspector.stack(level, arrow.getId());
            helper.assertTrue(as.members().size() == 1 && as.identical(), "arrow stack");
            // an entity that is gone
            int gone = other.getId();
            other.discard();
            helper.assertTrue(EntityInspector.read(level, gone) == null && EntityInspector.stack(level, gone) == null, "a removed entity was read");
            helper.assertTrue(EntityInspector.read(level, -12345) == null, "unknown id");
            List<String> text = PanelText.real(r, 1, 3, false);
            helper.assertTrue(text.stream().anyMatch(l -> l.contains("2/3")) && text.stream().anyMatch(l -> l.contains("NOT all identical")) && text.stream().anyMatch(l -> l.startsWith("fuse ")), "text: " + text);
            stack.forEach(PrimedTnt::discard);
            arrow.discard();
            helper.succeed();
        });
    }
}
