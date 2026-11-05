package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.ChainEvent;
import obsidivision.ghostpath.record.UndoLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finding for DESIGN (1c, "footprint varies with location", 835-978 read positions between JVM runs): the same machine
 * is recorded at two places exactly 32 blocks apart on x and z, i.e. with the same chunk alignment, same seed. Footprints
 * are compared relative to each machine's origin. Same footprint: the variation between runs comes from chunk boundaries
 * (the test lands at a different chunk offset each run). Different: it comes from the absolute position (hash or
 * iteration order, double precision). The test only measures and logs; it fails only if a recording does not run.
 */
public class FootprintVariationTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    private static final long SEED = 0x6A05_7FA7_0000_0001L;
    private static final int RUN_TICKS = 130;
    private static final BlockPos OFFSET_A = BlockPos.ZERO;
    private static final BlockPos OFFSET_B = new BlockPos(32, 0, 32);

    private record Run(Set<String> footprint, List<String> events, int reads, int writes, int destroyed, long belowFull) {}

    /** Entries look like "x, y, z ..."; counts per y. */
    private static java.util.Map<Integer, Integer> byY(Set<String> entries) {
        java.util.Map<Integer, Integer> out = new java.util.TreeMap<>();
        for (String e : entries) {
            out.merge(Integer.parseInt(e.split(",")[1].trim()), 1, Integer::sum);
        }
        return out;
    }

    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:variation")
    public void sameMachine32BlocksApart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CauseTrackingTest.buildMachine(helper, OFFSET_A, false);
        CauseTrackingTest.buildMachine(helper, OFFSET_B, false);
        List<Run> runs = new ArrayList<>();
        CauseTrackingTest.whenEntityTicking(helper, OFFSET_A, () -> CauseTrackingTest.whenEntityTicking(helper, OFFSET_B,
            () -> record(helper, level, OFFSET_A, runs, () -> helper.runAfterDelay(5, () -> record(helper, level, OFFSET_B, runs,
                // control: A again, after B. A == A' and A != B: the place matters; A' == B != A: the order matters
                () -> helper.runAfterDelay(5, () -> record(helper, level, OFFSET_A, runs, () -> finish(helper, runs))))))));
    }

    private static void record(GameTestHelper helper, ServerLevel level, BlockPos off, List<Run> runs, Runnable then) {
        UndoLog log = UndoLog.begin(level);
        CauseTracker tracker = CauseTracker.begin(level, SEED);
        CauseTrackingTest.runMachine(helper, tracker, off);
        helper.runAfterDelay(RUN_TICKS, () -> {
            tracker.end();
            try {
                BlockPos origin = helper.absolutePos(off); // relative to this machine's origin: the footprint is compared shifted
                Set<String> fp = new TreeSet<>();
                for (BlockPos p : tracker.footprint()) {
                    BlockPos r = p.subtract(origin);
                    fp.add(r.toShortString() + (tracker.writes().contains(p) ? " W" : "") + " " + new TreeSet<>(tracker.reads().getOrDefault(p, Set.of())));
                }
                long destroyed = tracker.events().stream().filter(e -> e.kind() == ChainEvent.Kind.WRITE && e.detail().endsWith("-> Block{minecraft:air}")).count();
                runs.add(new Run(fp, tracker.events().stream().map(ChainEvent::toString).toList(), tracker.reads().size(), tracker.writes().size(), (int) destroyed,
                    tracker.events().stream().filter(e -> e.kind() == ChainEvent.Kind.NO_COLLISION_BELOW_FULL).count()));
                LOG.info("[ghostpath] variation run at offset {} (absolute origin {}): {} read, {} written, {} destroyed, {} events",
                    off.toShortString(), origin.toShortString(), tracker.reads().size(), tracker.writes().size(), destroyed, tracker.events().size());
            } finally {
                log.restore();
            }
            then.run();
        });
    }

    private static String compare(String name, Run x, Run y) {
        Set<String> onlyX = new TreeSet<>(x.footprint());
        onlyX.removeAll(y.footprint());
        Set<String> onlyY = new TreeSet<>(y.footprint());
        onlyY.removeAll(x.footprint());
        return name + ": " + (onlyX.isEmpty() && onlyY.isEmpty() ? "IDENTICAL" : "DIFFERENT (only in first " + onlyX.size() + ", only in second " + onlyY.size()
            + "; differing entries by relative y " + byY(onlyX) + " / " + byY(onlyY) + ")") + "; reads " + x.reads() + " vs " + y.reads()
            + "; events " + (x.events().equals(y.events()) ? "identical" : "differ at " + firstDifference(x.events(), y.events()));
    }

    private static String firstDifference(List<String> a, List<String> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String p = i < a.size() ? a.get(i) : "<end>";
            String q = i < b.size() ? b.get(i) : "<end>";
            if (!p.equals(q)) {
                return "event " + i + ": " + p + " | " + q;
            }
        }
        return "none";
    }

    private static void finish(GameTestHelper helper, List<Run> runs) {
        CauseTrackingTest.forceChunks(helper, false, OFFSET_A);
        CauseTrackingTest.forceChunks(helper, false, OFFSET_B);
        Run a = runs.get(0);
        Run b = runs.get(1);
        Run a2 = runs.get(2);
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        LOG.info("[ghostpath] variation RESULT at x/z = ({}, {}), chunk alignment x%16={} z%16={} for A and B (B = A + 32/+32), run order A, B, A again:",
            abs.getX(), abs.getZ(), Math.floorMod(abs.getX(), 16), Math.floorMod(abs.getZ(), 16));
        LOG.info("[ghostpath] variation RESULT A vs B (other place): {}", compare("A vs B", a, b));
        LOG.info("[ghostpath] variation RESULT A vs A again (same place): {}", compare("A vs A'", a, a2));
        LOG.info("[ghostpath] variation RESULT B vs A again: {}", compare("B vs A'", b, a2));
        LOG.info("[ghostpath] variation: NO_COLLISION_BELOW_FULL events A/B/A': {} / {} / {}", a.belowFull(), b.belowFull(), a2.belowFull());
        helper.assertTrue(a.writes() > 0 && b.writes() > 0, "a recording did not run");
        helper.succeed();
    }
}
