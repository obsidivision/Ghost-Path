package obsidivision.ghostpath.test;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.ghost.GhostCheck;
import obsidivision.ghostpath.inspect.PanelText;
import obsidivision.ghostpath.preview.PathBuilder;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Frozen entities (session "frozen entities and clocks", item 2). One chunk A is forced (entity ticking); B east of it is at level 32
 * (block ticking, entities frozen). An ender pearl without owner hangs in B's first column (a pearl in stasis: a dispenser drops pearls as
 * items in 26.2, so the test puts it there, as a player's throw would have). The machine, all in A except the piston:
 * <ul>
 * <li>a lever lights 10 TNT blocks through a dust line on stone beside them (dust strongly powers the block it sits on,
 *     {@code RedStoneWireBlock.getSignal} :361-369); water from the sources next to them flows into their cells, so the 10 TNT explode in
 *     water at t+81 and break nothing (the first ray step meets the fluid's resistance), but push the frozen pearl: 10 explosions of lazy
 *     acceleration (DESIGN "Lazy chunks": the push changes velocity only);</li>
 * <li>the same lever starts a repeater line (8 x delay 4, dust, 13 x delay 1) that fires a piston in B at about t+90: its head pushes the
 *     pearl one block west into A, where it ticks again with the built-up velocity (and flies east into the piston head: hit block).</li>
 * </ul>
 * {@code releasedPearlIsExactAndThePathResumes}: real and ghost exact (frozen ticks are in the entity trace, so the velocity build-up is
 * compared), the path has one stretch with 10 explosion pushes, moved while frozen (dashed), and goes on. {@code withoutReleaseTheRunEnds...}:
 * no piston: the run ends when the boosters are done, not at the tick limit, the pearl's end reason is "frozen: lazy chunk".
 */
public class FrozenEntityTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    private static final int LIMIT = 600;

    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, environment = "ghostpath-gametest:frozen_release")
    public void releasedPearlIsExactAndThePathResumes(GameTestHelper helper) {
        run(helper, true, 1900);
    }

    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, environment = "ghostpath-gametest:frozen_no_release")
    public void withoutReleaseTheRunEndsWhenTheBoostersAreDone(GameTestHelper helper) {
        run(helper, false, 2100);
    }

    private static boolean ready(ServerLevel level, ChunkPos a, ChunkPos... lazy) {
        if (!level.isPositionEntityTicking(new BlockPos(a.getMiddleBlockX(), level.getMinY() + 1, a.getMiddleBlockZ()))) {
            return false;
        }
        for (ChunkPos c : lazy) {
            if (level.getChunkSource().getChunkNow(c.x(), c.z()) == null || !level.areEntitiesLoaded(c.pack())) {
                return false;
            }
        }
        return true;
    }

    private static void when(GameTestHelper helper, java.util.function.BooleanSupplier cond, Runnable then) {
        if (cond.getAsBoolean()) {
            then.run();
        } else if (TestSupport.overRealTime(helper)) {
            helper.fail("chunks not ready");
        } else {
            helper.runAfterDelay(1, () -> when(helper, cond, then));
        }
    }

    /** Builds the machine at A's local coordinates: x relative to A's west edge, z relative to A's middle, y relative to y0. */
    private static final class Site {
        final ServerLevel level;
        final int x0;
        final int z0;
        final int y0;

        Site(ServerLevel level, int x0, int z0, int y0) {
            this.level = level;
            this.x0 = x0;
            this.z0 = z0;
            this.y0 = y0;
        }

        BlockPos at(int x, int y, int z) {
            return new BlockPos(this.x0 + x, this.y0 + y, this.z0 + z);
        }

        void set(int x, int y, int z, net.minecraft.world.level.block.state.BlockState s) {
            this.level.setBlock(this.at(x, y, z), s, 2);
        }
    }

    private static void run(GameTestHelper helper, boolean release, int offset) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(BlockPos.ZERO).offset(offset, 0, offset);
        ChunkPos a = new ChunkPos(base.getX() >> 4, base.getZ() >> 4);
        ChunkPos b = new ChunkPos(a.x() + 1, a.z());
        ChunkPos north = new ChunkPos(a.x(), a.z() - 1);
        ChunkPos northB = new ChunkPos(a.x() + 1, a.z() - 1);
        level.setChunkForced(a.x(), a.z(), true);
        when(helper, () -> ready(level, a, b, north, northB), () -> TestSupport.whenSavesFree(helper, TestSupport.once(() -> {
            int y0 = level.getHeight(Heightmap.Types.MOTION_BLOCKING, a.getMiddleBlockX(), a.getMiddleBlockZ()) + 3;
            Site s = new Site(level, a.getMinBlockX(), a.getMiddleBlockZ(), y0);
            build(s, release);
            helper.assertTrue(!level.isPositionEntityTicking(s.at(16, 2, 0)) && level.isPositionEntityTicking(s.at(15, 2, 0)),
                "B must be lazy and A entity ticking");
            Entity pearl = EntityTypes.ENDER_PEARL.create(level, EntitySpawnReason.COMMAND);
            pearl.setPos(s.x0 + 16.5, s.y0 + 2.4, s.z0 + 0.5);
            level.addFreshEntity(pearl);
            helper.runAfterDelay(30, () -> { // the water placed above settles first (its own fluid ticks are not the machine's)
                helper.assertTrue(pearl.position().x == s.x0 + 16.5 && pearl.position().y == s.y0 + 2.4, "the pearl moved in B: B is not lazy " + pearl.position());
                Recording[] out = new Recording[1];
                String[] failed = new String[1];
                try {
                    MachineRecorder.start(level, s.at(11, 1, -6), LIMIT, PathSettings.kick, new MachineRecorder.Listener() {
                        @Override
                        public void done(Recording recording) {
                            out[0] = recording;
                        }

                        @Override
                        public void failed(String message) {
                            failed[0] = message;
                        }
                    });
                } catch (MachineRecorder.RejectedException e) {
                    helper.fail("recording rejected: " + e.getMessage());
                    return;
                }
                waitIdle(helper, () -> {
                    boolean more = false;
                    try {
                        check(helper, level, out[0], failed[0], release);
                        if (!release) {
                            more = true;
                            loadingChange(helper, level, out[0], a, b, pearl);
                        } else {
                            helper.succeed();
                        }
                    } finally {
                        if (!more) {
                            pearl.discard();
                            level.setChunkForced(a.x(), a.z(), false);
                        }
                    }
                });
            });
        })));
    }

    private static void build(Site s, boolean release) {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        for (int x = 8; x <= 19; x++) {
            for (int z = -17; z <= 7; z++) {
                for (int y = -1; y <= 4; y++) {
                    s.set(x, y, z, y == -1 ? stone : air);
                }
            }
        }
        // the TNT row (x 12), the dust line on stone beside it (x 11), two rows of water sources (x 13, 14), walls
        for (int z = -6; z <= 5; z++) {
            s.set(15, 0, z, stone);
            for (int x = 11; x <= 15; x++) {
                if (z == -6 || z == 5) {
                    s.set(x, 0, z, stone);
                }
            }
        }
        for (int z = -5; z <= 4; z++) {
            s.set(11, 0, z, stone);
            s.set(11, 1, z, Blocks.REDSTONE_WIRE.defaultBlockState());
            s.set(12, 0, z, Blocks.TNT.defaultBlockState());
            s.set(13, 0, z, Blocks.WATER.defaultBlockState());
            s.set(14, 0, z, Blocks.WATER.defaultBlockState());
        }
        // the lever on stone S0 (11,0,-6): dust (11,1,-5) south of it, the repeater line north of it
        s.set(11, 1, -6, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        if (release) {
            for (int z = -7; z >= -14; z--) {
                s.set(11, 0, z, stone);
                s.set(11, 1, z, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.SOUTH).setValue(RepeaterBlock.DELAY, 4));
            }
            for (int x = 11; x <= 17; x++) {
                s.set(x, 0, -15, stone);
                s.set(x, 1, -15, Blocks.REDSTONE_WIRE.defaultBlockState());
            }
            for (int z = -14; z <= -1; z++) {
                s.set(17, 0, z, stone);
                s.set(17, 1, z, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.NORTH).setValue(RepeaterBlock.DELAY, 1));
            }
            s.set(17, 1, 0, stone); // strongly powered by the last repeater: powers the piston above it
            s.set(17, 2, 0, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST));
        }
    }

    private static void waitIdle(GameTestHelper helper, Runnable then) {
        if (MachineRecorder.current() == null) {
            then.run();
        } else if (TestSupport.overRealTime(helper)) {
            helper.fail("recording did not finish");
        } else {
            helper.runAfterDelay(1, () -> waitIdle(helper, then));
        }
    }

    private static void check(GameTestHelper helper, ServerLevel level, Recording rec, String failed, boolean release) {
        helper.assertTrue(rec != null, "no recording: " + failed);
        LOG.info("[ghostpath] frozen ({}): recording {} ticks:\n{}", release ? "release" : "no release", rec.ticks(), String.join("\n", rec.summary()));
        helper.assertTrue(!rec.limitHit(), "the run hit the tick limit: " + rec.summary());
        helper.assertTrue(rec.tntSpawned() == 10, "TNT spawned " + rec.tntSpawned());
        List<CauseTracker.FlightPoint> pearl = rec.flightPoints().stream().filter(p -> p.type().equals("minecraft:ender_pearl")).toList();
        helper.assertTrue(!pearl.isEmpty(), "the pearl is not in the recording");
        List<CauseTracker.FlightPoint> frozen = pearl.stream().filter(p -> p.frozen() != null).toList();
        List<CauseTracker.FlightNote> pushes = rec.flightNotes().stream().filter(n -> n.what().startsWith(CauseTracker.EXPLOSION_NOTE)).toList();
        helper.assertTrue(pushes.size() == 10, "explosion pushes on the frozen pearl: " + pushes);
        CauseTracker.FlightPoint lastFrozen = frozen.getLast();
        double speed = Math.sqrt(lastFrozen.vx() * lastFrozen.vx() + lastFrozen.vy() * lastFrozen.vy() + lastFrozen.vz() * lastFrozen.vz());
        helper.assertTrue(speed > 0.5, "no velocity built up while frozen: " + speed);
        PathSnapshot snap = PathBuilder.of(1, rec);
        PathSnapshot.Line line = snap.lines().stream().filter(l -> l.type().equals("minecraft:ender_pearl")).findFirst().orElse(null);
        helper.assertTrue(line != null, "no pearl line");
        helper.assertTrue(line.stasis().size() == 1, "stretches: " + line.stasis().size());
        PathSnapshot.Stasis st = line.stasis().getFirst();
        helper.assertTrue(st.pushes().size() == 10, "pushes in the stretch: " + st.pushes());
        List<String> panel = PanelText.ghost(line, st.point(), 0, "recorded");
        LOG.info("[ghostpath] frozen panel:\n{}\nend {}", String.join("\n", panel), PathSnapshot.endText(line));
        helper.assertTrue(panel.stream().anyMatch(t -> t.startsWith("FROZEN")) && panel.stream().anyMatch(t -> t.contains("< explosion #1")),
            "panel at the freeze point: " + panel);
        if (release) {
            helper.assertTrue(st.resumed(), "the pearl did not tick again");
            helper.assertTrue(st.resume() == st.point() + 1 && line.movedWhileFrozen(st.resume()), "the push is not a move while frozen: resume " + st.resume() + " point " + st.point());
            double rx = line.xyz()[3 * st.resume()];
            helper.assertTrue(Math.floor(rx) < Math.floor(line.xyz()[3 * st.point()]), "the push did not move it west into A: " + rx);
            helper.assertTrue(line.points() > st.resume() + 1, "the path does not go on after the push");
            helper.assertTrue(!line.end().reason().startsWith("frozen"), "the pearl ends frozen: " + line.end());
        } else {
            helper.assertTrue(!st.resumed() && st.resume() == -1, "stretch: resumed " + st.resumed() + " resume " + st.resume());
            helper.assertTrue(line.end().reason().equals("frozen: lazy chunk"), "end: " + line.end());
            helper.assertTrue(rec.ticks() < 140, "the run did not end when the boosters were done: " + rec.ticks() + " ticks");
            helper.assertTrue(rec.summary().stream().anyMatch(t -> t.startsWith("Frozen:") && t.contains("ender_pearl (lazy chunk)")), "summary: " + rec.summary());
        }
        GhostCheck.Result r = GhostCheck.run(level, rec);
        LOG.info("[ghostpath] frozen ghost check:\n{}", String.join("\n", r.lines()));
        helper.assertTrue(r.ran(), "ghost check did not run: " + r.note());
        helper.assertTrue(r.exact(), "ghost check not exact:\n" + String.join("\n", r.lines()));
        long frozenTraces = rec.entityTrace().stream().filter(t -> t.phase().equals("frozen")).count();
        helper.assertTrue(frozenTraces == frozen.size(), "frozen trace entries " + frozenTraces + " vs points " + frozen.size());
    }

    /**
     * "chunk loading changed since recording": the machine is watched; a forced rerun with the levels as recorded has no such note; then
     * B is forced (entity ticking, as if a player came near) and the next rerun's chat line says that B changed.
     */
    private static void loadingChange(GameTestHelper helper, ServerLevel level, Recording rec, ChunkPos a, ChunkPos b, Entity pearl) {
        List<String> chat = new java.util.concurrent.CopyOnWriteArrayList<>();
        obsidivision.ghostpath.watch.LiveWatch.watch(rec, chat::add);
        Runnable cleanup = () -> {
            obsidivision.ghostpath.watch.LiveWatch.stopAll();
            pearl.discard();
            level.setChunkForced(a.x(), a.z(), false);
            level.setChunkForced(b.x(), b.z(), false);
        };
        obsidivision.ghostpath.watch.LiveWatch.rerun(rec.trigger());
        waitFor(helper, () -> chat.stream().anyMatch(l -> l.startsWith("rerun:")), cleanup, () -> {
            String first = chat.stream().filter(l -> l.startsWith("rerun:")).findFirst().orElseThrow();
            LOG.info("[ghostpath] frozen: rerun with the recorded levels: {}", first);
            if (first.contains("leave the area where entities tick")) {
                cleanup.run();
                helper.fail("a pearl the ghost froze too is marked as beyond the real ticking area: " + first);
                return;
            }
            if (first.contains("chunk loading changed")) {
                cleanup.run();
                helper.fail("a rerun with unchanged levels says the loading changed: " + first);
                return;
            }
            chat.clear();
            level.setChunkForced(b.x(), b.z(), true);
            waitFor(helper, () -> level.isPositionEntityTicking(new BlockPos(b.getMiddleBlockX(), level.getMinY() + 1, b.getMiddleBlockZ())), cleanup, () -> {
                obsidivision.ghostpath.watch.LiveWatch.rerun(rec.trigger());
                waitFor(helper, () -> chat.stream().anyMatch(l -> l.startsWith("rerun:")), cleanup, () -> {
                    String second = chat.stream().filter(l -> l.startsWith("rerun:")).findFirst().orElseThrow();
                    LOG.info("[ghostpath] frozen: rerun after B became entity ticking: {}", second);
                    cleanup.run();
                    helper.assertTrue(second.contains("(chunk loading changed since recording: ") && second.contains("chunk " + b.x() + " " + b.z() + ": block ticking -> entity ticking"),
                        "no loading note: " + second);
                    helper.succeed();
                });
            });
        });
    }

    private static void waitFor(GameTestHelper helper, java.util.function.BooleanSupplier cond, Runnable cleanup, Runnable then) {
        if (cond.getAsBoolean()) {
            then.run();
        } else if (TestSupport.overRealTime(helper)) {
            cleanup.run();
            helper.fail("timed out; watches: " + obsidivision.ghostpath.watch.LiveWatch.list());
        } else {
            helper.runAfterDelay(1, () -> waitFor(helper, cond, cleanup, then));
        }
    }
}
