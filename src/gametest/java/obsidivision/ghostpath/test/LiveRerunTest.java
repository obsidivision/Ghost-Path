package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.record.RecordingStore;
import obsidivision.ghostpath.watch.Footprint;
import obsidivision.ghostpath.watch.LiveWatch;
import obsidivision.ghostpath.watch.Prediction;
import obsidivision.ghostpath.watch.Watched;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step 4: live rerun. The machine, through the real {@code /path machine} command:
 * <pre>
 *   lever on stone S0 (0,2,2) -> repeater R1 (1,2,2, delay 4) -> stone W (2,2,2) -> piston P1 (2,2,3) facing west
 *   P1's head goes into C (1,2,3); an observer (1,2,4) faces north and watches C; TNT (1,2,5) is behind the observer.
 * </pre>
 * Slow path: P1 extends 8 ticks after the lever, the observer sees C change, the TNT is lit about 10 ticks in. The tests
 * add a piston P2 (0,2,3) next to S0 and C, facing east: S0 powers it at once, its head enters C first, the observer fires
 * early, the TNT is lit about 3 ticks in. P2 is a real block change at a footprint position (S0's neighbour, read when the
 * lever changed S0).
 */
public class LiveRerunTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    private static final BlockPos S0 = new BlockPos(0, 2, 2);
    static final BlockPos LEVER = new BlockPos(0, 3, 2);
    static final BlockPos P2 = new BlockPos(0, 2, 3);
    static final BlockPos C = new BlockPos(1, 2, 3);
    static final BlockPos TNT = new BlockPos(1, 2, 5);
    static final BlockPos P1 = new BlockPos(2, 2, 3);
    static final List<BlockPos> CORNERS = List.of(new BlockPos(-6, 2, -6), new BlockPos(20, 2, -6), new BlockPos(-6, 2, 12), new BlockPos(20, 2, 12), new BlockPos(2, 2, 2));

    // ---- helpers ----

    static void build(GameTestHelper helper) {
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        for (int x = -1; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        helper.setBlock(S0, Blocks.STONE);
        helper.setBlock(LEVER, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, 4));
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.STONE);
        helper.setBlock(P1, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST));
        helper.setBlock(new BlockPos(1, 2, 4), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.NORTH));
        helper.setBlock(TNT, Blocks.TNT);
    }

    static void placeP2(GameTestHelper helper) {
        helper.setBlock(P2, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
    }

    static void whenTicking(GameTestHelper helper, List<BlockPos> rel, Runnable then, int waited) {
        boolean ready = true;
        for (BlockPos r : rel) {
            ready &= helper.getLevel().isPositionEntityTicking(helper.absolutePos(r));
        }
        ready &= TestSupport.forcedChunksTicking(helper);
        if (ready) {
            LOG.info("[ghostpath] machine area ready after {} ticks; other tests' entities removed from the forced chunks: {}", waited, TestSupport.clearForeignEntities(helper));
            then.run();
        } else if (TestSupport.overRealTime(helper)) {
            helper.fail("machine area not entity-ticking within the real-time limit (" + TestSupport.REAL_TIME_LIMIT_S + " s)");
        } else {
            helper.runAfterDelay(1, () -> whenTicking(helper, rel, then, waited + 1));
        }
    }

    /** Polls once per tick until {@code cond}; {@code each} runs every tick before the check; {@code then} runs once. */
    static void waitFor(GameTestHelper helper, String what, int limit, Runnable each, BooleanSupplier cond, Runnable then) {
        Runnable once = TestSupport.once(then);
        poll(helper, what, limit, each, cond, once, 0);
    }

    private static void poll(GameTestHelper helper, String what, int limit, Runnable each, BooleanSupplier cond, Runnable then, int waited) {
        try {
            each.run();
        } catch (RuntimeException e) {
            finish(helper); // a failed test must not leave its watch or its ghost behind for the next batch
            throw e;
        }
        if (cond.getAsBoolean()) {
            then.run();
        } else if (waited > limit && TestSupport.overRealTime(helper)) {
            finish(helper);
            helper.fail("timed out waiting for " + what + " (" + limit + " ticks); watch: " + LiveWatch.list());
        } else {
            helper.runAfterDelay(1, () -> poll(helper, what, limit, each, cond, then, waited + 1));
        }
    }

    static final class Capture implements net.minecraft.commands.CommandSource {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(net.minecraft.network.chat.Component message) {
            this.lines.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        boolean has(String text) {
            return this.lines.stream().anyMatch(l -> l.contains(text));
        }
    }

    static void command(ServerLevel level, Capture capture, String command) {
        CommandSourceStack source = level.getServer().createCommandSourceStack().withSource(capture).withLevel(level).withPermission(LevelBasedPermissionSet.GAMEMASTER);
        level.getServer().getCommands().performPrefixedCommand(source, command);
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper helper, ServerLevel level) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(-1, 1, 0)), helper.absolutePos(new BlockPos(8, 4, 8)))) {
            out.put(p.immutable(), level.getBlockState(p));
        }
        return out;
    }

    /** Fails if the real machine has fired or moved: lever off, no lit TNT, nothing extended, C empty. */
    private static void assertNotFired(GameTestHelper helper, ServerLevel level) {
        BlockPos lever = helper.absolutePos(LEVER);
        helper.assertTrue(level.getBlockState(lever).is(Blocks.LEVER) && !level.getBlockState(lever).getValue(LeverBlock.POWERED), "the real lever was pulled");
        helper.assertTrue(level.getBlockState(helper.absolutePos(TNT)).is(Blocks.TNT), "the real TNT block is gone");
        helper.assertTrue(level.getEntitiesOfClass(PrimedTnt.class, helper.getBounds().inflate(24)).isEmpty(), "a real TNT was lit");
        helper.assertTrue(!level.getBlockState(helper.absolutePos(P1)).getValue(PistonBaseBlock.EXTENDED), "the real piston P1 extended");
        BlockState p2 = level.getBlockState(helper.absolutePos(P2));
        helper.assertTrue(!p2.is(Blocks.PISTON) || !p2.getValue(PistonBaseBlock.EXTENDED), "the real piston P2 extended");
        helper.assertTrue(level.getBlockState(helper.absolutePos(C)).isAir(), "C is not empty in the real world: " + level.getBlockState(helper.absolutePos(C)));
    }

    /** Runs {@code /path machine} on the lever and hands over the recording and the watch once the recording is over. */
    private static void recordAndWatch(GameTestHelper helper, ServerLevel level, Capture capture, java.util.function.BiConsumer<Recording, Watched> then) {
        recordAndWatch(helper, level, capture, LEVER, then);
    }

    static void recordAndWatch(GameTestHelper helper, ServerLevel level, Capture capture, BlockPos relLever, java.util.function.BiConsumer<Recording, Watched> then) {
        BlockPos lever = helper.absolutePos(relLever);
        PathSettings.kick = true;
        PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
        PathSettings.watchAfterMachine = true;
        LiveWatch.stopAll();
        RecordingStore.clear();
        TestSupport.whenSavesFree(helper, () -> {
            command(level, capture, "path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
            waitFor(helper, "the recording", 6000, () -> {}, () -> MachineRecorder.current() == null, () -> {
                Recording rec = RecordingStore.latest();
                helper.assertTrue(rec != null && rec.trigger().equals(lever), "no recording of the lever: " + capture.lines);
                helper.assertTrue(capture.has("Watching"), "the command did not start a watch: " + capture.lines);
                helper.assertTrue(LiveWatch.watches().size() == 1, "watches: " + LiveWatch.watches().size());
                then.accept(rec, LiveWatch.watches().getFirst());
            });
        });
    }

    static void finish(GameTestHelper helper) {
        LiveWatch.stopAll();
        PathSettings.rerunBudgetMs = 10;
        PathSettings.watchAfterMachine = false;
        CauseTrackingTest.forceChunks(helper, false);
    }

    // ---- the tests ----

    /**
     * The key test: a piston added next to an observer in a recorded machine. The watch notices the real change, waits for
     * 10 quiet ticks, reruns in a ghost, spread over several server ticks (budget 1 ms): the TNT spawn tick moves from about
     * 10 to about 3 and the chat line says so. Meanwhile the real machine never fires (checked every tick). The rerun's
     * footprint replaces the watched one. A change outside the footprint then starts no rerun; one inside an entity area
     * does.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:live_rerun_piston")
    public void pistonNextToObserverChangesTheRerun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.rerunBudgetMs = 1;
        build(helper);
        Capture chat = new Capture();
        whenTicking(helper, CORNERS, TestSupport.once(() -> recordAndWatch(helper, level, chat, (rec, watch) -> {
            try {
                List<Integer> before = Prediction.tntSpawnTicks(rec.events(), rec.entityTrace());
                helper.assertTrue(before.size() == 1, "the recording lit " + before.size() + " TNT");
                BlockPos p2 = helper.absolutePos(P2);
                helper.assertTrue(watch.footprint().hit(p2), "P2's position is not in the footprint: " + watch.footprint());
                helper.assertTrue(watch.footprint().hit(helper.absolutePos(C)), "C is not in the footprint");
                helper.assertTrue(watch.reruns() == 0 && !watch.dirty() && LiveWatch.running() == null, "the watch started dirty or running: " + LiveWatch.list());
                Footprint oldFootprint = watch.footprint();
                Map<BlockPos, BlockState> world = snapshot(helper, level);
                LOG.info("[ghostpath] live rerun: recorded {} ticks, TNT lit at t+{}; footprint {}", rec.ticks(), before.getFirst(), oldFootprint);
                placeP2(helper);
                world.put(p2, level.getBlockState(p2));
                int[] busy = {0};
                int[] quiet = {0};
                long placed = level.getGameTime();
                long[] startedAt = {-1};
                waitFor(helper, "the first rerun", 60000, () -> {
                    assertNotFired(helper, level);
                    if (LiveWatch.running() != null) {
                        busy[0]++;
                        if (startedAt[0] < 0) {
                            startedAt[0] = level.getGameTime();
                        }
                    }
                }, () -> watch.reruns() >= 1, () -> {
                    try {
                        Prediction p = watch.latest();
                        helper.assertTrue(p != null, "no prediction");
                        helper.assertTrue(startedAt[0] - placed >= 10, "the rerun started after only " + (startedAt[0] - placed) + " ticks");
                        helper.assertTrue(p.tntSpawned() == 1 && p.tntSpawnTicks().size() == 1, "the rerun lit " + p.tntSpawned() + " TNT: " + p.tntSpawnTicks());
                        int after = p.tntSpawnTicks().getFirst();
                        LOG.info("[ghostpath] live rerun: TNT lit at t+{} (was t+{}); {} ticks over {} server ticks, {} ms, copy {} ms, longest slice {} ms",
                            after, before.getFirst(), p.ticks(), p.serverTicks(), p.millis(), p.copyMillis(), p.maxSliceMillis());
                        helper.assertTrue(after < before.getFirst(), "the TNT spawn tick did not move earlier: t+" + before.getFirst() + " -> t+" + after);
                        helper.assertTrue(p.serverTicks() >= 5 && busy[0] >= 5, "the run was not spread over server ticks: " + p.serverTicks() + " / " + busy[0]);
                        helper.assertTrue(p.trace().stream().anyMatch(t -> t.type().equals("minecraft:tnt")), "no TNT state in the prediction");
                        String line = chat.lines.stream().filter(l -> l.startsWith("rerun:")).findFirst().orElse(null);
                        LOG.info("[ghostpath] chat: {}", line);
                        helper.assertTrue(line != null && line.contains(p.ticks() + " ticks, 1 TNT spawned") && line.contains("(ghost created in ") && line.contains(", changed since last: ")
                            && line.contains("TNT #0 spawn t+" + before.getFirst() + " -> t+" + after), "chat line: " + line);
                        helper.assertTrue(watch.footprint() != oldFootprint && watch.footprint() == p.footprint() && watch.footprint().containsPosition(p2),
                            "the footprint was not replaced by the rerun's");
                        assertNotFired(helper, level);
                        // the real world is as it was, plus the new piston
                        Map<BlockPos, BlockState> now = snapshot(helper, level);
                        helper.assertTrue(now.equals(world), "the real world changed during the rerun");
                        helper.assertTrue(!rec.events().isEmpty() && rec.ticks() > 0, "the recording changed");
                        outsideAndArea(helper, level, watch, chat, p);
                    } catch (RuntimeException e) {
                        finish(helper);
                        throw e;
                    }
                });
            } catch (RuntimeException e) {
                finish(helper);
                throw e;
            }
        })), 0);
    }

    private static void outsideAndArea(GameTestHelper helper, ServerLevel level, Watched watch, Capture chat, Prediction p) {
        // a change outside the footprint: no rerun. (The explosion reads a large area: search for an air block nothing touched.)
        BlockPos outside = null;
        for (int y = 30; y >= 2 && outside == null; y -= 4) {
            for (int x = -6; x <= 20 && outside == null; x += 2) {
                for (int z = -6; z <= 14 && outside == null; z += 2) {
                    BlockPos q = helper.absolutePos(new BlockPos(x, y, z));
                    if (watch.footprint().cause(q, PathSettings.watchMargin) == null && level.getBlockState(q).isAir() && level.isLoaded(q)) {
                        outside = q;
                    }
                }
            }
        }
        helper.assertTrue(outside != null, "no air block outside the footprint (" + watch.footprint() + ")");
        final BlockPos outsidePos = outside;
        int reruns = watch.reruns();
        int changes = watch.changes();
        level.setBlock(outsidePos, Blocks.GLASS.defaultBlockState(), 3);
        TestSupport.later(helper, 80, () -> {
            try {
                helper.assertTrue(watch.reruns() == reruns && watch.changes() == changes && !watch.dirty() && LiveWatch.running() == null,
                    "a change outside the footprint started a rerun: " + LiveWatch.list());
                // synthetic: Footprint.hit for a box, a column and a position
                Footprint fp = new Footprint(List.of(new BlockPos(100, 64, 100)), List.of(new CauseTracker.Column(200, 200)),
                    List.of(new AABB(300.2, 64.5, 300.2, 302.7, 66.1, 302.7)));
                helper.assertTrue(fp.hit(new BlockPos(100, 64, 100)) && !fp.hit(new BlockPos(100, 65, 100)), "position hit");
                helper.assertTrue(fp.hit(new BlockPos(200, 5, 200)) && !fp.hit(new BlockPos(201, 5, 200)), "column hit");
                helper.assertTrue(fp.hit(new BlockPos(301, 65, 301)) && fp.hit(new BlockPos(302, 66, 302)) && !fp.hit(new BlockPos(303, 65, 301))
                    && !fp.hit(new BlockPos(301, 67, 301)) && !fp.hit(new BlockPos(299, 65, 301)), "area hit");
                // live: a block inside an entity area that is not a footprint position and not in a read column
                BlockPos inArea = null;
                search:
                for (AABB box : watch.footprint().areas()) {
                    for (BlockPos q : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
                        if (!watch.footprint().containsPosition(q) && !watch.footprint().columns().contains(new CauseTracker.Column(q.getX(), q.getZ()))
                            && level.getBlockState(q).isAir() && !q.equals(outsidePos)) {
                            inArea = q.immutable();
                            break search;
                        }
                    }
                }
                LOG.info("[ghostpath] live rerun: {} entity areas; a position only in an area: {}", watch.footprint().areas().size(), inArea);
                if (inArea == null) {
                    helper.assertTrue(false, "no air block that is in an entity area only (areas: " + watch.footprint().areas().size() + ")");
                }
                int before = watch.reruns();
                level.setBlock(inArea, Blocks.GLASS.defaultBlockState(), 3);
                helper.assertTrue(watch.dirty(), "a change inside an entity area was not seen");
                waitFor(helper, "the rerun after the area change", 60000, () -> assertNotFired(helper, level), () -> watch.reruns() > before, () -> {
                    LOG.info("[ghostpath] chat: {}", chat.lines.get(chat.lines.size() - 1));
                    finish(helper);
                    helper.succeed();
                });
            } catch (RuntimeException e) {
                finish(helper);
                throw e;
            }
        });
    }

    /**
     * Only rerun while the real machine is idle, and a new change cancels a run in progress. A scheduled tick at a footprint
     * position (S0, 80 ticks out) makes the machine busy: the change (P2) is seen, the quiet period passes, and nothing
     * starts until the tick has run. The run (budget 1 ms) is then cancelled by a second change (P2 removed again) and starts
     * over: one completed rerun, one cancelled, and the prediction is the original machine's (TNT at the recording's tick).
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:live_rerun_idle")
    public void rerunWaitsForIdleAndRestartsOnANewChange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.rerunBudgetMs = 1;
        build(helper);
        Capture chat = new Capture();
        whenTicking(helper, CORNERS, TestSupport.once(() -> recordAndWatch(helper, level, chat, (rec, watch) -> {
            try {
                BlockPos s0 = helper.absolutePos(S0);
                level.scheduleTick(s0, Blocks.STONE, 80);
                long scheduled = level.getGameTime();
                placeP2(helper);
                boolean[] sawSettle = {false};
                long[] startedAt = {-1};
                waitFor(helper, "the run to start", 60000, () -> {
                    assertNotFired(helper, level);
                    if (watch.status().contains("settle")) {
                        sawSettle[0] = true;
                    }
                    if (LiveWatch.running() != null && startedAt[0] < 0) {
                        startedAt[0] = level.getGameTime();
                    }
                    if (startedAt[0] < 0) {
                        helper.assertTrue(level.getGameTime() - scheduled < 80 || !level.getBlockTicks().hasScheduledTick(s0, Blocks.STONE),
                            "still waiting after the tick ran");
                    }
                }, () -> startedAt[0] >= 0, () -> {
                    try {
                        LOG.info("[ghostpath] idle test: tick scheduled at {}, run started at {}; saw 'settle': {}", scheduled, startedAt[0], sawSettle[0]);
                        helper.assertTrue(sawSettle[0], "the watch never reported waiting for the machine to settle");
                        helper.assertTrue(startedAt[0] - scheduled >= 80, "the rerun started " + (startedAt[0] - scheduled) + " ticks after the tick was scheduled, before it ran");
                        // cancel it in the run phase
                        waitFor(helper, "the run phase", 60000, () -> {}, () -> LiveWatch.running() != null && LiveWatch.running().startsWith("running"), () -> {
                            try {
                                int cancelled = watch.cancelled();
                                helper.setBlock(P2, Blocks.AIR); // not C: a real change there would fire the real observer
                                helper.assertTrue(LiveWatch.running() == null, "the run was not cancelled by a footprint change");
                                helper.assertTrue(watch.cancelled() == cancelled + 1 && watch.dirty() && watch.reruns() == 0, "cancelled " + watch.cancelled() + ", reruns " + watch.reruns());
                                waitFor(helper, "the second run", 60000, () -> assertNotFired(helper, level), () -> watch.reruns() >= 1, () -> {
                                    try {
                                        Prediction p = watch.latest();
                                        LOG.info("[ghostpath] chat: {}", chat.lines.get(chat.lines.size() - 1));
                                        List<Integer> orig = Prediction.tntSpawnTicks(rec.events(), rec.entityTrace());
                                        helper.assertTrue(watch.reruns() == 1 && watch.cancelled() == 1 && p.tntSpawnTicks().equals(orig),
                                            "reruns " + watch.reruns() + ", cancelled " + watch.cancelled() + ", TNT " + p.tntSpawnTicks() + " (recording " + orig + ")");
                                        helper.assertTrue(chat.lines.stream().filter(l -> l.startsWith("rerun:")).count() == 1, "chat: " + chat.lines);
                                        assertNotFired(helper, level);
                                        // /path watch list and stop
                                        Capture out = new Capture();
                                        command(level, out, "path watch list");
                                        helper.assertTrue(out.has("reruns 1") && out.has("cancelled 1"), "watch list: " + out.lines);
                                        command(level, out, "path watch stop");
                                        helper.assertTrue(out.has("Stopped 1 watch") && LiveWatch.watches().isEmpty(), "watch stop: " + out.lines);
                                        finish(helper);
                                        helper.succeed();
                                    } catch (RuntimeException e) {
                                        finish(helper);
                                        throw e;
                                    }
                                });
                            } catch (RuntimeException e) {
                                finish(helper);
                                throw e;
                            }
                        });
                    } catch (RuntimeException e) {
                        finish(helper);
                        throw e;
                    }
                });
            } catch (RuntimeException e) {
                finish(helper);
                throw e;
            }
        })), 0);
    }

    private static void forceWide(GameTestHelper helper, boolean forced) {
        BlockPos min = helper.absolutePos(new BlockPos(-8, 0, -8));
        BlockPos max = helper.absolutePos(new BlockPos(130, 0, 14));
        for (int cx = Math.min(min.getX(), max.getX()) >> 4; cx <= Math.max(min.getX(), max.getX()) >> 4; cx++) {
            for (int cz = Math.min(min.getZ(), max.getZ()) >> 4; cz <= Math.max(min.getZ(), max.getZ()) >> 4; cz++) {
                helper.getLevel().setChunkForced(cx, cz, forced);
            }
        }
    }

    /**
     * A chain that reaches beyond the copied chunks: the recorded machine is extended, in the real world, by a line of 80
     * repeaters east of W (beyond what a footprint-plus-one-chunk area covers). The first rerun's ghost has void there, the
     * chain stops at the edge of the copied area and its footprint shows chunks outside it; the rerun is repeated once with a
     * larger area, and that run follows the line to its end. One completed rerun, one widening, nothing left beyond.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:live_rerun_widen")
    public void chainBeyondTheCopiedChunksIsRerunWithALargerArea(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.rerunBudgetMs = 5;
        build(helper);
        forceWide(helper, true);
        for (int x = 9; x <= 90; x++) {
            for (int z = 0; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        Capture chat = new Capture();
        List<BlockPos> corners = new ArrayList<>(CORNERS);
        corners.add(new BlockPos(125, 2, 12));
        corners.add(new BlockPos(100, 2, -6));
        whenTicking(helper, corners, TestSupport.once(() -> recordAndWatch(helper, level, chat, (rec, watch) -> {
            try {
                for (int x = 82; x >= 3; x--) {
                    helper.setBlock(new BlockPos(x, 2, 2), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, 1));
                }
                helper.assertTrue(watch.dirty(), "the repeater next to W was not seen as a footprint change");
                waitFor(helper, "the rerun", 60000, () -> assertNotFired(helper, level), () -> watch.reruns() >= 1, () -> {
                    try {
                        Prediction p = watch.latest();
                        String line = chat.lines.stream().filter(l -> l.startsWith("rerun:")).findFirst().orElse("-");
                        LOG.info("[ghostpath] widen test: {} ticks, area {}..{}, widenings {}, beyond {}; chat: {}", p.ticks(), p.copiedMin(), p.copiedMax(),
                            watch.widenings(), p.beyond().size(), line);
                        helper.assertTrue(watch.reruns() == 1 && watch.widenings() == 1 && p.widened(), "reruns " + watch.reruns() + ", widenings " + watch.widenings());
                        helper.assertTrue(p.beyond().isEmpty(), "chain still beyond the area: " + p.beyond());
                        helper.assertTrue(p.footprint().containsPosition(helper.absolutePos(new BlockPos(82, 2, 2))), "the ghost did not follow the line to its end");
                        helper.assertTrue(p.ticks() >= 160, "the ghost run was too short for 80 repeaters: " + p.ticks());
                        helper.assertTrue(line.contains("widened"), "chat line: " + line);
                        assertNotFired(helper, level);
                        forceWide(helper, false);
                        finish(helper);
                        helper.succeed();
                    } catch (RuntimeException e) {
                        forceWide(helper, false);
                        finish(helper);
                        throw e;
                    }
                });
            } catch (RuntimeException e) {
                forceWide(helper, false);
                finish(helper);
                throw e;
            }
        })), 0);
    }

    /**
     * A new recording (or a ghost check) while a rerun is in progress cancels the rerun (one ghost at a time; the ghost holds
     * the autosave guard): the command works, the new recording includes the piston, and its watch replaces the old one.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:live_rerun_footprint")
    public void aRecordingCancelsARerunInProgress(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.rerunBudgetMs = 1;
        build(helper);
        Capture chat = new Capture();
        whenTicking(helper, CORNERS, TestSupport.once(() -> recordAndWatch(helper, level, chat, (rec, watch) -> {
            try {
                List<Integer> original = Prediction.tntSpawnTicks(rec.events(), rec.entityTrace());
                placeP2(helper);
                waitFor(helper, "the run phase", 60000, () -> {}, () -> LiveWatch.running() != null && LiveWatch.running().startsWith("running"), () -> {
                    try {
                        Capture second = new Capture();
                        BlockPos lever = helper.absolutePos(LEVER);
                        command(level, second, "path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
                        helper.assertTrue(second.lines.stream().noneMatch(l -> l.contains("already running") || l.contains("held")), "command refused: " + second.lines);
                        helper.assertTrue(LiveWatch.running() == null && watch.cancelled() == 1, "the rerun was not cancelled: " + LiveWatch.list());
                        waitFor(helper, "the second recording", 6000, () -> {}, () -> MachineRecorder.current() == null, () -> {
                            try {
                                Recording rec2 = RecordingStore.latest();
                                List<Integer> now = Prediction.tntSpawnTicks(rec2.events(), rec2.entityTrace());
                                LOG.info("[ghostpath] second recording: TNT lit at {} (first recording {}); watches {}", now, original, LiveWatch.list());
                                helper.assertTrue(rec2 != rec && now.size() == 1 && now.getFirst() < original.getFirst(), "second recording TNT " + now + " vs " + original);
                                helper.assertTrue(LiveWatch.watches().size() == 1 && LiveWatch.watches().getFirst() != watch && !LiveWatch.watches().getFirst().dirty(),
                                    "watches: " + LiveWatch.list());
                                helper.assertTrue(second.has("Watching"), "second command output: " + second.lines);
                                finish(helper);
                                helper.succeed();
                            } catch (RuntimeException e) {
                                finish(helper);
                                throw e;
                            }
                        });
                    } catch (RuntimeException e) {
                        finish(helper);
                        throw e;
                    }
                });
            } catch (RuntimeException e) {
                finish(helper);
                throw e;
            }
        })), 0);
    }
}
