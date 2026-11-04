package obsidivision.ghostpath.test;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import obsidivision.ghostpath.ghost.GhostCopy;
import obsidivision.ghostpath.ghost.GhostLevel;
import obsidivision.ghostpath.mixin.LevelTicksAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saved scheduled ticks of a chunk taken from the save (item 3 of the ghost-from-save change). A saved tick stores its delay relative
 * to the save (SavedTick.java:12, ScheduledTick.toSavedTick) and a chunk turns it into a trigger tick with the level's game time at the
 * moment it loads (ServerLevel.java:1751). The ghost must give it "recording start + remaining delay": one repeater with a scheduled
 * tick in chunk B, saved, copied from the save into a ghost whose start time is 5000 ticks away from the real game time; the ghost's
 * tick must trigger at start + the real remaining delay, and the repeater must turn on exactly then.
 */
public class GhostDiskTicksTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    @GameTest(maxTicks = 40000, padding = 24, environment = "ghostpath-gametest:ghost_disk_ticks")
    public void aSavedRepeaterTickKeepsItsRemainingDelay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos a = new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4);
        ChunkPos b = new ChunkPos(a.x() + 1, a.z());
        level.setChunkForced(a.x(), a.z(), true);
        level.setChunkForced(b.x(), b.z(), true);
        whenLoaded(helper, level, a, b, 0, () -> TestSupport.whenSavesFree(helper, TestSupport.once(() -> run(helper, level, origin, a, b))));
    }

    @SuppressWarnings("unchecked")
    private static List<ScheduledTick<Block>> ticksAt(LevelTicks<Block> ticks, BlockPos pos) {
        List<ScheduledTick<Block>> out = new ArrayList<>();
        Long2ObjectMap<LevelChunkTicks<Block>> all = ((LevelTicksAccessor<Block>) (Object) ticks).ghostpath$allContainers();
        for (LevelChunkTicks<Block> c : all.values()) {
            c.getAll().filter(t -> t.pos().equals(pos)).forEach(out::add);
        }
        return out;
    }

    private static void run(GameTestHelper helper, ServerLevel level, BlockPos origin, ChunkPos a, ChunkPos b) {
        int y = origin.getY();
        BlockPos repeater = new BlockPos(b.getMinBlockX() + 5, y, a.getMinBlockZ() + 8);
        // the repeater faces east; powered by a redstone block on its input side (FACING, DiodeBlock.getInputSignal): its neighbour update schedules the on-tick (RepeaterBlock/DiodeBlock.checkTickOnNeighbor)
        level.setBlock(repeater.below(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(repeater, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.EAST).setValue(RepeaterBlock.DELAY, 3), 2);
        level.setBlock(repeater.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        List<ScheduledTick<Block>> realTicks = ticksAt(level.getBlockTicks(), repeater);
        helper.assertTrue(realTicks.size() == 1, "the real level should hold one tick for the repeater, has " + realTicks.size());
        long remaining = realTicks.getFirst().triggerTick() - level.getGameTime();
        helper.assertTrue(remaining >= 4, "remaining delay of the real tick: " + remaining);
        level.getChunkSource().save(true); // B (with the tick, saved as a delay) is on disk now
        long start = level.getGameTime() + 5000L;
        GhostLevel ghost;
        GhostCopy.Prepared prepared;
        try {
            ghost = GhostLevel.create(level);
            prepared = GhostCopy.prepare(level, ghost, a, b, new GhostCopy.Start(null, null, start), true, Set.of(b));
        } catch (java.io.IOException | RuntimeException e) {
            release(level, a, b);
            throw new IllegalStateException(e);
        }
        poll(helper, level, ghost, prepared, a, b, new int[] {0}, repeater, start, remaining);
    }

    private static void poll(GameTestHelper helper, ServerLevel level, GhostLevel ghost, GhostCopy.Prepared prepared, ChunkPos a, ChunkPos b, int[] polls, BlockPos repeater, long start, long remaining) {
        polls[0]++;
        try {
            if (!prepared.poll()) {
                if (polls[0] > 3000) {
                    throw new IllegalStateException("the ghost chunks were not ready after 3000 polls");
                }
                helper.runAfterDelay(1, () -> poll(helper, level, ghost, prepared, a, b, polls, repeater, start, remaining));
                return;
            }
            prepared.finish();
            helper.assertTrue(ghost.getGameTime() == start, "ghost game time " + ghost.getGameTime() + ", start " + start);
            List<ScheduledTick<Block>> g = ticksAt(ghost.getBlockTicks(), repeater);
            helper.assertTrue(g.size() == 1, "the ghost should hold the saved tick of the repeater, has " + g.size());
            long trigger = g.getFirst().triggerTick();
            LOG.info("[ghostpath] saved tick: remaining delay in the real level {}, ghost start {}, ghost trigger {} (remaining {})", remaining, start, trigger, trigger - start);
            helper.assertTrue(trigger == start + remaining, "ghost trigger " + trigger + " should be start " + start + " + remaining " + remaining + " = " + (start + remaining) + ", is start + " + (trigger - start));
            // behaviour: the game time is raised first and the ticks due at that time run in the same tick (ServerLevel.tick), so the
            // repeater is still off after remaining - 1 ghost ticks and on after remaining
            for (long i = 0; i < remaining - 1; i++) {
                ghost.tickGhost();
                helper.assertTrue(!ghost.getBlockState(repeater).getValue(DiodeBlock.POWERED), "repeater already on after " + (i + 1) + " ghost ticks (game time " + ghost.getGameTime() + ")");
            }
            ghost.tickGhost();
            helper.assertTrue(ghost.getBlockState(repeater).getValue(DiodeBlock.POWERED), "repeater not on after the remaining delay (game time " + ghost.getGameTime() + ")");
            ghost.discard();
            release(level, a, b);
            helper.succeed();
        } catch (RuntimeException e) {
            ghost.discard();
            release(level, a, b);
            throw e;
        }
    }

    private static void release(ServerLevel level, ChunkPos a, ChunkPos b) {
        level.setChunkForced(a.x(), a.z(), false);
        level.setChunkForced(b.x(), b.z(), false);
    }

    private static void whenLoaded(GameTestHelper helper, ServerLevel level, ChunkPos a, ChunkPos b, int waited, Runnable then) {
        boolean ok = true;
        for (ChunkPos c : new ChunkPos[] {a, b}) {
            ok &= level.getChunkSource().getChunkNow(c.x(), c.z()) != null && level.areEntitiesLoaded(c.pack()) && level.areEntitiesActuallyLoadedAndTicking(c);
        }
        if (ok) {
            then.run();
        } else if (waited > 30000) {
            helper.fail("chunks not loaded");
        } else {
            helper.runAfterDelay(1, () -> whenLoaded(helper, level, a, b, waited + 1, then));
        }
    }
}
