package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.watch.Prediction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rerun whose widening really reaches <b>unloaded</b> chunks (item 5 of the ghost-from-save change; the old widen test forces every
 * chunk). The machine of {@link LiveRerunTest} gets a line of repeaters east of W. Its far end, with a dispenser holding one TNT, lies in
 * chunks that the test unloads (it removes their force tickets and waits until they are gone, the method of
 * {@code RecordingRulesTest.unloadedChunkRestored}) after building it, so it is only in the save. The change that closes the line is
 * made in the loaded part. The rerun must widen, take the unloaded chunks from the save without loading them in the real world (R9),
 * run the line to the dispenser, fire its TNT, and flag that TNT's path as frozen in the real world ("unloaded") from its first point.
 * With {@code /path beyond-loaded off} the same machine is not widened over the unloaded chunks.
 */
public class LiveRerunUnloadedTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    private static final int LINE_END = 82;

    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:live_rerun_unloaded")
    public void widenReachesUnloadedChunks(GameTestHelper helper) {
        run(helper, true);
    }

    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:live_rerun_unloaded_off")
    public void beyondLoadedOffDoesNotReadTheSave(GameTestHelper helper) {
        run(helper, false);
    }

    private static void force(GameTestHelper helper, int fromRelX, int toRelX, boolean on) {
        BlockPos min = helper.absolutePos(new BlockPos(fromRelX, 0, -8));
        BlockPos max = helper.absolutePos(new BlockPos(toRelX, 0, 14));
        for (int cx = Math.min(min.getX(), max.getX()) >> 4; cx <= Math.max(min.getX(), max.getX()) >> 4; cx++) {
            for (int cz = Math.min(min.getZ(), max.getZ()) >> 4; cz <= Math.max(min.getZ(), max.getZ()) >> 4; cz++) {
                helper.getLevel().setChunkForced(cx, cz, on);
            }
        }
    }

    private static void run(GameTestHelper helper, boolean beyond) {
        ServerLevel level = helper.getLevel();
        PathSettings.rerunBudgetMs = 5;
        PathSettings.beyondLoaded = beyond;
        LiveRerunTest.build(helper);
        force(helper, -8, 130, true);
        List<BlockPos> corners = new ArrayList<>(LiveRerunTest.CORNERS);
        corners.add(new BlockPos(125, 2, 12));
        corners.add(new BlockPos(100, 2, -6));
        LiveRerunTest.whenTicking(helper, corners, TestSupport.once(() -> {
            for (int x = 9; x <= 90; x++) {
                for (int z = 0; z <= 8; z++) {
                    helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                }
            }
            for (int x = 20; x <= LINE_END; x++) {
                helper.setBlock(new BlockPos(x, 2, 2), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, 1));
            }
            BlockPos disp = new BlockPos(LINE_END + 1, 2, 2);
            helper.setBlock(disp, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.UP));
            ((DispenserBlockEntity) level.getBlockEntity(helper.absolutePos(disp))).setItem(0, new ItemStack(Items.TNT));
            // unload everything east of the chunk of x = 31: remove the force tickets and wait until those chunks are gone. The two chunks
            // next to the last forced one stay loaded at the border levels (32 block ticking, 33 full: "lazy"), the third one away is gone.
            int lastLoadedCx = helper.absolutePos(new BlockPos(31, 2, 2)).getX() >> 4;
            int firstFarCx = lastLoadedCx + 3;
            int farEndCx = helper.absolutePos(new BlockPos(130, 2, 2)).getX() >> 4;
            int czMin = helper.absolutePos(new BlockPos(0, 2, -8)).getZ() >> 4;
            int czMax = helper.absolutePos(new BlockPos(0, 2, 14)).getZ() >> 4;
            int dispCx = helper.absolutePos(disp).getX() >> 4;
            helper.assertTrue(dispCx >= firstFarCx, "the dispenser's chunk is not far enough east");
            for (int cx = lastLoadedCx + 1; cx <= farEndCx; cx++) {
                for (int cz = czMin; cz <= czMax; cz++) {
                    level.setChunkForced(cx, cz, false);
                }
            }
            waitUnloaded(helper, level, firstFarCx, farEndCx, czMin, czMax, 0, () -> afterUnload(helper, level, beyond, disp, firstFarCx, czMin, lastLoadedCx));
        }), 0);
    }

    private static void waitUnloaded(GameTestHelper helper, ServerLevel level, int cx0, int cx1, int cz0, int cz1, int waited, Runnable then) {
        boolean gone = true;
        for (int cx = cx0; cx <= cx1 && gone; cx++) {
            for (int cz = cz0; cz <= cz1 && gone; cz++) {
                gone = level.getChunkSource().getChunkNow(cx, cz) == null;
            }
        }
        if (gone) {
            then.run();
        } else if (TestSupport.overRealTime(helper)) {
            LiveRerunTest.finish(helper);
            helper.fail("the far chunks did not unload");
        } else {
            helper.runAfterDelay(1, () -> waitUnloaded(helper, level, cx0, cx1, cz0, cz1, waited + 1, then));
        }
    }

    private static void afterUnload(GameTestHelper helper, ServerLevel level, boolean beyond, BlockPos relDisp, int firstFarCx, int czMin, int lastLoadedCx) {
        LiveRerunTest.Capture chat = new LiveRerunTest.Capture();
        LiveRerunTest.recordAndWatch(helper, level, chat, LiveRerunTest.LEVER, (rec, watch) -> {
            try {
                // the change that closes the line, in the loaded part
                for (int x = 19; x >= 3; x--) {
                    helper.setBlock(new BlockPos(x, 2, 2), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, 1));
                }
                helper.assertTrue(watch.dirty(), "the repeater next to W was not seen as a footprint change");
                LiveRerunTest.waitFor(helper, "the rerun", 300000, () -> {
                    for (int cx = firstFarCx; cx <= firstFarCx + 1; cx++) {
                        helper.assertTrue(level.getChunkSource().getChunkNow(cx, czMin) == null, "the rerun loaded a far chunk in the real world (R9): " + cx);
                    }
                }, () -> watch.reruns() >= 1, () -> {
                    try {
                        Prediction p = watch.latest();
                        String line = chat.lines.stream().filter(l -> l.contains("rerun:")).findFirst().orElse("-");
                        LOG.info("[ghostpath] unloaded widen test (beyond-loaded {}): {} ticks, area {}..{}, widenings {}, beyond {}, TNT {}, notTicking {}; chat: {}", beyond,
                            p.ticks(), p.copiedMin(), p.copiedMax(), watch.widenings(), p.beyond().size(), p.tntSpawned(), p.notTicking().size(), line);
                        BlockPos disp = helper.absolutePos(relDisp);
                        long dispChunk = ChunkPos.pack(disp.getX() >> 4, disp.getZ() >> 4);
                        if (beyond) {
                            helper.assertTrue(watch.reruns() == 1 && watch.widenings() >= 1 && p.widened(), "reruns " + watch.reruns() + ", widenings " + watch.widenings());
                            helper.assertTrue(p.beyond().isEmpty(), "chain still beyond the area: " + p.beyond());
                            helper.assertTrue(p.footprint().containsPosition(helper.absolutePos(new BlockPos(LINE_END, 2, 2))), "the ghost did not follow the line to its end");
                            helper.assertTrue(p.tntSpawned() == 2, "TNT: the machine's own and the one the dispenser from the save fires: " + p.tntSpawned());
                            helper.assertTrue("unloaded".equals(p.notTicking().get(dispChunk)), "the dispenser's chunk is not flagged unloaded: " + p.notTicking());
                            helper.assertTrue("lazy".equals(p.notTicking().get(ChunkPos.pack(lastLoadedCx + 1, disp.getZ() >> 4))), "the border chunk next to the last forced one is not flagged lazy: " + p.notTicking());
                            helper.assertTrue(p.notTicking().get(ChunkPos.pack(helper.absolutePos(new BlockPos(5, 2, 2)).getX() >> 4, disp.getZ() >> 4)) == null, "the loaded chunk at W is flagged");
                            PathSnapshot s = PathStore.latest();
                            helper.assertTrue(s != null && s.source().equals("rerun"), "no rerun snapshot");
                            List<PathSnapshot.Line> tnts = s.lines().stream().filter(l -> l.type().equals("minecraft:tnt")).toList();
                            helper.assertTrue(tnts.size() == 2, "TNT paths: " + tnts.size());
                            PathSnapshot.Line far = tnts.stream().filter(l -> l.freeze() != null).findFirst().orElse(null);
                            helper.assertTrue(far != null && far.freeze().kind().equals("unloaded") && far.freeze().point() == 0 && tnts.stream().filter(l -> l.freeze() == null).count() == 1,
                                "the dispenser's TNT path is not flagged from its first point (the machine's own is not): " + tnts.stream().map(PathSnapshot.Line::freeze).toList());
                            helper.assertTrue(line.contains("real: frozen at t+") && line.contains("ghost: exploded at t+"), "chat line: " + line);
                        } else {
                            // the loaded border chunks may still be copied (widened over loaded chunks), but nothing comes from the save
                            helper.assertTrue(p.tntSpawned() == 1, "off: only the machine's own TNT (the dispenser's chunk is not in the ghost): " + p.tntSpawned());
                            helper.assertTrue(!p.footprint().containsPosition(helper.absolutePos(new BlockPos(LINE_END, 2, 2))), "off: the line was followed into the unloaded chunks");
                            helper.assertTrue(!p.notTicking().containsValue("unloaded"), "off: a chunk was taken from the save: " + p.notTicking().values());
                            helper.assertTrue(!line.contains("frozen"), "off: chat line: " + line);
                        }
                        for (int cx = firstFarCx; cx <= firstFarCx + 1; cx++) {
                            helper.assertTrue(level.getChunkSource().getChunkNow(cx, czMin) == null, "far chunk loaded after the rerun: " + cx);
                        }
                        PathSettings.beyondLoaded = true;
                        LiveRerunTest.finish(helper);
                        helper.succeed();
                    } catch (RuntimeException e) {
                        PathSettings.beyondLoaded = true;
                        LiveRerunTest.finish(helper);
                        throw e;
                    }
                });
            } catch (RuntimeException e) {
                PathSettings.beyondLoaded = true;
                LiveRerunTest.finish(helper);
                throw e;
            }
        });
    }
}
