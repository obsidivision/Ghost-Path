package obsidivision.ghostpath.test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelTicks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The user's real dust-based 10-TNT duper with compression ({@code schematics/dust-duper-10-compression.litematic}, saved by
 * 1.21.1, data version 3955), loaded by {@link Litematic} and run through the 26.2 DataFixer.
 */
public class DustDuperTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    static final String FILE = "/schematics/dust-duper-10-compression.litematic";

    /** Where the machine's lowest corner sits in the test structure (a stone floor under it). */
    static final BlockPos ORIGIN = new BlockPos(2, 2, 2);

    static Litematic load() {
        try (InputStream in = DustDuperTest.class.getResourceAsStream(FILE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + FILE);
            }
            return Litematic.read(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Places the schematic with the saved states and no neighbour updates (like Litematica does), air included. */
    static void place(GameTestHelper helper, Litematic lit, BlockPos origin) {
        ServerLevel level = helper.getLevel();
        for (Litematic.Block b : lit.blocks) {
            level.setBlock(helper.absolutePos(origin.offset(b.pos())), b.state(), 2 | 16);
        }
    }

    /** The trigger added to the file (it has none): a lever on the floor east of the machine, watched by an observer whose back is against the east end piston (14,2,1). A lever left on the piston itself holds it extended and gives 19-20 TNT; the observer's 2-tick pulse gives the 10 per cycle (see DESIGN). */
    /** Ticks the placed machine runs on its own before the lever is pulled: the file holds a mid-cycle state, placed without updates. */
    static final int SETTLE_TICKS = 300;
    static final BlockPos LEVER = new BlockPos(16, 2, 1);
    /** The observer that turns the lever pull into a 2-tick pulse into the back of the east piston. */
    static final BlockPos OBSERVER = new BlockPos(15, 2, 1);

    /** The machine and a margin: TNT of tests next door (inflate(30) reached them) does not count. */
    static net.minecraft.world.phys.AABB machineBox(GameTestHelper helper) {
        BlockPos a = helper.absolutePos(ORIGIN.offset(-3, -1, -3));
        BlockPos b = helper.absolutePos(ORIGIN.offset(19, 12, 7));
        return new net.minecraft.world.phys.AABB(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    static void floor(GameTestHelper helper) {
        for (int x = -4; x <= 22; x++) {
            for (int z = -4; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    @GameTest(maxTicks = 60000, padding = 8, environment = "ghostpath-gametest:duper_load")
    public void loaderReadsTheSchematic(GameTestHelper helper) {
        Litematic lit = load();
        LOG.info("[ghostpath] duper: litematic version {} (Litematica), MinecraftDataVersion {} (26.2 is {}), author {}, size {}x{}x{}, {} blocks, {} tile entities, {} entities",
            lit.version, lit.dataVersion, lit.currentDataVersion, lit.author, lit.sizeX, lit.sizeY, lit.sizeZ, lit.blocks.stream().filter(b -> !b.state().isAir()).count(), lit.tileEntities.size(), lit.entities.size());
        LOG.info("[ghostpath] duper: the DataFixer changed {} things: {}", lit.fixerChanges.size(), lit.fixerChanges);
        helper.assertTrue(lit.dataVersion == 3955, "data version " + lit.dataVersion);
        helper.assertTrue(lit.sizeX == 15 && lit.sizeY == 4 && lit.sizeZ == 3, "size " + lit.sizeX + "x" + lit.sizeY + "x" + lit.sizeZ);
        helper.assertTrue(lit.blocks.stream().filter(b -> !b.state().isAir()).count() == 113, "TotalBlocks in the metadata is 113");
        helper.assertTrue(lit.blocks.stream().filter(b -> b.state().is(Blocks.TNT)).count() == 10, "TNT blocks");
        helper.assertTrue(lit.fixerChanges.stream().noneMatch(c -> c.startsWith("UNKNOWN")), "unknown blocks: " + lit.fixerChanges);
        helper.succeed();
    }

    /** What one probe saw in its 80 ticks. */
    record Result(String label, int tnt, int firstTnt, boolean pistonMoved, String signature) {
    }

    /** Machine placed fresh, the trigger lever and its stone added (lever off or pulled). */
    static void fresh(GameTestHelper helper, Litematic lit, boolean pulled) {
        fresh(helper, lit, pulled, true);
    }

    static void fresh(GameTestHelper helper, Litematic lit, boolean pulled, boolean withTrigger) {
        BlockPos lo = helper.absolutePos(ORIGIN.offset(-6, -1, -6));
        BlockPos hi = helper.absolutePos(ORIGIN.offset(lit.sizeX + 6, lit.sizeY + 8, lit.sizeZ + 6));
        ServerLevel level = helper.getLevel();
        ((LevelTicks<?>) level.getBlockTicks()).clearArea(BoundingBox.fromCorners(lo, hi));
        ((LevelTicks<?>) level.getFluidTicks()).clearArea(BoundingBox.fromCorners(lo, hi));
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            if (p.getY() > helper.absolutePos(BlockPos.ZERO).getY() + 1) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
        level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, machineBox(helper), e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(net.minecraft.world.entity.Entity::discard);
        floor(helper);
        place(helper, lit, ORIGIN);
        if (withTrigger) {
            addTrigger(helper, pulled);
        }
    }

    static void addTrigger(GameTestHelper helper, boolean pulled) {
        // no updates: placing the lever with updates would itself pulse the observer and start the machine
        ServerLevel placeLevel = helper.getLevel();
        placeLevel.setBlock(helper.absolutePos(ORIGIN.offset(LEVER).below()), Blocks.STONE.defaultBlockState(), 2 | 16);
        placeLevel.setBlock(helper.absolutePos(ORIGIN.offset(OBSERVER)), Blocks.OBSERVER.defaultBlockState().setValue(net.minecraft.world.level.block.ObserverBlock.FACING, Direction.EAST), 2 | 16);
        placeLevel.setBlock(helper.absolutePos(ORIGIN.offset(LEVER)), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR), 2 | 16);
        if (pulled) {
            ServerLevel level = helper.getLevel();
            BlockPos lever = helper.absolutePos(ORIGIN.offset(LEVER));
            BlockState state = level.getBlockState(lever);
            ((LeverBlock) state.getBlock()).pull(state, level, lever, null);
        }
    }

    /** Runs each setup on a freshly placed machine for {@code ticks} ticks and reports TNT lit, the tick of the first, and whether any piston moved. */
    static void probes(GameTestHelper helper, Litematic lit, List<String> labels, List<Runnable> setups, int ticks, java.util.function.Consumer<List<Result>> done) {
        ServerLevel level = helper.getLevel();
        List<Result> results = new ArrayList<>();
        int[] at = {-1};
        int[] age = {0};
        int[] first = {-1};
        boolean[] moved = {false};
        String[] sig = {""};
        Set<java.util.UUID> seen = new HashSet<>();
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (at[0] >= 0) {
                for (PrimedTnt t : level.getEntitiesOfClass(PrimedTnt.class, machineBox(helper))) {
                    if (seen.add(t.getUUID()) && first[0] < 0) {
                        first[0] = age[0];
                    }
                }
                if (age[0] == 30) {
                    BlockPos base = helper.absolutePos(ORIGIN);
                    double[] sum = new double[3];
                    int n = 0;
                    for (PrimedTnt t : level.getEntitiesOfClass(PrimedTnt.class, machineBox(helper))) {
                        sum[0] += t.getX() - base.getX();
                        sum[1] += t.getY() - base.getY();
                        sum[2] += t.getZ() - base.getZ();
                        n++;
                    }
                    sig[0] = String.format("n=%d sum=%.2f/%.2f/%.2f", n, sum[0], sum[1], sum[2]);
                }
                for (BlockPos p : BlockPos.betweenClosed(helper.absolutePos(ORIGIN.offset(-1, -1, -1)), helper.absolutePos(ORIGIN.offset(lit.sizeX + 1, lit.sizeY + 1, lit.sizeZ + 1)))) {
                    BlockState b = level.getBlockState(p);
                    moved[0] |= b.is(Blocks.MOVING_PISTON) || b.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock && b.getValue(net.minecraft.world.level.block.piston.PistonBaseBlock.EXTENDED);
                }
            }
            if (at[0] < 0 || age[0] >= ticks) {
                if (at[0] >= 0) {
                    results.add(new Result(labels.get(at[0]), seen.size(), first[0], moved[0], sig[0]));
                }
                at[0]++;
                age[0] = 0;
                first[0] = -1;
                moved[0] = false;
                sig[0] = "";
                seen.clear();
                if (at[0] >= labels.size()) {
                    done.accept(results);
                    return;
                }
                setups.get(at[0]).run();
            }
            age[0]++;
            helper.runAfterDelay(1, TestSupport.once(step[0]));
        };
        TestSupport.later(helper, 40, step[0]);
    }

    /**
     * Exploration (a finding, see DESIGN): the file has no lever or button. A lever (pulled) on every neighbouring block of every
     * air position next to the machine, on a freshly placed machine, 80 ticks each; prints those that light TNT.
     */
    @GameTest(maxTicks = 60000, padding = 8, environment = "ghostpath-gametest:duper_explore")
    public void exploreTriggers(GameTestHelper helper) {
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        Runnable unforce = TestSupport.forceAround(helper, 4);
        floor(helper);
        Litematic lit = load();
        ServerLevel level = helper.getLevel();
        place(helper, lit, ORIGIN);
        List<String> labels = new ArrayList<>();
        List<Runnable> setups = new ArrayList<>();
        for (int x = -1; x <= lit.sizeX; x++) {
            for (int y = -1; y <= lit.sizeY; y++) {
                for (int z = -1; z <= lit.sizeZ; z++) {
                    BlockPos rel = new BlockPos(x, y, z);
                    if (!helper.getBlockState(ORIGIN.offset(rel)).isAir()) {
                        continue;
                    }
                    for (Direction d : Direction.values()) {
                        BlockPos support = helper.absolutePos(ORIGIN.offset(rel.relative(d)));
                        if (!level.getBlockState(support).isFaceSturdy(level, support, d.getOpposite())) {
                            continue;
                        }
                        BlockState lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.POWERED, true);
                        BlockState state = d == Direction.DOWN ? lever.setValue(LeverBlock.FACE, AttachFace.FLOOR)
                            : d == Direction.UP ? lever.setValue(LeverBlock.FACE, AttachFace.CEILING)
                            : lever.setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, d.getOpposite());
                        labels.add("lever at " + rel.toShortString() + " on the block to the " + d.getName());
                        setups.add(() -> {
                            fresh(helper, lit, false, false);
                            helper.setBlock(ORIGIN.offset(rel), state);
                        });
                    }
                }
            }
        }
        probes(helper, lit, labels, setups, 80, results -> {
            List<Result> hits = results.stream().filter(r -> r.tnt() > 0).toList();
            LOG.info("[ghostpath] duper explore: {} lever placements tried, {} light TNT within 80 ticks", results.size(), hits.size());
            hits.forEach(h -> LOG.info("[ghostpath] duper explore:   {}: {} TNT, first at tick {}", h.label(), h.tnt(), h.firstTnt()));
            CauseTrackingTest.forceChunks(helper, false);
            unforce.run();
            helper.succeed();
        });
    }

    /**
     * Exploration: which piston (any of 6 facings) in an air cell next to an observer changes the machine, without moving by
     * itself while the lever is off. Prints idle and pulled results against the unchanged machine.
     */
    @GameTest(maxTicks = 60000, padding = 8, environment = "ghostpath-gametest:duper_piston")
    public void explorePistons(GameTestHelper helper) {
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        Runnable unforce = TestSupport.forceAround(helper, 4);
        floor(helper);
        Litematic lit = load();
        place(helper, lit, ORIGIN);
        List<String> labels = new ArrayList<>();
        List<Runnable> setups = new ArrayList<>();
        labels.add("baseline pulled");
        setups.add(() -> fresh(helper, lit, true));
        labels.add("baseline idle");
        setups.add(() -> fresh(helper, lit, false));
        List<BlockPos> cells = new ArrayList<>();
        for (Litematic.Block b : lit.blocks) {
            if (!b.state().is(Blocks.OBSERVER)) {
                continue;
            }
            for (Direction d : Direction.values()) {
                BlockPos c = b.pos().relative(d);
                boolean inside = c.getX() >= 0 && c.getY() >= 0 && c.getZ() >= 0 && c.getX() < lit.sizeX && c.getY() < lit.sizeY && c.getZ() < lit.sizeZ;
                if (inside && helper.getBlockState(ORIGIN.offset(c)).isAir() && !cells.contains(c)) {
                    cells.add(c);
                }
            }
        }
        for (BlockPos c : cells) {
            for (Direction f : Direction.values()) {
                for (boolean pulled : new boolean[] {false, true}) {
                    labels.add("piston at " + c.toShortString() + " facing " + f.getName() + (pulled ? " pulled" : " idle"));
                    setups.add(() -> {
                        fresh(helper, lit, pulled);
                        helper.setBlock(ORIGIN.offset(c), Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, f));
                    });
                }
            }
        }
        probes(helper, lit, labels, setups, 80, results -> {
            Result base = results.getFirst();
            Result baseIdle = results.get(1);
            LOG.info("[ghostpath] duper pistons: baseline pulled: {} TNT, first at {}, piston moved {}, {}; idle: {} TNT, moved {}; {} cells next to observers", base.tnt(), base.firstTnt(), base.pistonMoved(), base.signature(), baseIdle.tnt(), baseIdle.pistonMoved(), cells.size());
            int shown = 0;
            for (int i = 2; i + 1 < results.size(); i += 2) {
                Result idle = results.get(i);
                Result pulled = results.get(i + 1);
                if (idle.tnt() == 0 && idle.pistonMoved() == baseIdle.pistonMoved() && !pulled.signature().equals(base.signature())) {
                    LOG.info("[ghostpath] duper pistons:   {} -> {} TNT, first at {}, {}", idle.label().replace(" idle", ""), pulled.tnt(), pulled.firstTnt(), pulled.signature());
                    shown++;
                }
            }
            LOG.info("[ghostpath] duper pistons: {} placements stay idle and change the pulled machine", shown);
            CauseTrackingTest.forceChunks(helper, false);
            unforce.run();
            helper.succeed();
        });
    }

    /** The cells the recording of the real duper is reported on: every number printed in the log is what DESIGN quotes. */
    @GameTest(maxTicks = 120000, padding = 24, environment = "ghostpath-gametest:duper_main")
    public void recordTheDuper(GameTestHelper helper) {
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        Runnable unforce = TestSupport.forceAround(helper, 4);
        floor(helper);
        Litematic lit = load();
        ServerLevel level = helper.getLevel();
        place(helper, lit, ORIGIN);
        addTrigger(helper, false);
        LiveRerunTest.Capture chat = new LiveRerunTest.Capture();
        List<BlockPos> corners = List.of(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(20, 0, -6), ORIGIN.offset(-6, 0, 12), ORIGIN.offset(20, 0, 12), ORIGIN.offset(7, 2, 1));
        LiveRerunTest.whenTicking(helper, corners, TestSupport.once(() -> TestSupport.later(helper, SETTLE_TICKS, () -> {
            long t0 = System.nanoTime();
            LiveRerunTest.recordAndWatch(helper, level, chat, ORIGIN.offset(LEVER), (rec, watch) -> {
                long recordMs = (System.nanoTime() - t0) / 1_000_000;
                try {
                    report(helper, rec, recordMs, chat);
                } finally {
                    LiveRerunTest.finish(helper);
                    unforce.run();
                }
                helper.succeed();
            });
        })), 0);
    }

    private static void report(GameTestHelper helper, obsidivision.ghostpath.record.Recording rec, long recordMs, LiveRerunTest.Capture chat) {
        LOG.info("[ghostpath] duper: recording took {} ms of real time (includes the ghost check)", recordMs);
        chat.lines.forEach(l -> LOG.info("[ghostpath] duper chat: {}", l));
        List<Integer> spawns = obsidivision.ghostpath.watch.Prediction.tntSpawnTicks(rec.events(), rec.entityTrace());
        java.util.TreeMap<Integer, Integer> waves = new java.util.TreeMap<>();
        spawns.forEach(t -> waves.merge(t, 1, Integer::sum));
        LOG.info("[ghostpath] duper: {} ticks recorded (limit {}, hit {}); TNT spawned {}, in waves (tick -> count): {}", rec.ticks(), rec.limit(), rec.limitHit(), rec.tntSpawned(), waves);
        BlockPos base = helper.absolutePos(ORIGIN);
        for (obsidivision.ghostpath.record.ChainEvent e : rec.events()) {
            if (e.kind() == obsidivision.ghostpath.record.ChainEvent.Kind.BLOCK_EVENT_RUN || e.kind() == obsidivision.ghostpath.record.ChainEvent.Kind.WRITE && e.detail().contains("minecraft:piston}") && false) {
                LOG.info("[ghostpath] duperevent t+{} {} {} {}", e.tick(), e.kind(), e.pos() == null ? "" : e.pos().subtract(base).toShortString(), e.detail());
            }
        }
        obsidivision.ghostpath.preview.PathSnapshot snap = obsidivision.ghostpath.preview.PathStore.latest();
        LOG.info("[ghostpath] duper: {} path lines", snap.lines().size());
        for (obsidivision.ghostpath.preview.PathSnapshot.Line l : snap.lines()) {
            if (!l.type().equals("minecraft:tnt")) {
                continue;
            }
            LOG.info("[ghostpath] duper:   {} x{} t+{}..t+{} from ({}, {}, {}) end {} ({}, {}, {})", l.type(), l.count(), l.ticks()[0], l.end().tick(),
                l.xyz()[0], l.xyz()[1], l.xyz()[2], l.end().reason(), l.end().x(), l.end().y(), l.end().z());
        }
        // spread of the TNT per tick
        long maxTick = snap.lines().stream().mapToLong(l -> l.end().tick()).max().orElse(0);
        StringBuilder table = new StringBuilder();
        String last = "";
        for (long t = 0; t <= maxTick; t++) {
            int alive = 0;
            double[] min = {1e9, 1e9, 1e9};
            double[] max = {-1e9, -1e9, -1e9};
            for (obsidivision.ghostpath.preview.PathSnapshot.Line l : snap.lines()) {
                if (!l.type().equals("minecraft:tnt")) {
                    continue;
                }
                int i = l.indexOfTick(t);
                if (i < 0) {
                    continue;
                }
                alive += l.count();
                for (int a = 0; a < 3; a++) {
                    min[a] = Math.min(min[a], l.xyz()[3 * i + a]);
                    max[a] = Math.max(max[a], l.xyz()[3 * i + a]);
                }
            }
            String row = alive == 0 ? "" : alive + " alive, spread " + String.format("%.3f/%.3f/%.3f", max[0] - min[0], max[1] - min[1], max[2] - min[2]) + " at x " + String.format("%.2f", min[0]) + " y " + String.format("%.2f", min[1]) + " z " + String.format("%.2f", min[2]);
            if (!row.isEmpty() && !row.equals(last)) {
                table.append("\n  t+").append(t).append(": ").append(row);
            }
            last = row;
        }
        LOG.info("[ghostpath] duper: TNT per tick (changes only):{}", table);
    }

    /** Place, settle, pull the lever without the mod, watch the TNT entities. */
    private static void plain(GameTestHelper helper, int pulse) {
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        Runnable unforce = TestSupport.forceAround(helper, 4);
        floor(helper);
        Litematic lit = load();
        ServerLevel level = helper.getLevel();
        place(helper, lit, ORIGIN);
        addTrigger(helper, false);
        List<BlockPos> corners = List.of(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(20, 0, -6), ORIGIN.offset(-6, 0, 12), ORIGIN.offset(20, 0, 12), ORIGIN.offset(7, 2, 1));
        LiveRerunTest.whenTicking(helper, corners, TestSupport.once(() -> TestSupport.later(helper, SETTLE_TICKS, () -> {
            BlockPos lever = helper.absolutePos(ORIGIN.offset(LEVER));
            BlockState on = level.getBlockState(lever);
            ((LeverBlock) on.getBlock()).pull(on, level, lever, null);
            int[] age = {0};
            java.util.TreeMap<Integer, String> rows = new java.util.TreeMap<>();
            Runnable[] step = new Runnable[1];
            step[0] = () -> {
                age[0]++;
                List<PrimedTnt> tnt = level.getEntitiesOfClass(PrimedTnt.class, machineBox(helper));
                double minX = 1e9;
                double maxX = -1e9;
                for (PrimedTnt t : tnt) {
                    minX = Math.min(minX, t.getX());
                    maxX = Math.max(maxX, t.getX());
                }
                if (age[0] == 1 || age[0] == 6 || age[0] == 18 || age[0] == 40 || age[0] == 79) {
                    rows.put(age[0], tnt.size() + " TNT, x spread " + String.format("%.3f", tnt.isEmpty() ? 0.0 : maxX - minX));
                }
                if (age[0] < 85) {
                    helper.runAfterDelay(1, TestSupport.once(step[0]));
                    return;
                }
                CauseTrackingTest.forceChunks(helper, false);
                unforce.run();
                LOG.info("[ghostpath] duper plain run (no mod): {}", rows);
                helper.succeed();
            };
            helper.runAfterDelay(1, TestSupport.once(step[0]));
        })), 0);
    }

    /** The same pull without the mod: what the recording must agree with. */
    @GameTest(maxTicks = 120000, padding = 24, environment = "ghostpath-gametest:duper_plain")
    public void theGameAloneDoesTheSame(GameTestHelper helper) {
        plain(helper, 0);
    }

    /** The piston that changes the machine without moving by itself (found by {@link #explorePistons}): next to the observer at (13,1,1). */
    static final BlockPos NEW_PISTON = new BlockPos(12, 1, 1);

    private static List<String> shape(obsidivision.ghostpath.preview.PathSnapshot s) {
        return s.lines().stream().filter(l -> l.type().equals("minecraft:tnt")).map(l -> l.type() + " x" + l.count() + " t+" + l.ticks()[0] + "..t+" + l.end().tick() + " " + l.end().reason() + " " + Math.round(l.end().y() * 100) / 100.0).sorted().toList();
    }

    /** The piston added in the real world after the recording: the watch reruns, the real machine stays idle, the prediction changes. */
    @GameTest(maxTicks = 240000, padding = 24, environment = "ghostpath-gametest:duper_rerun")
    public void aPistonNextToAnObserverChangesTheDuperRerun(GameTestHelper helper) {
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        Runnable unforce = TestSupport.forceAround(helper, 4);
        floor(helper);
        Litematic lit = load();
        ServerLevel level = helper.getLevel();
        place(helper, lit, ORIGIN);
        addTrigger(helper, false);
        LiveRerunTest.Capture chat = new LiveRerunTest.Capture();
        List<BlockPos> corners = List.of(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(20, 0, -6), ORIGIN.offset(-6, 0, 12), ORIGIN.offset(20, 0, 12), ORIGIN.offset(7, 2, 1));
        LiveRerunTest.whenTicking(helper, corners, TestSupport.once(() -> TestSupport.later(helper, SETTLE_TICKS, () ->
            LiveRerunTest.recordAndWatch(helper, level, chat, ORIGIN.offset(LEVER), (rec, watch) -> {
                try {
                    obsidivision.ghostpath.preview.PathSnapshot before = obsidivision.ghostpath.preview.PathStore.latest();
                    helper.assertTrue(before.source().equals("recorded"), "snapshot source " + before.source());
                    BlockPos piston = helper.absolutePos(ORIGIN.offset(NEW_PISTON));
                    helper.assertTrue(watch.footprint().hit(piston), "the new piston's cell is not in the footprint: " + watch.footprint());
                    helper.setBlock(ORIGIN.offset(NEW_PISTON), Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.WEST));
                    BlockPos lever = helper.absolutePos(ORIGIN.offset(LEVER));
                    LiveRerunTest.waitFor(helper, "the rerun with the new piston", 60000, () -> {
                        helper.assertTrue(!level.getBlockState(lever).getValue(LeverBlock.POWERED), "the real lever was pulled");
                        helper.assertTrue(level.getEntitiesOfClass(PrimedTnt.class, machineBox(helper)).isEmpty(), "a real TNT was lit");
                    }, () -> watch.reruns() >= 1, () -> {
                        try {
                            obsidivision.ghostpath.watch.Prediction p = watch.latest();
                            obsidivision.ghostpath.preview.PathSnapshot after = obsidivision.ghostpath.preview.PathStore.latest();
                            String line = chat.lines.stream().filter(l -> l.startsWith("rerun:")).findFirst().orElse("(no chat line)");
                            LOG.info("[ghostpath] duper rerun: {}", line);
                            LOG.info("[ghostpath] duper rerun: {} ticks in {} server ticks, {} ms (ghost created in {} ms, copy {} ms, longest slice {} ms); TNT spawned {} (was {})",
                                p.ticks(), p.serverTicks(), p.millis(), p.createMillis(), p.copyMillis(), p.maxSliceMillis(), p.tntSpawned(), rec.tntSpawned());
                            LOG.info("[ghostpath] duper rerun: before {}", shape(before));
                            LOG.info("[ghostpath] duper rerun: after  {}", shape(after));
                            double diff40 = 0.0;
                            double diff79 = 0.0;
                            for (obsidivision.ghostpath.preview.PathSnapshot.Line a : before.lines()) {
                                for (obsidivision.ghostpath.preview.PathSnapshot.Line b : after.lines()) {
                                    if (a.type().equals("minecraft:tnt") && b.type().equals("minecraft:tnt") && a.members()[0] == b.members()[0]) {
                                        for (long t : new long[] {40, 79}) {
                                            int i = a.indexOfTick(t);
                                            int j = b.indexOfTick(t);
                                            if (i >= 0 && j >= 0) {
                                                for (int k = 0; k < 3; k++) {
                                                    double d = Math.abs(a.xyz()[3 * i + k] - b.xyz()[3 * j + k]);
                                                    if (t == 40) {
                                                        diff40 = Math.max(diff40, d);
                                                    } else {
                                                        diff79 = Math.max(diff79, d);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            LOG.info("[ghostpath] duper rerun: largest TNT position difference before/after: {} at t+40, {} at t+79", diff40, diff79);
                            helper.assertTrue(after.source().equals("rerun") && after.serial() != before.serial(), "no new snapshot from the rerun");
                            helper.assertTrue(!shape(after).equals(shape(before)), "the piston did not change the prediction");
                            helper.assertTrue(line.startsWith("rerun:") && line.contains("changed since last:"), "chat line: " + line);
                            helper.assertTrue(!level.getBlockState(lever).getValue(LeverBlock.POWERED) && level.getEntitiesOfClass(PrimedTnt.class, machineBox(helper)).isEmpty(), "the real machine fired");
                        } finally {
                            LiveRerunTest.finish(helper);
                    unforce.run();
                        }
                        helper.succeed();
                    });
                } catch (RuntimeException e) {
                    LiveRerunTest.finish(helper);
                    unforce.run();
                    throw e;
                }
            }))), 0);
    }
}
