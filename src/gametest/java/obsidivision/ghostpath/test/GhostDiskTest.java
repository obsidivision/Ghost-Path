package obsidivision.ghostpath.test;

import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.ghost.GhostCopy;
import obsidivision.ghostpath.ghost.GhostLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ghost chunks taken from the save (the ghost beyond the loaded area): two chunks A and B are loaded and saved, B is copied from its
 * <i>saved data</i> (not from the loaded chunk: {@code forceDisk}); a primed TNT flying east out of A hits a stone wall in B. The ghost's TNT
 * must stop at the wall exactly like the real one, bit for bit, and the chest in B must hold its item.
 */
public class GhostDiskTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    @GameTest(maxTicks = 40000, padding = 24, environment = "ghostpath-gametest:ghost_disk")
    public void aChunkFromTheSaveStopsATntLikeALoadedOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos a = new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4);
        ChunkPos b = new ChunkPos(a.x() + 1, a.z());
        level.setChunkForced(a.x(), a.z(), true);
        level.setChunkForced(b.x(), b.z(), true);
        whenLoaded(helper, level, a, b, 0, () -> TestSupport.whenSavesFree(helper, TestSupport.once(() -> run(helper, level, origin, a, b))));
    }

    private static void run(GameTestHelper helper, ServerLevel level, BlockPos origin, ChunkPos a, ChunkPos b) {
        int y = origin.getY();
        int zc = a.getMinBlockZ() + 8;
        int wallX = b.getMinBlockX() + 6;
        for (int dy = 0; dy < 8; dy++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(new BlockPos(wallX, y + dy, zc + dz), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        BlockPos chest = new BlockPos(b.getMinBlockX() + 3, y, zc + 5);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 2);
        ((ChestBlockEntity) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        PrimedTnt tnt = new PrimedTnt(level, b.getMinBlockX() - 3 + 0.5, y + 3.5, zc + 0.5, null);
        tnt.setDeltaMovement(1.0, 0.0, 0.0);
        tnt.setNoGravity(true);
        tnt.setFuse(400);
        level.addFreshEntity(tnt);
        level.getChunkSource().save(true); // B's blocks are on disk now
        GhostLevel ghost;
        GhostCopy.Prepared prepared;
        try {
            ghost = GhostLevel.create(level);
            prepared = GhostCopy.prepare(level, ghost, a, b, GhostCopy.Start.NOW, true, Set.of(b));
        } catch (java.io.IOException | RuntimeException e) {
            level.setChunkForced(a.x(), a.z(), false);
            level.setChunkForced(b.x(), b.z(), false);
            throw new IllegalStateException(e);
        }
        poll(helper, level, ghost, prepared, a, b, new int[] {0}, tnt, wallX, chest);
    }

    private static void poll(GameTestHelper helper, ServerLevel level, GhostLevel ghost, GhostCopy.Prepared prepared, ChunkPos a, ChunkPos b, int[] polls, PrimedTnt realTnt, int wallX, BlockPos chest) {
        polls[0]++;
        try {
            if (!prepared.poll()) {
                if (polls[0] > 3000) {
                    throw new IllegalStateException("the ghost chunks were not ready after 3000 polls");
                }
                helper.runAfterDelay(1, () -> poll(helper, level, ghost, prepared, a, b, polls, realTnt, wallX, chest));
                return;
            }
            GhostCopy.Report report = prepared.finish();
            LOG.info("[ghostpath] ghost from the save: {}", report.lines());
            // what the ghost got from B's saved data: the wall and the chest's diamonds
            int y = helper.absolutePos(BlockPos.ZERO).getY();
            int zc = a.getMinBlockZ() + 8;
            for (int dy = 0; dy < 8; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos p = new BlockPos(wallX, y + dy, zc + dz);
                    helper.assertTrue(ghost.getBlockState(p).is(Blocks.STONE), "the wall is not in the ghost at " + p.toShortString() + ": " + ghost.getBlockState(p));
                }
            }
            helper.assertTrue(ghost.getBlockEntity(chest) instanceof ChestBlockEntity c && c.getItem(0).is(Items.DIAMOND) && c.getItem(0).getCount() == 3,
                "the chest in the ghost: " + ghost.getBlockEntity(chest));
            run(helper, level, ghost, realTnt, wallX, new int[] {0}, a, b);
        } catch (RuntimeException e) {
            ghost.discard();
            level.setChunkForced(a.x(), a.z(), false);
            level.setChunkForced(b.x(), b.z(), false);
            throw e;
        }
    }

    private static void run(GameTestHelper helper, ServerLevel level, GhostLevel ghost, PrimedTnt realTnt, int wallX, int[] step, ChunkPos a, ChunkPos b) {
        try {
            step[0]++;
            ghost.tickGhost();
            if (step[0] < 14) {
                helper.runAfterDelay(1, () -> run(helper, level, ghost, realTnt, wallX, step, a, b));
                return;
            }
            AABB box = new AABB(a.getMinBlockX() - 2, helper.absolutePos(BlockPos.ZERO).getY() - 2, a.getMinBlockZ() - 2, b.getMaxBlockX() + 2, helper.absolutePos(BlockPos.ZERO).getY() + 12, a.getMaxBlockZ() + 2);
            java.util.List<PrimedTnt> g = ghost.getEntitiesOfClass(PrimedTnt.class, box);
            helper.assertTrue(g.size() == 1, "TNT in the ghost: " + g.size());
            Vec3 rp = realTnt.position();
            Vec3 gp = g.getFirst().position();
            LOG.info("[ghostpath] ghost from the save: after {} ticks the real TNT is at {} and the ghost's at {} (wall at x {})", step[0], rp, gp, wallX);
            helper.assertTrue(rp.x < wallX, "the real TNT went through the wall: " + rp);
            helper.assertTrue(rp.equals(gp) && realTnt.getDeltaMovement().equals(g.getFirst().getDeltaMovement()), "real " + rp + " " + realTnt.getDeltaMovement() + ", ghost " + gp + " " + g.getFirst().getDeltaMovement());
            ghost.discard();
            level.setChunkForced(a.x(), a.z(), false);
            level.setChunkForced(b.x(), b.z(), false);
            helper.succeed();
        } catch (RuntimeException e) {
            ghost.discard();
            level.setChunkForced(a.x(), a.z(), false);
            level.setChunkForced(b.x(), b.z(), false);
            throw e;
        }
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
