package obsidivision.ghostpath.test;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.preview.AreaBuilder;
import obsidivision.ghostpath.preview.AreaData;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.test.LiveRerunTest.Capture;
import obsidivision.ghostpath.watch.LiveWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The ghost area overlay's data: merged boxes, the cap, the watched layer, chunk marking, and the snapshots of a recording and a rerun. */
public class AreaTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    private static LongOpenHashSet set(List<BlockPos> ps) {
        LongOpenHashSet s = new LongOpenHashSet();
        ps.forEach(p -> s.add(p.asLong()));
        return s;
    }

    /** The boxes cover exactly the positions: same count, no block twice, every block of every box is in the set. */
    private static void assertTiles(GameTestHelper helper, String what, LongOpenHashSet positions, List<AreaData.Box> boxes) {
        LongOpenHashSet seen = new LongOpenHashSet();
        for (AreaData.Box b : boxes) {
            for (int x = b.x0(); x < b.x1(); x++) {
                for (int y = b.y0(); y < b.y1(); y++) {
                    for (int z = b.z0(); z < b.z1(); z++) {
                        helper.assertTrue(seen.add(BlockPos.asLong(x, y, z)), what + ": block " + x + "," + y + "," + z + " is in two boxes");
                    }
                }
            }
        }
        helper.assertTrue(seen.equals(positions), what + ": the boxes cover " + seen.size() + " blocks, the footprint has " + positions.size());
    }

    @GameTest(maxTicks = 200, environment = "ghostpath-gametest:area_merge")
    public void boxesTileTheFootprintExactly(GameTestHelper helper) {
        List<BlockPos> solid = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    solid.add(new BlockPos(x, y, z));
                }
            }
        }
        List<AreaData.Box> one = AreaBuilder.merge(set(solid), true);
        helper.assertTrue(one.size() == 1 && one.getFirst().volume() == 64, "a solid 4x4x4 is one box: " + one);
        List<BlockPos> line = new ArrayList<>();
        for (int x = -5; x < 6; x++) {
            line.add(new BlockPos(x, 70, -3));
        }
        helper.assertTrue(AreaBuilder.merge(set(line), false).size() == 1, "a line is one box");
        List<BlockPos> plus = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, -1, 0), new BlockPos(0, 0, 1), new BlockPos(0, 0, -1));
        List<AreaData.Box> p = AreaBuilder.merge(set(plus), false);
        assertTiles(helper, "plus", set(plus), p);
        helper.assertTrue(p.size() == 5, "a 3D plus merges into 5 boxes (row, two z, two y): " + p.size());
        List<BlockPos> checker = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    if ((x + y + z) % 2 == 0) {
                        checker.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        helper.assertTrue(AreaBuilder.merge(set(checker), false).size() == 32, "a checkerboard is all single blocks");
        // random clouds, including negative coordinates: exact tiling, never more boxes than blocks
        Random random = new Random(7);
        for (int round = 0; round < 6; round++) {
            LongOpenHashSet s = new LongOpenHashSet();
            for (int i = 0; i < 1500; i++) {
                s.add(BlockPos.asLong(random.nextInt(14) - 7, random.nextInt(10) - 5, random.nextInt(14) - 7));
            }
            List<AreaData.Box> boxes = AreaBuilder.merge(s, false);
            assertTiles(helper, "random cloud " + round, s, boxes);
            helper.assertTrue(boxes.size() < s.size(), "no merging in a dense cloud: " + boxes.size() + " boxes for " + s.size() + " blocks");
            LOG.info("[ghostpath] area merge: random cloud of {} blocks -> {} boxes", s.size(), boxes.size());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200, environment = "ghostpath-gametest:area_cap")
    public void theCapKeepsTheWrittenBoxesAndSaysSo(GameTestHelper helper) {
        List<BlockPos> writes = new ArrayList<>();
        List<BlockPos> reads = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            writes.add(new BlockPos(i * 2, 64, 0)); // isolated: every second block
        }
        for (int i = 0; i < 3000; i++) {
            reads.add(new BlockPos(i * 2, 64, 5));
        }
        reads.addAll(writes); // reads include the writes: they are drawn as writes only
        long t0 = System.nanoTime();
        AreaData a = AreaBuilder.build(writes, reads, 2, null, null, null);
        LOG.info("[ghostpath] area cap: built in {} ms: {} boxes of {}", (System.nanoTime() - t0) / 1_000_000, a.boxes().size(), a.totalBoxes());
        helper.assertTrue(a.totalBoxes() == 3100 && a.boxes().size() == AreaBuilder.MAX_BOXES && a.capped(), "total " + a.totalBoxes() + ", drawn " + a.boxes().size());
        helper.assertTrue(a.boxes().stream().filter(AreaData.Box::write).count() == 100 && a.boxes().subList(0, 100).stream().allMatch(AreaData.Box::write), "the written boxes are not all drawn, first");
        helper.assertTrue(a.writeBlocks() == 100 && a.readBlocks() == 3000, "counts " + a.writeBlocks() + "/" + a.readBlocks());
        // not capped below the cap
        AreaData b = AreaBuilder.build(writes, writes, 2, null, null, null);
        helper.assertTrue(!b.capped() && b.boxes().size() == 100, "capped without a reason");
        helper.succeed();
    }

    @GameTest(maxTicks = 200, environment = "ghostpath-gametest:area_watched")
    public void theWatchedLayerIsTheMarginAroundTheWrittenBlocks(GameTestHelper helper) {
        BlockPos w = new BlockPos(10, 64, 10);
        AreaData m2 = AreaBuilder.build(List.of(w), List.of(w), 2, null, null, null);
        helper.assertTrue(m2.watched().size() == 1 && m2.watched().getFirst().volume() == 125 && m2.margin() == 2, "margin 2: " + m2.watched());
        AreaData.Box box = m2.watched().getFirst();
        helper.assertTrue(box.x0() == 8 && box.x1() == 13 && box.y0() == 62 && box.y1() == 67, "box " + box);
        AreaData m0 = AreaBuilder.build(List.of(w), List.of(w), 0, null, null, null);
        helper.assertTrue(m0.watched().stream().mapToLong(AreaData.Box::volume).sum() == 7, "margin 0 is the block and its 6 neighbours");
        AreaData two = AreaBuilder.build(List.of(w, w.offset(20, 0, 0)), List.of(), 2, null, null, null);
        helper.assertTrue(two.watched().size() == 2, "two far apart blocks: two boxes");
        List<BlockPos> many = new ArrayList<>();
        for (int i = 0; i < 12_100; i++) {
            many.add(new BlockPos(i * 10, 64, 0));
        }
        AreaData big = AreaBuilder.build(many, many, 2, null, null, null);
        helper.assertTrue(big.watchedSkipped() && big.watched().isEmpty(), "a watched layer of 1.5 million blocks was built");
        helper.succeed();
    }

    @GameTest(maxTicks = 200, environment = "ghostpath-gametest:area_chunks")
    public void widenedChunksAreMarked(GameTestHelper helper) {
        AreaData a = AreaBuilder.build(List.of(new BlockPos(1, 64, 1)), List.of(), 2, new int[] {0, 0, 3, 2}, new int[] {1, 1, 2, 1}, null);
        List<AreaData.ChunkCell> cells = a.chunkCells();
        helper.assertTrue(cells.size() == 12 && a.widenedChunks() == 10, "cells " + cells.size() + ", widened " + a.widenedChunks());
        helper.assertTrue(cells.stream().filter(c -> !c.widened()).map(c -> c.x() + "," + c.z()).sorted().toList().equals(List.of("1,1", "2,1")), "the machine's own chunks: " + cells);
        AreaData plain = AreaBuilder.build(List.of(new BlockPos(1, 64, 1)), List.of(), 2, new int[] {-2, -2, 0, 0}, null, null);
        helper.assertTrue(plain.chunkCells().size() == 9 && plain.widenedChunks() == 0, "no base: nothing is widened");
        helper.assertTrue(AreaBuilder.build(List.of(), List.of(), 2, null, null, null).chunkCells().isEmpty(), "no rectangle, no cells");
        helper.succeed();
    }

    /** A recording and a rerun publish the area with their paths; the rerun's carries the block change that started it. */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:area_snapshots")
    public void snapshotsCarryTheAreaAndTheLastTrigger(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Capture chat = new Capture();
        RerunTriggersTest.recordM(helper, level, chat, (rec, watch) -> RerunTriggersTest.guard(helper, () -> {
            PathSnapshot recorded = PathStore.latest();
            AreaData a = recorded.area();
            helper.assertTrue(recorded.source().equals("recorded") && a != null && a.change() == null, "recorded snapshot: " + recorded.source() + " / " + a);
            helper.assertTrue(a.writeBlocks() == rec.writes().size() && a.writeBlocks() + a.readBlocks() == rec.footprint().size(), "block counts " + a.writeBlocks() + "/" + a.readBlocks());
            helper.assertTrue(!a.capped() && a.drawnVolume() == rec.footprint().size() && a.boxes().size() < rec.footprint().size(), "boxes " + a.boxes().size() + " cover " + a.drawnVolume());
            helper.assertTrue(a.copied() != null && a.chunkCells().size() >= 1 && a.widenedChunks() == 0, "chunk rectangle " + java.util.Arrays.toString(a.copied()));
            helper.assertTrue(a.boxes().stream().filter(AreaData.Box::write).mapToLong(AreaData.Box::volume).sum() == rec.writes().size(), "write boxes");
            helper.assertTrue(!a.watched().isEmpty() && a.margin() == PathSettings.watchMargin, "watched layer");
            LOG.info("[ghostpath] area (recorded): {} boxes for {} blocks ({} written), watched {} boxes, {} chunks", a.boxes().size(), rec.footprint().size(), a.writeBlocks(), a.watched().size(), a.chunkCells().size());
            // an edit: an observer next to X; after the rerun the snapshot says what started it
            helper.setBlock(RerunTriggersTest.O2, RerunTriggersTest.observerDown());
            LiveRerunTest.waitFor(helper, "the rerun", 60000, () -> { }, () -> watch.reruns() >= 1 && !watch.dirty() && LiveWatch.running() == null, () -> RerunTriggersTest.guard(helper, () -> {
                PathSnapshot rerun = PathStore.latest();
                AreaData r = rerun.area();
                helper.assertTrue(rerun.source().equals("rerun") && r != null && r.change() != null, "rerun snapshot: " + rerun.source() + " / " + r);
                helper.assertTrue(r.change().label().equals("rerun: observer placed") && helper.absolutePos(RerunTriggersTest.O2).equals(r.change().pos()), "change " + r.change());
                helper.assertTrue(System.currentTimeMillis() - r.change().millis() < 5000, "change time");
                helper.assertTrue(r.drawnVolume() == watch.footprint().positions().size() && r.writeBlocks() == watch.footprint().writes().size(), "the rerun's boxes are not its footprint");
                // /path rerun: labelled "forced", at the trigger
                LiveRerunTest.command(level, chat, "path rerun");
                LiveRerunTest.waitFor(helper, "the forced rerun", 60000, () -> { }, () -> watch.reruns() >= 2 && LiveWatch.running() == null, () -> RerunTriggersTest.guard(helper, () -> {
                    AreaData f = PathStore.latest().area();
                    helper.assertTrue(f.change().label().equals("rerun: forced") && f.change().pos().equals(rec.trigger()), "forced change " + f.change());
                    RerunTriggersTest.end(helper);
                    helper.succeed();
                }));
            }));
        }));
    }
}
