package obsidivision.ghostpath.test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.ChainEvent;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.record.RecordingStore;
import obsidivision.ghostpath.test.LiveRerunTest.Capture;
import obsidivision.ghostpath.watch.Footprint;
import obsidivision.ghostpath.watch.LiveWatch;
import obsidivision.ghostpath.watch.Prediction;
import obsidivision.ghostpath.watch.Watched;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rerun triggers: the neighbour rule, the margin, {@code /path rerun}, queueing of several watches. The machine M, no explosion
 * (so the footprint is small and cells just outside it exist):
 * <pre>
 *   lever (0,3,2) on stone S0 (0,2,2) powers piston Pa (1,2,2) facing east; Pa pushes stone B (2,2,2) to X (3,2,2).
 * </pre>
 * Written by the chain: the lever, Pa and its head, B's cells, X. The edits of the order tests: an observer O2 above X
 * (watching X), a solid block S2 behind it, piston P3 (pushes a TNT block into a cell next to a redstone block, which lights
 * it) and piston P4 (pushes a stone), both powered through S2.
 */
public class RerunTriggersTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    static final BlockPos LEVER = new BlockPos(0, 3, 2);
    static final BlockPos X = new BlockPos(3, 2, 2);
    static final BlockPos PA = new BlockPos(1, 2, 2);
    static final BlockPos O2 = new BlockPos(3, 3, 2);
    static final BlockPos S2 = new BlockPos(3, 4, 2);
    static final BlockPos P3 = new BlockPos(3, 4, 3);
    static final BlockPos T2 = new BlockPos(3, 4, 4);
    static final BlockPos LANDING = new BlockPos(3, 4, 5);
    static final BlockPos REDSTONE = new BlockPos(4, 4, 5);
    static final BlockPos P4 = new BlockPos(2, 4, 2);
    static final BlockPos Q = new BlockPos(1, 4, 2);
    static final List<BlockPos> CORNERS = List.of(new BlockPos(-6, 2, -6), new BlockPos(16, 2, -6), new BlockPos(-6, 2, 14), new BlockPos(16, 2, 14), new BlockPos(3, 2, 2));

    // ---- helpers ----

    /** The machine M (and a floor). {@code z0} shifts it, so that a second one fits into the same chunk. */
    static void buildM(GameTestHelper helper, int z0) {
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        for (int x = -1; x <= 12; x++) {
            for (int z = 0; z <= 12; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(0, 2, 2 + z0), Blocks.STONE);
        helper.setBlock(LEVER.offset(0, 0, z0), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        helper.setBlock(PA.offset(0, 0, z0), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(2, 2, 2 + z0), Blocks.STONE);
    }

    static void ready(GameTestHelper helper, Runnable then) {
        LiveRerunTest.whenTicking(helper, CORNERS, TestSupport.once(then), 0);
    }

    static void recordM(GameTestHelper helper, ServerLevel level, Capture chat, BiConsumer<Recording, Watched> then) {
        buildM(helper, 0);
        ready(helper, () -> LiveRerunTest.recordAndWatch(helper, level, chat, LEVER, then));
    }

    /** A second {@code /path machine} that keeps the watches already there. */
    static void recordMore(GameTestHelper helper, ServerLevel level, Capture chat, BlockPos relLever, BiConsumer<Recording, Watched> then) {
        BlockPos lever = helper.absolutePos(relLever);
        TestSupport.whenSavesFree(helper, () -> {
            LiveRerunTest.command(level, chat, "path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
            LiveRerunTest.waitFor(helper, "the recording", 6000, () -> { }, () -> MachineRecorder.current() == null, () -> {
                Recording rec = RecordingStore.latest();
                helper.assertTrue(rec != null && rec.trigger().equals(lever), "no recording of the lever: " + chat.lines);
                then.accept(rec, LiveWatch.watches().stream().filter(w -> w.trigger().equals(lever)).findFirst().orElseThrow());
            });
        });
    }

    static void end(GameTestHelper helper) {
        PathSettings.watchMargin = 2;
        LiveRerunTest.finish(helper);
    }

    /** Runs {@code body}; if it throws, the watches and settings are cleaned up first. */
    static void guard(GameTestHelper helper, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            end(helper);
            throw e;
        }
    }

    /** The recording as if its chain had only written: no reads, no entity areas, no heightmap columns (footprint = the written blocks). */
    static Recording writesOnly(Recording r) {
        return new Recording(r.dimension(), r.trigger(), r.seed(), r.kickApplied(), r.startGameTime(), r.ticks(), r.limit(), r.limitHit(), r.pendingAtEnd(),
            r.events(), r.writes(), Map.of(), Set.of(), Set.of(), r.tntSpawned(), r.unseededDrawsNotAllowed(), r.unseededDrawsAllowed(), r.unseededDrawsInexact(),
            r.lateBlockEntities(), r.ticksNotRestored(), r.weatherAtStart(), r.weatherAtEnd(), r.chunksReloaded(), r.entitiesNotRestored(), r.itemDriftTicks(),
            r.sharedRestored(), r.nonChainBlocksLeft(), r.nonChainEntitiesLeft(), r.notFollowed(), r.clocksAtStart(), r.entityTrace(), Map.of(), r.triggerStateAtStart(),
            r.flightPoints(), r.flightNotes(), r.liveProjectiles(), r.notSimulated());
    }

    static void settled(GameTestHelper helper, Watched w, Runnable then) {
        LiveRerunTest.waitFor(helper, "the watch to settle", 60000, () -> { }, () -> !w.dirty() && LiveWatch.running() == null, then);
    }

    /** Fails if the real machine moved or fired: lever off, Pa not extended, X empty, no lit TNT. */
    static void notFired(GameTestHelper helper, ServerLevel level, int z0) {
        BlockState lever = level.getBlockState(helper.absolutePos(LEVER.offset(0, 0, z0)));
        helper.assertTrue(lever.is(Blocks.LEVER) && !lever.getValue(LeverBlock.POWERED), "the real lever was pulled");
        helper.assertTrue(!level.getBlockState(helper.absolutePos(PA.offset(0, 0, z0))).getValue(PistonBaseBlock.EXTENDED), "the real piston extended");
        helper.assertTrue(level.getBlockState(helper.absolutePos(X.offset(0, 0, z0))).isAir(), "X is not empty in the real world");
        helper.assertTrue(level.getEntitiesOfClass(PrimedTnt.class, helper.getBounds().inflate(24)).isEmpty(), "a real TNT was lit");
    }

    static BlockState observerDown() {
        return Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.DOWN);
    }

    static boolean wrote(Prediction p, BlockPos abs) {
        return p.events().stream().anyMatch(e -> e.kind() == ChainEvent.Kind.WRITE && abs.equals(e.pos()));
    }

    // ---- tests ----

    /** The rules on a synthetic footprint: written block, neighbour (6, not diagonal), margin (cube), across chunk and sign boundaries. */
    @GameTest(maxTicks = 100, environment = "ghostpath-gametest:trig_unit")
    public void theRulesOnASyntheticFootprint(GameTestHelper helper) {
        for (BlockPos w : List.of(new BlockPos(100, 64, 100), new BlockPos(15, 64, 0), new BlockPos(-1, 64, -1), new BlockPos(-16, 10, 31))) {
            Footprint fp = new Footprint(List.of(w), List.of(), List.of(), List.of(w));
            helper.assertTrue(fp.cause(w, 0) == Footprint.Cause.FOOTPRINT, "the written block itself");
            for (Direction d : Direction.values()) {
                helper.assertTrue(fp.cause(w.relative(d), 0) == Footprint.Cause.NEIGHBOR, "neighbour " + d + " of " + w + " with margin 0");
            }
            BlockPos diag = w.offset(1, 1, 0);
            helper.assertTrue(fp.cause(diag, 0) == null && fp.cause(diag, 1) == Footprint.Cause.MARGIN, "a diagonal cell is not a neighbour, but within margin 1 of " + w);
            BlockPos two = w.offset(2, 0, 0);
            helper.assertTrue(fp.cause(two, 0) == null && fp.cause(two, 1) == null && fp.cause(two, 2) == Footprint.Cause.MARGIN, "two cells away of " + w);
            BlockPos corner = w.offset(-3, 3, -3);
            helper.assertTrue(fp.cause(corner, 2) == null && fp.cause(corner, 3) == Footprint.Cause.MARGIN && fp.cause(corner, Footprint.MAX_MARGIN) == Footprint.Cause.MARGIN,
                "the margin is a cube: the corner at 3 of " + w);
            helper.assertTrue(fp.cause(w.offset(9, 0, 0), Footprint.MAX_MARGIN) == null, "9 cells away is outside the largest margin");
            helper.assertTrue(fp.cause(w.offset(0, 8, 0), Footprint.MAX_MARGIN) == Footprint.Cause.MARGIN, "8 cells up is inside the largest margin");
        }
        // the footprint (reads) keeps its own cause and wins
        Footprint reads = new Footprint(List.of(new BlockPos(5, 5, 5), new BlockPos(6, 5, 5)), List.of(), List.of(), List.of(new BlockPos(5, 5, 5)));
        helper.assertTrue(reads.cause(new BlockPos(6, 5, 5), 2) == Footprint.Cause.FOOTPRINT, "a read position next to a written one is FOOTPRINT");
        helper.assertTrue(reads.cause(new BlockPos(5, 6, 5), 0) == Footprint.Cause.NEIGHBOR, "an unread neighbour of the written block");
        helper.assertTrue(new Footprint(List.of(), List.of(), List.of()).cause(new BlockPos(1, 2, 3), 8) == null, "no writes: no neighbour, no margin");
        helper.succeed();
    }

    /**
     * Neighbour rule, live: the footprint is cut down to the written blocks (as if the update lookups had not read the neighbours),
     * margin 0. An observer next to X (the block the machine writes) starts a rerun; a block two cells from X does not.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:trig_neighbour")
    public void aNeighbourOfAWrittenBlockStartsARerun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Capture chat = new Capture();
        recordM(helper, level, chat, (rec, full) -> guard(helper, () -> {
            PathSettings.watchMargin = 0;
            Watched w = LiveWatch.watch(writesOnly(rec), line -> chat.lines.add(line));
            BlockPos abs = helper.absolutePos(O2);
            helper.assertTrue(!w.footprint().hit(abs) && w.footprint().cause(abs, 0) == Footprint.Cause.NEIGHBOR, "the observer's cell: " + w.footprint().cause(abs, 0));
            // control: two cells above X is no neighbour, margin 0: nothing
            helper.setBlock(S2, Blocks.STONE);
            helper.assertTrue(!w.dirty() && w.changes() == 0, "a block two cells from X counted with margin 0: " + w.changes());
            helper.setBlock(S2, Blocks.AIR);
            helper.setBlock(O2, observerDown());
            helper.assertTrue(w.dirty() && w.changes() == 1, "the observer next to X was not seen (changes " + w.changes() + ")");
            LiveRerunTest.waitFor(helper, "the rerun", 60000, () -> notFired(helper, level, 0), () -> w.reruns() >= 1, () -> guard(helper, () -> {
                helper.assertTrue(chat.lines.stream().anyMatch(l -> l.startsWith("rerun:")), "no rerun line: " + chat.lines);
                end(helper);
                helper.succeed();
            }));
        }));
    }

    /**
     * Margin, live: the footprint is cut down to the written blocks. Margin 2 (default): a block two cells from X starts a rerun;
     * three cells does not; {@code /path watch margin 3} makes the three-cell block count; air to air does not count; a change
     * outside a loaded chunk is not seen.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:trig_margin")
    public void aBlockWithinTheMarginStartsARerun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Capture chat = new Capture();
        recordM(helper, level, chat, (rec, full) -> guard(helper, () -> {
            helper.assertTrue(PathSettings.watchMargin == 2, "default margin " + PathSettings.watchMargin);
            Watched w = LiveWatch.watch(writesOnly(rec), line -> chat.lines.add(line));
            BlockPos two = X.offset(2, 0, 0);
            BlockPos three = X.offset(3, 0, 0);
            helper.assertTrue(w.footprint().cause(helper.absolutePos(two), 2) == Footprint.Cause.MARGIN && w.footprint().cause(helper.absolutePos(three), 2) == null, "cells");
            // air to air, and a position in an unloaded chunk: nothing counts
            level.setBlock(helper.absolutePos(two), Blocks.CAVE_AIR.defaultBlockState(), 3);
            helper.assertTrue(w.changes() == 0, "cave air over air counted");
            BlockPos far = helper.absolutePos(two).offset(100_000, 0, 0);
            helper.assertTrue(!level.hasChunkAt(far), "the far chunk is loaded");
            LiveWatch.onBlockChanged(level, far, Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState());
            helper.assertTrue(w.changes() == 0, "a change in an unloaded chunk counted");
            helper.setBlock(three, Blocks.STONE);
            helper.assertTrue(w.changes() == 0 && !w.dirty(), "three cells from X counted with margin 2");
            helper.setBlock(three, Blocks.AIR);
            helper.setBlock(two, Blocks.STONE);
            helper.assertTrue(w.changes() == 1 && w.dirty(), "two cells from X was not seen with margin 2");
            LiveRerunTest.waitFor(helper, "the rerun", 60000, () -> notFired(helper, level, 0), () -> w.reruns() >= 1, () -> guard(helper, () -> {
                // the rerun's footprint (reads) replaced the cut one; cut it down again for the command check
                Watched w2 = LiveWatch.watch(writesOnly(rec), line -> chat.lines.add(line));
                LiveRerunTest.command(level, chat, "path watch margin 3");
                helper.assertTrue(PathSettings.watchMargin == 3 && chat.has("Watch margin: 3"), "command: " + chat.lines);
                helper.setBlock(three, Blocks.STONE);
                helper.assertTrue(w2.changes() == 1 && w2.dirty(), "three cells from X was not seen with margin 3");
                LiveRerunTest.command(level, chat, "path watch margin 0");
                helper.setBlock(three.above(5), Blocks.STONE);
                helper.assertTrue(w2.changes() == 1, "a distant block counted");
                end(helper);
                helper.succeed();
            }));
        }));
    }

    private void observerAndPistons(GameTestHelper helper, boolean pistonsFirst) {
        ServerLevel level = helper.getLevel();
        Capture chat = new Capture();
        recordM(helper, level, chat, (rec, watch) -> guard(helper, () -> {
            // the cells of the edit, against the recorded footprint (reported; the numbers go into DESIGN)
            for (BlockPos rel : List.of(O2, S2, P3, T2, LANDING, REDSTONE, P4, Q)) {
                BlockPos abs = helper.absolutePos(rel);
                LOG.info("[ghostpath] triggers: cell {} is {} in the recorded footprint (cause {})", rel.toShortString(), watch.footprint().hit(abs) ? "" : "NOT", watch.footprint().cause(abs, PathSettings.watchMargin));
            }
            for (BlockPos rel : List.of(P3, T2, LANDING, REDSTONE, P4, Q)) {
                helper.assertTrue(!watch.footprint().hit(helper.absolutePos(rel)), "cell " + rel.toShortString() + " is inside the footprint already");
            }
            Runnable pistons = () -> {
                helper.setBlock(S2, Blocks.STONE);
                helper.setBlock(P3, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.SOUTH));
                helper.setBlock(P4, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST));
                helper.setBlock(Q, Blocks.STONE);
                helper.setBlock(T2, Blocks.TNT);
                helper.setBlock(REDSTONE, Blocks.REDSTONE_BLOCK);
            };
            Runnable observer = () -> helper.setBlock(O2, observerDown());
            int[] reruns = {0};
            Runnable finalCheck = () -> guard(helper, () -> {
                Prediction p = watch.latest();
                helper.assertTrue(p != null && watch.reruns() > reruns[0], "no rerun after the last edit");
                String line = chat.lines.stream().filter(l -> l.startsWith("rerun:")).reduce((a, b) -> b).orElse("(none)");
                LOG.info("[ghostpath] triggers: last rerun line: {}", line);
                helper.assertTrue(p.tntSpawned() == 1 && p.tntSpawnTicks().size() == 1, "the rerun lit " + p.tntSpawned() + " TNT: " + p.tntSpawnTicks());
                helper.assertTrue(wrote(p, helper.absolutePos(T2)) && wrote(p, helper.absolutePos(LANDING)), "P3 did not push the TNT block: head cell " + wrote(p, helper.absolutePos(T2)) + " landing " + wrote(p, helper.absolutePos(LANDING)));
                helper.assertTrue(wrote(p, helper.absolutePos(Q)) && wrote(p, helper.absolutePos(Q.west())), "P4 did not push its stone");
                helper.assertTrue(wrote(p, helper.absolutePos(O2)), "the observer did not fire");
                notFired(helper, level, 0);
                BlockState realT2 = level.getBlockState(helper.absolutePos(T2));
                helper.assertTrue(realT2.is(Blocks.TNT) && !level.getBlockState(helper.absolutePos(P3)).getValue(PistonBaseBlock.EXTENDED), "the real pistons moved: " + realT2);
                end(helper);
                helper.succeed();
            });
            if (pistonsFirst) {
                pistons.run();
                settled(helper, watch, () -> guard(helper, () -> {
                    reruns[0] = watch.reruns();
                    LOG.info("[ghostpath] triggers: pistons first: {} reruns before the observer (margin catches the stone at S2)", reruns[0]);
                    observer.run();
                    helper.assertTrue(watch.dirty(), "the observer was not seen");
                    LiveRerunTest.waitFor(helper, "the rerun with the observer", 60000, () -> notFired(helper, level, 0), () -> watch.reruns() > reruns[0] && !watch.dirty() && LiveWatch.running() == null, finalCheck);
                }));
            } else {
                observer.run();
                helper.assertTrue(watch.dirty(), "the observer was not seen");
                LiveRerunTest.waitFor(helper, "the rerun with the observer", 60000, () -> notFired(helper, level, 0), () -> watch.reruns() >= 1 && !watch.dirty() && LiveWatch.running() == null, () -> guard(helper, () -> {
                    Prediction first = watch.latest();
                    helper.assertTrue(first.tntSpawned() == 0, "TNT before the pistons: " + first.tntSpawned());
                    for (BlockPos rel : List.of(S2, P3, P4)) {
                        LOG.info("[ghostpath] triggers: after the observer's rerun, {} is {} in the footprint", rel.toShortString(), watch.footprint().containsPosition(helper.absolutePos(rel)) ? "" : "NOT");
                    }
                    reruns[0] = watch.reruns();
                    pistons.run();
                    helper.assertTrue(watch.dirty(), "the pistons were not seen");
                    LiveRerunTest.waitFor(helper, "the rerun with the pistons", 60000, () -> notFired(helper, level, 0), () -> watch.reruns() > reruns[0] && !watch.dirty() && LiveWatch.running() == null, finalCheck);
                }));
            }
        }));
    }

    /** An observer watching X, a solid block behind it, two pistons and a TNT block, placed after the recording: pistons first, the observer last. */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:trig_pistons_first")
    public void pistonsFirstThenTheObserver(GameTestHelper helper) {
        this.observerAndPistons(helper, true);
    }

    /** The same, the observer first (its rerun finishes before the pistons come). */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:trig_observer_first")
    public void observerFirstThenThePistons(GameTestHelper helper) {
        this.observerAndPistons(helper, false);
    }

    /**
     * Observer -> solid block -> iron door next to that block. The observer and the block first (rerun), then the door: was the
     * door's cell in the footprint from the update lookups? Then the rerun starts and the door opens in the prediction.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:trig_door")
    public void anIronDoorNextToThePoweredBlockOpensInThePrediction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Capture chat = new Capture();
        BlockPos lower = new BlockPos(4, 4, 2);
        BlockPos upper = lower.above();
        recordM(helper, level, chat, (rec, watch) -> guard(helper, () -> {
            BlockPos abs = helper.absolutePos(lower);
            LOG.info("[ghostpath] door: in the recorded footprint: lower {} upper {}; cause (margin 2): lower {} upper {}", watch.footprint().hit(abs),
                watch.footprint().hit(helper.absolutePos(upper)), watch.footprint().cause(abs, 2), watch.footprint().cause(helper.absolutePos(upper), 2));
            helper.assertTrue(!watch.footprint().hit(abs), "the door cell is in the recorded footprint");
            helper.setBlock(O2, observerDown());
            helper.setBlock(S2, Blocks.STONE);
            LiveRerunTest.waitFor(helper, "the rerun with observer and block", 60000, () -> notFired(helper, level, 0), () -> watch.reruns() >= 1 && !watch.dirty() && LiveWatch.running() == null, () -> guard(helper, () -> {
                boolean read = watch.footprint().containsPosition(abs);
                boolean readUpper = watch.footprint().containsPosition(helper.absolutePos(upper));
                LOG.info("[ghostpath] door: after the observer's rerun the door's lower cell {} in the footprint (read by an update lookup), the upper cell {}", read ? "IS" : "is NOT", readUpper ? "IS" : "is NOT");
                helper.assertTrue(read, "the door's lower cell was not read by the update lookups");
                helper.assertTrue(!wrote(watch.latest(), abs), "the door was written before it was there");
                int reruns = watch.reruns();
                BlockState lowerState = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
                level.setBlock(abs, lowerState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                level.setBlock(helper.absolutePos(upper), lowerState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                helper.assertTrue(watch.dirty(), "the door was not seen");
                LiveRerunTest.waitFor(helper, "the rerun with the door", 60000, () -> notFired(helper, level, 0), () -> watch.reruns() > reruns && !watch.dirty() && LiveWatch.running() == null, () -> guard(helper, () -> {
                    Prediction p = watch.latest();
                    boolean opened = p.events().stream().anyMatch(e -> e.kind() == ChainEvent.Kind.WRITE && abs.equals(e.pos()) && e.detail().contains("-> ") && e.detail().substring(e.detail().indexOf("-> ")).contains("open=true"));
                    LOG.info("[ghostpath] door: opened in the prediction: {}; events at the door: {}", opened, p.events().stream().filter(e -> abs.equals(e.pos())).map(ChainEvent::toString).toList());
                    helper.assertTrue(opened, "the door did not open in the prediction");
                    helper.assertTrue(level.getBlockState(abs).getValue(DoorBlock.OPEN) == Boolean.FALSE, "the real door opened");
                    end(helper);
                    helper.succeed();
                }));
            }));
        }));
    }

    /** {@code /path rerun}: no quiet period, the run starts within a few ticks; the real machine does not fire. */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:trig_rerun_command")
    public void thePathRerunCommandStartsARunAtOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Capture chat = new Capture();
        recordM(helper, level, chat, (rec, watch) -> guard(helper, () -> {
            helper.assertTrue(!watch.dirty() && watch.reruns() == 0, "dirty at the start");
            long issued = level.getGameTime();
            LiveRerunTest.command(level, chat, "path rerun");
            helper.assertTrue(chat.has("Rerun queued for 1 watch"), "chat: " + chat.lines);
            long[] started = {-1};
            LiveRerunTest.waitFor(helper, "the forced rerun", 60000, () -> {
                notFired(helper, level, 0);
                if (started[0] < 0 && LiveWatch.running() != null) {
                    started[0] = level.getGameTime();
                }
            }, () -> watch.reruns() >= 1, () -> guard(helper, () -> {
                LOG.info("[ghostpath] rerun command: started {} ticks after the command (quiet period is {})", started[0] - issued, LiveWatch.QUIET_TICKS);
                helper.assertTrue(started[0] >= 0 && started[0] - issued < LiveWatch.QUIET_TICKS, "the run started after " + (started[0] - issued) + " ticks");
                helper.assertTrue(chat.lines.stream().anyMatch(l -> l.startsWith("rerun:") && l.contains("changed since last: nothing")), "chat: " + chat.lines);
                helper.assertTrue(watch.lastTrigger() != null && watch.lastTrigger().label().equals("rerun: forced"), "last trigger " + watch.lastTrigger());
                // a position that is not watched
                LiveRerunTest.command(level, chat, "path rerun 1 2 3");
                helper.assertTrue(chat.has("No such watch"), "chat: " + chat.lines);
                end(helper);
                helper.succeed();
            }));
        }));
    }

    /**
     * Two machines in one chunk. One block within the margin of both (and a second change near each) marks both; the reruns
     * go one after the other, none fails; {@code /path rerun x y z} reruns the one only, {@code /path rerun} both.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:trig_two_machines")
    public void twoMachinesInOneChunkRerunOneAfterTheOther(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Capture chat = new Capture();
        // the test structure sits at a different place each run: shift both machines so that they start at a chunk corner (and so share a chunk)
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        BlockPos off = new BlockPos((16 - (o.getX() & 15)) & 15, 0, (16 - (o.getZ() & 15)) & 15);
        BlockPos lever1 = LEVER.offset(off);
        BlockPos lever2 = LEVER.offset(off).offset(0, 0, 4);
        TestSupport.removeBarriers(helper);
        TestSupport.forceAround(helper, 3);
        for (int x = -1; x <= 20; x++) {
            for (int z = 0; z <= 20; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        for (int m = 0; m < 2; m++) {
            BlockPos b = off.offset(0, 0, 4 * m);
            helper.setBlock(new BlockPos(0, 2, 2).offset(b), Blocks.STONE);
            helper.setBlock(LEVER.offset(b), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
            helper.setBlock(PA.offset(b), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
            helper.setBlock(new BlockPos(2, 2, 2).offset(b), Blocks.STONE);
        }
        List<BlockPos> corners = List.of(new BlockPos(0, 2, 0), new BlockPos(20, 2, 20), new BlockPos(3, 2, 2).offset(off), new BlockPos(3, 2, 6).offset(off));
        LiveRerunTest.whenTicking(helper, corners, TestSupport.once(() -> LiveRerunTest.recordAndWatch(helper, level, chat, lever1, (rec1, w1) -> guard(helper, () -> recordMore(helper, level, chat, lever2, (rec2, w2) -> guard(helper, () -> {
            helper.assertTrue(LiveWatch.watches().size() == 2, "watches: " + LiveWatch.list());
            helper.assertTrue(rec1.trigger().getX() >> 4 == rec2.trigger().getX() >> 4 && rec1.trigger().getZ() >> 4 == rec2.trigger().getZ() >> 4, "the machines are not in one chunk");
            BlockPos both = new BlockPos(3, 3, 4).offset(off); // two cells from X1 and from X2 (3,2,2 / 3,2,6)
            helper.assertTrue(w1.footprint().cause(helper.absolutePos(both), 2) != null && w2.footprint().cause(helper.absolutePos(both), 2) != null, "the cell is not near both");
            helper.setBlock(both, Blocks.STONE);
            helper.assertTrue(w1.dirty() && w2.dirty(), "one change did not mark both: " + LiveWatch.list());
            LiveRerunTest.waitFor(helper, "both reruns", 60000, () -> { }, () -> w1.reruns() >= 1 && w2.reruns() >= 1, () -> guard(helper, () -> {
                helper.assertTrue(w1.failures() == 0 && w2.failures() == 0 && chat.lines.stream().noneMatch(l -> l.contains("rerun failed")), "a rerun failed: " + chat.lines);
                helper.assertTrue(w1.cancelled() == 0 && w2.cancelled() == 0, "a run was cancelled: " + w1.cancelled() + "/" + w2.cancelled());
                helper.assertTrue(chat.lines.stream().filter(l -> l.startsWith("rerun:")).count() == 2, "rerun lines: " + chat.lines);
                int a = w1.reruns();
                int b = w2.reruns();
                BlockPos abs2 = helper.absolutePos(lever2);
                LiveRerunTest.command(level, chat, "path rerun " + abs2.getX() + " " + abs2.getY() + " " + abs2.getZ());
                LiveRerunTest.waitFor(helper, "the targeted rerun", 60000, () -> { }, () -> w2.reruns() > b && LiveWatch.running() == null, () -> guard(helper, () -> {
                    helper.assertTrue(w1.reruns() == a, "the other machine reran too");
                    LiveRerunTest.command(level, chat, "path rerun");
                    helper.assertTrue(chat.has("Rerun queued for 2 watches"), "chat: " + chat.lines);
                    LiveRerunTest.waitFor(helper, "both forced reruns", 60000, () -> { }, () -> w1.reruns() > a && w2.reruns() > b + 1 && LiveWatch.running() == null, () -> guard(helper, () -> {
                        helper.assertTrue(w1.failures() == 0 && w2.failures() == 0, "failures");
                        end(helper);
                        helper.succeed();
                    }));
                }));
            }));
        }))))), 0);
    }
}
