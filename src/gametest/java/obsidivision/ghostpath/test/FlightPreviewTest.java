package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.preview.PathBuilder;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;
import obsidivision.ghostpath.record.CauseTracker.FlightNote;
import obsidivision.ghostpath.record.CauseTracker.FlightPoint;
import obsidivision.ghostpath.record.CauseTracker.LiveEntity;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.watch.LiveWatch;
import obsidivision.ghostpath.watch.Prediction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step 5, the parts that can be checked on the server: the paths built from a run (end reasons, identical paths merged
 * with a count), the handoff to the client (the latest snapshot, published when a recording or a rerun is finished, never
 * changed while the ghost runs), a projectile still flying at the tick limit keeps a tail for the client to continue, and
 * the commands. Drawing is not tested here.
 */
public class FlightPreviewTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    private static FlightPoint pt(long tick, int index, String type, FlightKind kind, double x, double y, double z, boolean onGround, boolean removed, boolean portal) {
        return new FlightPoint(tick, index, type, kind, x, y, z, 0.0, -0.1, 0.0, onGround, removed, portal);
    }

    private static PathSnapshot.Line only(PathSnapshot s, String type, int startTick) {
        List<PathSnapshot.Line> l = s.lines().stream().filter(x -> x.type().equals(type) && x.startTick() == startTick).toList();
        if (l.size() != 1) {
            throw new AssertionError("expected one " + type + " line starting at t+" + startTick + ", found " + l.size() + " in " + s.lines().size());
        }
        return l.getFirst();
    }

    /** Sixty identical TNT paths are one line with a count; a different start tick or one different position is another line. */
    @GameTest
    public void identicalPathsAreMergedWithACount(GameTestHelper helper) {
        List<FlightPoint> pts = new ArrayList<>();
        List<FlightNote> notes = new ArrayList<>();
        for (int i = 0; i < 62; i++) {
            long start = i == 60 ? 6 : 5;
            double lastX = i == 61 ? 9.0 : 1.5;
            pts.add(pt(start, i, "minecraft:tnt", FlightKind.TNT, 1, 2, 3, false, false, false));
            for (int k = 1; k <= 5; k++) {
                boolean last = k == 5;
                pts.add(pt(start + k, i, "minecraft:tnt", FlightKind.TNT, last ? lastX : 1 + 0.1 * k, 2 - 0.05 * k, 3, false, last, false));
            }
            notes.add(new FlightNote(start + 5, i, "removed DISCARDED"));
        }
        PathSnapshot s = PathBuilder.build(1, "recorded", "minecraft:overworld", 20, false, pts, notes, List.of());
        helper.assertTrue(s.lines().size() == 3, "lines: " + s.lines().size());
        PathSnapshot.Line group = s.lines().getFirst();
        helper.assertTrue(group.count() == 60 && group.startTick() == 5 && group.end().reason().equals("exploded") && group.end().tick() == 10 && group.points() == 6,
            "group: count " + group.count() + ", start " + group.startTick() + ", end " + group.end() + ", points " + group.points());
        helper.assertTrue(s.lines().stream().mapToInt(PathSnapshot.Line::count).sum() == 62, "paths lost in the merge");
        helper.assertTrue(s.lines().stream().anyMatch(l -> l.count() == 1 && l.startTick() == 6) && s.lines().stream().anyMatch(l -> l.count() == 1 && l.startTick() == 5 && l.end().x() == 9.0),
            "the later start and the different end were merged");
        helper.succeed();
    }

    /** Each end reason, from the points and notes that cause it. */
    @GameTest
    public void endReasons(GameTestHelper helper) {
        List<FlightPoint> pts = new ArrayList<>();
        List<FlightNote> notes = new ArrayList<>();
        // 0: an item that falls for 4 ticks then lies on the ground for 12: settled 8 ticks after it came to rest
        for (int k = 0; k <= 16; k++) {
            boolean down = k < 4;
            pts.add(pt(10 + k, 0, "minecraft:item", FlightKind.ITEM, 5, down ? 5 - k : 1, 5, !down, false, false));
        }
        // 1: an arrow that hits a block at tick 8 and stays (not removed): hit block, path cut there
        for (int k = 0; k <= 12; k++) {
            pts.add(pt(4 + k, 1, "minecraft:arrow", FlightKind.PROJECTILE, k, 3, 0, false, false, false));
        }
        notes.add(new FlightNote(8, 1, "hit block"));
        // 2: an ender pearl that hits an entity and is removed in the same tick
        for (int k = 0; k <= 3; k++) {
            pts.add(pt(4 + k, 2, "minecraft:ender_pearl", FlightKind.PROJECTILE, k, 3, 1, false, k == 3, false));
        }
        notes.add(new FlightNote(7, 2, "hit entity"));
        notes.add(new FlightNote(7, 2, "removed DISCARDED"));
        // 3: a falling block that lands
        for (int k = 0; k <= 3; k++) {
            pts.add(pt(4 + k, 3, "minecraft:falling_block", FlightKind.FALLING_BLOCK, 2, 6 - k, 2, false, k == 3, false));
        }
        notes.add(new FlightNote(7, 3, "removed DISCARDED"));
        // 4: unloaded with its chunk; 5: changed dimension; 6: in a portal from tick 6 on
        for (int k = 0; k <= 3; k++) {
            pts.add(pt(4 + k, 4, "minecraft:snowball", FlightKind.PROJECTILE, k, 3, 2, false, k == 3, false));
            pts.add(pt(4 + k, 5, "minecraft:snowball", FlightKind.PROJECTILE, k, 3, 3, false, k == 3, false));
            pts.add(pt(4 + k, 6, "minecraft:snowball", FlightKind.PROJECTILE, k, 3, 4, false, false, k >= 2));
        }
        notes.add(new FlightNote(7, 4, "removed UNLOADED_TO_CHUNK"));
        notes.add(new FlightNote(7, 5, "removed CHANGED_DIMENSION"));
        // 7: still flying at the tick limit (with a saved state): limit, with a tail; 8: a mob, not drawn
        for (int k = 0; k <= 5; k++) {
            pts.add(pt(4 + k, 7, "minecraft:arrow", FlightKind.PROJECTILE, k, 3, 7, false, false, false));
            pts.add(pt(4 + k, 8, "minecraft:pig", FlightKind.OTHER, k, 3, 8, false, false, false));
        }
        // 9: an arrow that hits a block at tick 8 and stays there for 8 ticks: "stuck", at the hit (the note carries the hit's tick)
        for (int k = 0; k <= 14; k++) {
            pts.add(pt(4 + k, 9, "minecraft:arrow", FlightKind.PROJECTILE, k, 3, 9, false, false, false));
        }
        notes.add(new FlightNote(8, 9, "hit block"));
        notes.add(new FlightNote(8, 9, "stuck"));
        CompoundTag nbt = new CompoundTag();
        nbt.putString("marker", "tail");
        PathSnapshot s = PathBuilder.build(7, "rerun", "minecraft:overworld", 30, true, pts, notes,
            List.of(new LiveEntity(7, "minecraft:arrow", nbt, 9, 4.0, 3.0, 7.0, false)));
        helper.assertTrue(s.lines().size() == 9, "lines: " + s.lines().size() + " (the pig must not be drawn)");
        for (PathSnapshot.Line l : s.lines()) {
            helper.assertTrue(PathBuilder.REASONS.contains(l.end().reason()), "unknown reason " + l.end().reason());
        }
        PathSnapshot.Line item = only(s, "minecraft:item", 10);
        helper.assertTrue(item.end().reason().equals("settled") && item.end().tick() == 22, "item: " + item.end());
        PathSnapshot.Line arrow = s.lines().stream().filter(l -> l.type().equals("minecraft:arrow") && l.end().reason().equals("hit block")).findFirst().orElse(null);
        helper.assertTrue(arrow != null && arrow.end().tick() == 8 && arrow.points() == 5 && arrow.end().x() == 4.0, "arrow: " + arrow);
        PathSnapshot.Line stuck = s.lines().stream().filter(l -> l.end().reason().equals("stuck")).findFirst().orElse(null);
        helper.assertTrue(stuck != null && stuck.end().tick() == 8 && stuck.points() == 5 && stuck.end().z() == 9.0, "stuck arrow: " + (stuck == null ? null : stuck.end()));
        helper.assertTrue(s.lines().stream().anyMatch(l -> l.type().equals("minecraft:ender_pearl") && l.end().reason().equals("hit entity") && l.end().tick() == 7), "ender pearl");
        helper.assertTrue(s.lines().stream().anyMatch(l -> l.kind() == FlightKind.FALLING_BLOCK && l.end().reason().equals("landed")), "falling block");
        helper.assertTrue(s.lines().stream().anyMatch(l -> l.end().reason().equals("unloaded") && l.end().z() == 2.0), "unloaded");
        helper.assertTrue(s.lines().stream().anyMatch(l -> l.end().reason().equals("left dimension") && l.end().z() == 3.0), "left dimension");
        PathSnapshot.Line portal = s.lines().stream().filter(l -> l.end().z() == 4.0).findFirst().orElseThrow();
        helper.assertTrue(portal.end().reason().equals("portal") && portal.end().tick() == 6 && portal.points() == 3, "portal: " + portal.end() + ", points " + portal.points());
        PathSnapshot.Line limit = s.lines().stream().filter(l -> l.end().reason().equals("limit")).findFirst().orElseThrow();
        helper.assertTrue(limit.tail() != null && limit.tail().nbt().getStringOr("marker", "").equals("tail") && limit.tail().nbt() != nbt && limit.tail().tickCount() == 9
            && limit.end().tick() == 9, "tail: " + limit.tail());
        long withTail = s.lines().stream().filter(l -> l.tail() != null).count();
        helper.assertTrue(withTail == 1, "tails: " + withTail);
        helper.succeed();
    }

    // ---- the real thing: the handoff ----

    /**
     * The piston machine of {@link LiveRerunTest} through {@code /path machine}: the snapshot published at the end of the
     * recording has the real TNT's positions (exact doubles of the recording's event log) and ends "exploded". Then a piston
     * is added: during the whole rerun the published snapshot does not change; when it is done a new one ("rerun", a higher
     * serial) is there, built from the prediction, with the earlier spawn tick.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:flight_handoff")
    public void snapshotsAreHandedOverWhenARunIsDone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.rerunBudgetMs = 1;
        PathStore.clear();
        LiveRerunTest.build(helper);
        LiveRerunTest.Capture chat = new LiveRerunTest.Capture();
        LiveRerunTest.whenTicking(helper, LiveRerunTest.CORNERS, TestSupport.once(() -> LiveRerunTest.recordAndWatch(helper, level, chat, LiveRerunTest.LEVER, (rec, watch) -> {
            try {
                PathSnapshot first = PathStore.latest();
                helper.assertTrue(first != null && first.source().equals("recorded") && first.dimension().equals("minecraft:overworld"), "no snapshot of the recording: " + first);
                List<Integer> spawn = Prediction.tntSpawnTicks(rec.events(), rec.entityTrace());
                PathSnapshot.Line tnt = first.lines().stream().filter(l -> l.kind() == FlightKind.TNT).findFirst().orElse(null);
                helper.assertTrue(tnt != null && tnt.end().reason().equals("exploded") && tnt.startTick() == spawn.getFirst(), "TNT line: " + tnt + ", spawn " + spawn);
                // exact: the position of the last point is the position in the event log
                String lastState = rec.events().stream().filter(e -> e.kind() == obsidivision.ghostpath.record.ChainEvent.Kind.ENTITY_STATE && e.detail().startsWith("#0 minecraft:tnt"))
                    .reduce((a, b) -> b).orElseThrow().detail();
                String pos = lastState.replaceAll(".*pos=\\(([^)]*)\\).*", "$1");
                int n = tnt.points();
                String mine = tnt.xyz()[3 * n - 3] + ", " + tnt.xyz()[3 * n - 2] + ", " + tnt.xyz()[3 * n - 1];
                helper.assertTrue(mine.equals(pos), "end position " + mine + " vs the event log " + pos);
                helper.assertTrue(first.lines().stream().allMatch(l -> l.kind() != FlightKind.OTHER), "an undrawn kind in the snapshot");
                LOG.info("[ghostpath] snapshot of the recording: {} lines ({} entities), TNT t+{}..t+{} ({} points), ends {}", first.lines().size(),
                    first.lines().stream().mapToInt(PathSnapshot.Line::count).sum(), tnt.startTick(), tnt.end().tick(), n, tnt.end().reason());
                int reruns = watch.reruns();
                LiveRerunTest.placeP2(helper);
                LiveRerunTest.waitFor(helper, "the rerun", 60000, () -> {
                    if (watch.reruns() == reruns) {
                        helper.assertTrue(PathStore.latest() == first, "the published snapshot changed while the ghost was running");
                    }
                }, () -> watch.reruns() > reruns, () -> {
                    try {
                        PathSnapshot second = PathStore.latest();
                        Prediction p = watch.latest();
                        helper.assertTrue(second != first && second.source().equals("rerun") && second.serial() > first.serial(), "no new snapshot after the rerun: " + second);
                        PathSnapshot.Line t2 = second.lines().stream().filter(l -> l.kind() == FlightKind.TNT).findFirst().orElseThrow();
                        helper.assertTrue(t2.startTick() == p.tntSpawnTicks().getFirst() && t2.startTick() < tnt.startTick() && t2.end().reason().equals("exploded"),
                            "rerun TNT line: start " + t2.startTick() + " (prediction " + p.tntSpawnTicks() + ", recording " + tnt.startTick() + ")");
                        // the points are the prediction's points
                        List<FlightPoint> mineP = p.flightPoints().stream().filter(x -> x.kind() == FlightKind.TNT && x.index() == 0).toList();
                        helper.assertTrue(mineP.getLast().x() == t2.end().x() && mineP.getLast().z() == t2.end().z(), "the end is not the prediction's last TNT point");
                        LOG.info("[ghostpath] snapshot of the rerun: serial {} -> {}, TNT t+{} -> t+{}", first.serial(), second.serial(), tnt.startTick(), t2.startTick());
                        LiveRerunTest.finish(helper);
                        helper.succeed();
                    } catch (RuntimeException e) {
                        LiveRerunTest.finish(helper);
                        throw e;
                    }
                });
            } catch (RuntimeException e) {
                LiveRerunTest.finish(helper);
                throw e;
            }
        })), 0);
    }

    /**
     * A dispenser shoots an arrow straight up and the recording is limited to 10 ticks: the arrow is still flying, so its
     * line ends "limit" and carries a tail, the arrow's saved state at that moment (its Pos is the line's last point).
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 8, environment = "ghostpath-gametest:flight_tail")
    public void aProjectileStillFlyingKeepsATail(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        PathSettings.tickLimit = 10;
        PathStore.clear();
        obsidivision.ghostpath.record.RecordingStore.clear();
        TestSupport.removeBarriers(helper); // the arrow flies up through the structure's ceiling shell otherwise
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        BlockPos disp = new BlockPos(2, 2, 2);
        helper.setBlock(disp, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.UP));
        ((DispenserBlockEntity) level.getBlockEntity(helper.absolutePos(disp))).setItem(0, new ItemStack(Items.ARROW));
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        BlockPos lever = helper.absolutePos(new BlockPos(1, 2, 2));
        TestSupport.whenSavesFree(helper, () -> whenTicking(helper, lever, () -> {
            MachineCommandTest.Capture out = new MachineCommandTest.Capture();
            MachineCommandTest.runCommand(helper, level, out, lever, () -> {
                try {
                    Recording r = obsidivision.ghostpath.record.RecordingStore.latest();
                    helper.assertTrue(r != null && r.limitHit(), "the recording did not stop at the limit: " + out.lines);
                    helper.assertTrue(r.liveProjectiles().size() == 1 && r.liveProjectiles().getFirst().type().equals("minecraft:arrow"), "live projectiles: " + r.liveProjectiles());
                    PathSnapshot s = PathStore.latest();
                    helper.assertTrue(s != null && s.limitHit() && s.lines().size() == 1, "snapshot: " + s);
                    PathSnapshot.Line l = s.lines().getFirst();
                    helper.assertTrue(l.kind() == FlightKind.PROJECTILE && l.end().reason().equals("limit") && l.tail() != null, "line: " + l.end() + ", tail " + l.tail());
                    var pos = l.tail().nbt().read("Pos", net.minecraft.world.phys.Vec3.CODEC).orElseThrow();
                    int n = l.points();
                    helper.assertTrue(pos.x == l.xyz()[3 * n - 3] && pos.y == l.xyz()[3 * n - 2] && pos.z == l.xyz()[3 * n - 1], "tail Pos " + pos + " is not the last point");
                    helper.assertTrue(l.xyz()[3 * n - 2] > l.xyz()[1], "the arrow did not rise");
                    LOG.info("[ghostpath] tail: arrow t+{}..t+{}, {} points, ends {} at y={}", l.startTick(), l.end().tick(), n, l.end().reason(), l.end().y());
                    PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
                    helper.succeed();
                } catch (RuntimeException e) {
                    PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
                    throw e;
                }
            });
        }));
    }

    /**
     * A dispenser throws a snowball east into a stone wall: the real run's snapshot has one projectile line that ends
     * "hit block" at the wall's face, with no tail (it did not keep flying). Also an egg thrown into open air for 10 ticks is
     * not needed here; the "limit" case is the test above.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 8, environment = "ghostpath-gametest:flight_hit")
    public void aProjectileThatHitsABlockEndsThere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
        PathStore.clear();
        obsidivision.ghostpath.record.RecordingStore.clear();
        TestSupport.removeBarriers(helper);
        Runnable unforce = TestSupport.forceAround(helper, 2);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        for (int y = 2; y <= 5; y++) {
            for (int z = 0; z < 6; z++) {
                helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
            }
        }
        BlockPos disp = new BlockPos(2, 2, 2);
        helper.setBlock(disp, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        ((DispenserBlockEntity) level.getBlockEntity(helper.absolutePos(disp))).setItem(0, new ItemStack(Items.SNOWBALL));
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        BlockPos lever = helper.absolutePos(new BlockPos(1, 2, 2));
        TestSupport.whenSavesFree(helper, () -> whenTicking(helper, lever, () -> {
            MachineCommandTest.Capture out = new MachineCommandTest.Capture();
            MachineCommandTest.runCommand(helper, level, out, lever, () -> {
                try {
                    Recording r = obsidivision.ghostpath.record.RecordingStore.latest();
                    helper.assertTrue(r != null && !r.limitHit(), "recording: " + out.lines);
                    PathSnapshot s = PathStore.latest();
                    helper.assertTrue(s != null && s.lines().size() == 1, "snapshot: " + s);
                    PathSnapshot.Line l = s.lines().getFirst();
                    helper.assertTrue(l.type().equals("minecraft:snowball") && l.end().reason().equals("hit block") && l.tail() == null, "line: " + l.type() + " " + l.end() + ", tail " + l.tail());
                    double wallFace = helper.absolutePos(new BlockPos(6, 2, 2)).getX();
                    helper.assertTrue(Math.abs(l.end().x() - wallFace) < 0.2, "ends at x=" + l.end().x() + ", the wall's face is x=" + wallFace);
                    LOG.info("[ghostpath] hit: snowball t+{}..t+{}, {} points, ends {} at x={}", l.startTick(), l.end().tick(), l.points(), l.end().reason(), l.end().x());
                    helper.succeed();
                } finally {
                    unforce.run();
                }
            });
        }));
    }

    /**
     * Stuck arrows (ruling after step 5): a dispenser shoots an arrow up, it comes down and sticks in the dispenser's roof. No
     * tick limit helps (the default 2000): the recording ends 8 ticks after the arrow stuck instead of waiting for its despawn
     * (1200 ticks), and the path ends where it hit, "stuck".
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 8, environment = "ghostpath-gametest:flight_hit")
    public void aStuckArrowIsSettled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
        PathStore.clear();
        obsidivision.ghostpath.record.RecordingStore.clear();
        TestSupport.removeBarriers(helper);
        Runnable unforce = TestSupport.forceAround(helper, 2);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        BlockPos disp = new BlockPos(2, 2, 2);
        helper.setBlock(disp, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.UP));
        ((DispenserBlockEntity) level.getBlockEntity(helper.absolutePos(disp))).setItem(0, new ItemStack(Items.ARROW));
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        BlockPos lever = helper.absolutePos(new BlockPos(1, 2, 2));
        TestSupport.whenSavesFree(helper, () -> whenTicking(helper, lever, () -> {
            MachineCommandTest.Capture out = new MachineCommandTest.Capture();
            MachineCommandTest.runCommand(helper, level, out, lever, () -> {
                try {
                    Recording r = obsidivision.ghostpath.record.RecordingStore.latest();
                    helper.assertTrue(r != null && !r.limitHit(), "recording: " + out.lines);
                    PathSnapshot s = PathStore.latest();
                    helper.assertTrue(s != null && s.lines().size() == 1, "snapshot: " + s);
                    PathSnapshot.Line l = s.lines().getFirst();
                    helper.assertTrue(l.type().equals("minecraft:arrow") && l.end().reason().equals("stuck") && l.tail() == null, "line: " + l.type() + " " + l.end() + ", tail " + l.tail());
                    helper.assertTrue(r.ticks() <= l.end().tick() + obsidivision.ghostpath.record.CauseTracker.ARROW_SETTLE_TICKS + 4,
                        "the recording ran " + r.ticks() + " ticks, the arrow stuck at t+" + l.end().tick());
                    LOG.info("[ghostpath] stuck: arrow t+{}..t+{}, ends {}, recording {} ticks", l.startTick(), l.end().tick(), l.end().reason(), r.ticks());
                    helper.succeed();
                } finally {
                    unforce.run();
                }
            });
        }));
    }

    private static void whenTicking(GameTestHelper helper, BlockPos abs, Runnable then) {
        if (helper.getLevel().isPositionEntityTicking(abs)) {
            then.run();
        } else {
            helper.runAfterDelay(1, () -> whenTicking(helper, abs, then));
        }
    }

    /** {@code /path show on|off} and {@code /path interval <ticks> [offset <n>]}. */
    @GameTest
    public void showAndIntervalCommands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        boolean show = PathSettings.showPaths;
        int interval = PathSettings.dotInterval;
        int offset = PathSettings.dotOffset;
        try {
            LiveRerunTest.Capture out = new LiveRerunTest.Capture();
            LiveRerunTest.command(level, out, "path show off");
            helper.assertTrue(!PathSettings.showPaths && out.has("hidden"), "show off: " + out.lines);
            LiveRerunTest.command(level, out, "path show on");
            helper.assertTrue(PathSettings.showPaths, "show on");
            LiveRerunTest.command(level, out, "path interval 3");
            helper.assertTrue(PathSettings.dotInterval == 3 && PathSettings.dotOffset == 0, "interval 3");
            LiveRerunTest.command(level, out, "path interval 4 offset 2");
            helper.assertTrue(PathSettings.dotInterval == 4 && PathSettings.dotOffset == 2, "interval 4 offset 2: " + out.lines);
            LiveRerunTest.command(level, out, "path interval 0");
            helper.assertTrue(PathSettings.dotInterval == 4, "interval 0 was accepted");
            helper.succeed();
        } finally {
            PathSettings.showPaths = show;
            PathSettings.dotInterval = interval;
            PathSettings.dotOffset = offset;
        }
    }
}
