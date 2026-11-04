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
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
    static void forceChunks(GameTestHelper helper, boolean forced) {
        forceChunks(helper, forced, BlockPos.ZERO);
    }

    /** Same, for the machine built at {@code off} (relative to the test structure). */
    static void forceChunks(GameTestHelper helper, boolean forced, BlockPos off) {
        BlockPos min = helper.absolutePos(new BlockPos(-8, 0, -8).offset(off));
        BlockPos max = helper.absolutePos(new BlockPos(22, 0, 14).offset(off));
        for (int cx = Math.min(min.getX(), max.getX()) >> 4; cx <= Math.max(min.getX(), max.getX()) >> 4; cx++) {
            for (int cz = Math.min(min.getZ(), max.getZ()) >> 4; cz <= Math.max(min.getZ(), max.getZ()) >> 4; cz++) {
                helper.getLevel().setChunkForced(cx, cz, forced);
            }
        }
    }

    /**
     * Runs {@code then} once the whole machine area is entity-ticking. Forced chunks become entity-ticking after async
     * chunk and entity-section loading, which takes real time, while game tests tick at full speed (no 50 ms sleep): the
     * wait can be thousands of ticks, hence the large maxTicks (seen: >800 once).
     */
    static void whenEntityTicking(GameTestHelper helper, Runnable then) {
        whenEntityTicking(helper, BlockPos.ZERO, then, 0);
    }

    static void whenEntityTicking(GameTestHelper helper, BlockPos off, Runnable then) {
        whenEntityTicking(helper, off, then, 0);
    }

    private static void whenEntityTicking(GameTestHelper helper, BlockPos off, Runnable then, int waited) {
        boolean ready = true;
        for (BlockPos rel : List.of(new BlockPos(0, 2, 0), new BlockPos(14, 2, 0), new BlockPos(0, 2, 6), new BlockPos(14, 2, 6), TNT)) {
            ready &= helper.getLevel().isPositionEntityTicking(helper.absolutePos(rel.offset(off)));
        }
        if (ready) {
            LOG.info("[ghostpath] machine area entity-ticking after {} ticks", waited);
            then.run();
        } else {
            helper.runAfterDelay(1, () -> whenEntityTicking(helper, off, then, waited + 1));
        }
    }

    static void buildMachine(GameTestHelper helper) {
        buildMachine(helper, BlockPos.ZERO, false);
    }

    /**
     * The machine with its west end at {@code off}. {@code lever}: a lever on the floor at the trigger position, for
     * {@code /path machine}; else the trigger spot is empty and {@link #runMachine} places a redstone block there.
     */
    static void buildMachine(GameTestHelper helper, BlockPos off, boolean lever) {
        TestSupport.removeBarriers(helper);
        forceChunks(helper, true, off);
        for (int x = 0; x <= 14; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z).offset(off), Blocks.STONE);
            }
        }
        for (int x = 1; x <= 6; x++) {
            // DiodeBlock.getInputSignal (DiodeBlock.java:113-116) reads from pos.relative(FACING): input from the west
            helper.setBlock(new BlockPos(x, 2, 1).offset(off), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST));
        }
        helper.setBlock(PISTON.offset(off), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        helper.setBlock(PUSHED.offset(off), Blocks.REDSTONE_BLOCK); // in front of the piston, so it does not power it
        helper.setBlock(TNT.offset(off), Blocks.TNT);
        if (lever) {
            // a floor lever powers the repeater next to it directly (LeverBlock.getSignal, :133-143)
            helper.setBlock(TRIGGER.offset(off), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        }
    }

    static BlockPos trigger() {
        return TRIGGER;
    }

    static void runMachine(GameTestHelper helper, CauseTracker tracker) {
        runMachine(helper, tracker, BlockPos.ZERO);
    }

    static void runMachine(GameTestHelper helper, CauseTracker tracker, BlockPos off) {
        tracker.runAsChain(() -> helper.setBlock(TRIGGER.offset(off), Blocks.REDSTONE_BLOCK));
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * An observer clock (two observers facing each other, a lamp on each output) runs three blocks from the repeater
     * line the whole time. It writes blocks and schedules ticks every few ticks during the recording, but none of that
     * may enter the chain: no read, no write, no event at any clock position.
     */
    @GameTest(maxTicks = 12000, padding = 24, environment = "ghostpath-gametest:cause_clock")
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
                    // read footprint (1c): the piston checks the power of the block above it (quasi-connectivity,
                    // PistonBaseBlock.getNeighborSignal), a position the chain reads but never writes
                    BlockPos abovePiston = helper.absolutePos(PISTON.above());
                    helper.assertTrue(tracker.reads().getOrDefault(abovePiston, java.util.Set.of()).contains(CauseTracker.ReadKind.BLOCK)
                        && !tracker.writes().contains(abovePiston), "block above the piston not read: " + tracker.reads().get(abovePiston));
                    // the clock really ran during the recording (the undo log saw it write)
                    helper.assertTrue(log.hasBlock(helper.absolutePos(observerA)) && log.hasBlock(helper.absolutePos(observerB)),
                        "the clock did not run during the recording, so this test proves nothing");
                    // ... and never entered the chain
                    for (BlockPos rel : clock) {
                        BlockPos abs = helper.absolutePos(rel);
                        helper.assertTrue(!tracker.footprint().contains(abs), "clock block " + rel.toShortString() + " is in the footprint"
                            + (tracker.reads().containsKey(abs) ? " (read: " + tracker.reads().get(abs) + ")" : " (written)"));
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
     * identical, event for event: every write, tick, block event, every chain entity's exact position and velocity on
     * every tick (the primed TNT and the items the blast drops), and every unseeded draw. Item drop positions and the
     * items' own randoms are seeded (1b follow-up ruling), so items must repeat exactly too.
     */
    @GameTest(maxTicks = 12000, padding = 24, environment = "ghostpath-gametest:cause_seed")
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
            helper.assertTrue(a.stream().anyMatch(s -> s.contains("ENTITY_STATE") && s.contains("minecraft:item")), "no item entity from the blast, so items are not tested");
            long destroyed = a.stream().filter(s -> s.contains(" WRITE ") && s.endsWith("-> Block{minecraft:air}")).count();
            helper.assertTrue(destroyed >= 10, "the explosion should destroy blocks, got " + destroyed + " writes to air");
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                String x = i < a.size() ? a.get(i) : "<end>";
                String y = i < b.size() ? b.get(i) : "<end>";
                helper.assertTrue(x.equals(y), "runs differ at event " + i + ":\n  run 1: " + x + "\n  run 2: " + y);
            }
            helper.assertTrue(a.stream().noneMatch(s -> s.contains("WARNING: not allowed")),
                "unseeded draw not on the allowlist: " + a.stream().filter(s -> s.contains("WARNING")).findFirst().orElse(""));
            LOG.info("[ghostpath] seeded runs identical: {} events + footprint entries, {} destroyed blocks", a.size(), destroyed);
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

    /**
     * How {@code p} was read, light reads left out. While it rains, chain entities read light (Entity.isInRain ->
     * Level.precipitationAt -> canSeeSky) and which positions get a light read differed between two otherwise identical
     * runs: skylight is updated by the asynchronous light thread after the restore (found by MachineCommandTest; DESIGN 1d).
     */
    private static java.util.Set<CauseTracker.ReadKind> readsWithoutLight(CauseTracker tracker, BlockPos p) {
        java.util.Set<CauseTracker.ReadKind> kinds = new java.util.TreeSet<>(tracker.reads().getOrDefault(p, java.util.Set.of()));
        kinds.remove(CauseTracker.ReadKind.LIGHT);
        return kinds;
    }

    /** Every event must match between two seeded runs, and so must the footprint (each position with how it was read, light aside). */
    static List<String> comparable(CauseTracker tracker) {
        List<String> out = new ArrayList<>(tracker.events().stream().map(ChainEvent::toString).toList());
        tracker.footprint().stream().sorted()
            .map(p -> "FOOTPRINT " + p.toShortString() + (tracker.writes().contains(p) ? " written" : "") + " read=" + readsWithoutLight(tracker, p))
            .forEach(out::add);
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * Chain ticks and block events are followed by identity. Three spots, each: chain work queues something there, it is
     * removed without running (clearArea / clearBlockEvents, the only vanilla removal paths besides chunk unload), then
     * unrelated work queues an equal-looking tick or event on the same spot. The unrelated one must not run as chain work.
     * P: the chain's tick is merged into an unrelated pending tick (the chain adopts that tick object).
     * Q: the chain's own tick. R: the chain's block event.
     */
    @GameTest(maxTicks = 100, environment = "ghostpath-gametest:cause_cancel")
    public void removedChainWorkDoesNotLeak(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos p = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos q = helper.absolutePos(new BlockPos(3, 2, 1));
        BlockPos r = helper.absolutePos(new BlockPos(5, 2, 1));
        // stone: its tick and block event do nothing (BlockBehaviour defaults), but both still run
        for (BlockPos pos : List.of(p, q, r)) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        }
        CauseTracker tracker = CauseTracker.begin(level, SEED);
        try {
            level.scheduleTick(p, Blocks.STONE, 5);                                        // unrelated, pending
            tracker.runAsChain(() -> level.scheduleTick(p, Blocks.STONE, 3));               // merged into it
            tracker.runAsChain(() -> level.scheduleTick(q, Blocks.STONE, 3));               // chain's own
            tracker.runAsChain(() -> level.blockEvent(r, Blocks.STONE, 1, 2));              // chain's own
            for (BlockPos pos : List.of(p, q, r)) {
                level.getBlockTicks().clearArea(new BoundingBox(pos));
                level.clearBlockEvents(new BoundingBox(pos));
            }
            level.scheduleTick(p, Blocks.STONE, 2);                                        // unrelated, same spot
            level.scheduleTick(q, Blocks.STONE, 2);
            level.blockEvent(r, Blocks.STONE, 1, 2);
        } catch (RuntimeException e) {
            tracker.end();
            throw e;
        }
        helper.runAfterDelay(6, () -> {
            tracker.end();
            report("cancel", tracker);
            helper.assertTrue(!level.getBlockTicks().hasScheduledTick(p, Blocks.STONE) && !level.getBlockTicks().hasScheduledTick(q, Blocks.STONE),
                "the unrelated ticks should have run by now");
            for (ChainEvent e : tracker.events()) {
                String kind = e.kind().name();
                helper.assertTrue(!kind.equals("TICK_RUN") && !kind.equals("BLOCK_EVENT_RUN"), "unrelated work ran as chain work: " + e);
            }
            helper.assertTrue(tracker.events().stream().anyMatch(e -> e.detail().contains("merged") && p.equals(e.pos())), "P: no merge recorded");
            for (BlockPos pos : List.of(p, q, r)) {
                helper.assertTrue(tracker.events().stream().anyMatch(e -> e.kind().name().endsWith("_CANCELLED") && pos.equals(e.pos())),
                    "no cancellation recorded at " + pos.toShortString());
            }
            helper.succeed();
        });
    }

    static void report(String name, CauseTracker tracker) {
        LOG.info("[ghostpath] {}: {} chain events, {} written positions, {} read positions", name, tracker.events().size(), tracker.writes().size(), tracker.reads().size());
        LOG.info("[ghostpath] {} events:\n{}", name, tracker.eventsAsText(400));
        for (Map.Entry<String, Integer> site : tracker.unseededDrawSites().entrySet()) {
            LOG.info("[ghostpath] {}: unseeded draw x{} at {}", name, site.getValue(), site.getKey());
        }
    }
}
