package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.ChainEvent;
import obsidivision.ghostpath.record.UndoLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step 1b: cause tracking.
 *
 * <p>Machine (all at y=2 on a stone floor, z=1): trigger (redstone block placed as chain work) at x=0, six repeaters
 * x=1..6 (scheduled ticks), sticky-free piston at x=7 facing east (block event, moving-piston block entity) pushes a
 * redstone block from x=8 to x=9, which primes the TNT at x=10 (chain entity, seeded kick); the TNT explodes about
 * 95 ticks after the trigger (seeded ray strength and shuffle) and destroys part of the floor and the machine.
 */
public class CauseTrackingTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    private static final long SEED = 0x6A05_7FA7_0000_0001L;
    /** Trigger to explosion is about 95 ticks; leave time for the blast and falling items. */
    private static final int RUN_TICKS = 130;

    private static final BlockPos TRIGGER = new BlockPos(0, 2, 1);
    private static final BlockPos PISTON = new BlockPos(7, 2, 1);
    private static final BlockPos PUSHED = new BlockPos(8, 2, 1);
    private static final BlockPos TNT = new BlockPos(10, 2, 1);

    /**
     * The default test structure is 8x8x8 (fabric-gametest-api-v1:empty.snbt) and the machine is 15 blocks long, so the
     * TNT can land in a chunk that is not entity-ticking (it then never ticks; seen once, depends on where the test is
     * placed). Force-load every chunk the machine and blast can reach for the test's duration.
     */
    private static void forceChunks(GameTestHelper helper, boolean forced) {
        BlockPos min = helper.absolutePos(new BlockPos(-8, 0, -8));
        BlockPos max = helper.absolutePos(new BlockPos(22, 0, 14));
        for (int cx = Math.min(min.getX(), max.getX()) >> 4; cx <= Math.max(min.getX(), max.getX()) >> 4; cx++) {
            for (int cz = Math.min(min.getZ(), max.getZ()) >> 4; cz <= Math.max(min.getZ(), max.getZ()) >> 4; cz++) {
                helper.getLevel().setChunkForced(cx, cz, forced);
            }
        }
    }

    /** Runs {@code then} once the whole machine area is entity-ticking (forced tickets take a few ticks to apply). */
    private static void whenEntityTicking(GameTestHelper helper, Runnable then) {
        boolean ready = true;
        for (BlockPos rel : List.of(new BlockPos(0, 2, 0), new BlockPos(14, 2, 0), new BlockPos(0, 2, 6), new BlockPos(14, 2, 6), TNT)) {
            ready &= helper.getLevel().isPositionEntityTicking(helper.absolutePos(rel));
        }
        if (ready) {
            then.run();
        } else {
            helper.runAfterDelay(1, () -> whenEntityTicking(helper, then));
        }
    }

    private static void buildMachine(GameTestHelper helper) {
        forceChunks(helper, true);
        for (int x = 0; x <= 14; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        for (int x = 1; x <= 6; x++) {
            // DiodeBlock.getInputSignal (DiodeBlock.java:113-116) reads from pos.relative(FACING): input from the west
            helper.setBlock(new BlockPos(x, 2, 1), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST));
        }
        helper.setBlock(PISTON, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        helper.setBlock(PUSHED, Blocks.REDSTONE_BLOCK); // in front of the piston, so it does not power it
        helper.setBlock(TNT, Blocks.TNT);
    }

    private static void runMachine(GameTestHelper helper, CauseTracker tracker) {
        tracker.runAsChain(() -> helper.setBlock(TRIGGER, Blocks.REDSTONE_BLOCK));
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * An observer clock (two observers facing each other, a lamp on each output) runs three blocks from the repeater
     * line the whole time. It writes blocks and schedules ticks every few ticks during the recording, but none of that
     * may enter the chain: no write, no event at any clock position.
     */
    @GameTest(maxTicks = 300, padding = 24, environment = "ghostpath-gametest:cause_clock")
    public void unrelatedClockNeverInFootprint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildMachine(helper);

        BlockPos lampWest = new BlockPos(0, 2, 4);
        BlockPos observerA = new BlockPos(1, 2, 4);
        BlockPos observerB = new BlockPos(2, 2, 4);
        BlockPos lampEast = new BlockPos(3, 2, 4);
        List<BlockPos> clock = List.of(lampWest, observerA, observerB, lampEast);
        helper.setBlock(lampWest, Blocks.REDSTONE_LAMP);
        helper.setBlock(lampEast, Blocks.REDSTONE_LAMP);
        helper.setBlock(observerA, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST));
        // placing B in front of A gives A a shape update from its facing side, which starts the clock
        // (ObserverBlock.updateShape, ObserverBlock.java:63-77)
        helper.setBlock(observerB, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));

        helper.runAfterDelay(10, () -> whenEntityTicking(helper, () -> {
            UndoLog log = UndoLog.begin(level);
            CauseTracker tracker = CauseTracker.begin(level, SEED);
            runMachine(helper, tracker);

            helper.runAfterDelay(RUN_TICKS, () -> {
                tracker.end();
                try {
                    report("clock", tracker);
                    // the chain really ran
                    helper.assertTrue(tracker.writes().contains(helper.absolutePos(TRIGGER)), "trigger write missing from footprint");
                    helper.assertTrue(tracker.writes().contains(helper.absolutePos(TNT)), "TNT block removal missing from footprint");
                    helper.assertTrue(tracker.events().stream().anyMatch(e -> e.kind() == ChainEvent.Kind.ENTITY_TAGGED && e.detail().contains("minecraft:tnt")),
                        "primed TNT never joined the chain");
                    helper.assertTrue(tracker.events().stream().anyMatch(e -> e.kind() == ChainEvent.Kind.BLOCK_EVENT_RUN),
                        "piston block event never ran as chain work");
                    helper.assertTrue(tracker.events().stream().anyMatch(e -> e.kind() == ChainEvent.Kind.ENTITY_STATE && e.detail().contains("minecraft:tnt")),
                        "primed TNT never ticked as chain work");
                    // the clock really ran during the recording (the undo log saw it write)
                    helper.assertTrue(log.hasBlock(helper.absolutePos(observerA)) && log.hasBlock(helper.absolutePos(observerB)),
                        "the clock did not run during the recording, so this test proves nothing");
                    // ... and never entered the chain
                    for (BlockPos rel : clock) {
                        BlockPos abs = helper.absolutePos(rel);
                        helper.assertTrue(!tracker.writes().contains(abs), "clock block " + rel.toShortString() + " is in the footprint");
                        for (ChainEvent e : tracker.events()) {
                            helper.assertTrue(!abs.equals(e.pos()), "clock block " + rel.toShortString() + " has a chain event: " + e);
                        }
                    }
                } finally {
                    log.restore();
                    forceChunks(helper, false);
                }
                helper.succeed();
            });
        }));
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * DESIGN R1: with the same seed, the machine runs twice (undo log restores in between) and the chain must be
     * identical: every write, tick, block event, and the primed TNT's exact position and velocity on every tick. Item
     * entities from the blast are left out: their spawn velocity is an unseeded draw (reported, see the log).
     */
    @GameTest(maxTicks = 400, padding = 24, environment = "ghostpath-gametest:cause_seed")
    public void seededChainRepeatsExactly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildMachine(helper);
        List<List<String>> runs = new ArrayList<>();

        Runnable[] second = new Runnable[1];
        Runnable first = () -> record(helper, level, runs, () -> helper.runAfterDelay(5, second[0]));
        second[0] = () -> record(helper, level, runs, () -> {
            forceChunks(helper, false);
            List<String> a = runs.get(0);
            List<String> b = runs.get(1);
            helper.assertTrue(a.stream().anyMatch(s -> s.contains("ENTITY_STATE") && s.contains("minecraft:tnt")), "primed TNT never ticked as chain work");
            long destroyed = a.stream().filter(s -> s.contains(" WRITE ") && s.endsWith("-> Block{minecraft:air}")).count();
            helper.assertTrue(destroyed >= 10, "the explosion should destroy blocks, got " + destroyed + " writes to air");
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                String x = i < a.size() ? a.get(i) : "<end>";
                String y = i < b.size() ? b.get(i) : "<end>";
                helper.assertTrue(x.equals(y), "runs differ at event " + i + ":\n  run 1: " + x + "\n  run 2: " + y);
            }
            LOG.info("[ghostpath] seeded runs identical: {} events, {} destroyed blocks", a.size(), destroyed);
            helper.succeed();
        });
        whenEntityTicking(helper, first);
    }

    private static void record(GameTestHelper helper, ServerLevel level, List<List<String>> runs, Runnable then) {
        UndoLog log = UndoLog.begin(level);
        CauseTracker tracker = CauseTracker.begin(level, SEED);
        runMachine(helper, tracker);
        helper.runAfterDelay(RUN_TICKS, () -> {
            tracker.end();
            try {
                report("seed run " + (runs.size() + 1), tracker);
                runs.add(comparable(tracker));
            } finally {
                log.restore();
            }
            then.run();
        });
    }

    /** Events that must match between two seeded runs: all but unseeded draws and non-TNT entities. */
    private static List<String> comparable(CauseTracker tracker) {
        List<String> out = new ArrayList<>();
        for (ChainEvent e : tracker.events()) {
            switch (e.kind()) {
                case UNSEEDED_DRAW -> { }
                case ENTITY_TAGGED, ENTITY_STATE -> {
                    if (e.detail().contains("minecraft:tnt")) {
                        out.add(e.toString());
                    }
                }
                default -> out.add(e.toString());
            }
        }
        return out;
    }

    private static void report(String name, CauseTracker tracker) {
        LOG.info("[ghostpath] {}: {} chain events, {} written positions", name, tracker.events().size(), tracker.writes().size());
        LOG.info("[ghostpath] {} events:\n{}", name, tracker.eventsAsText(400));
        for (Map.Entry<String, Integer> site : tracker.unseededDrawSites().entrySet()) {
            LOG.info("[ghostpath] {}: unseeded draw x{} at {}", name, site.getValue(), site.getKey());
        }
    }
}
