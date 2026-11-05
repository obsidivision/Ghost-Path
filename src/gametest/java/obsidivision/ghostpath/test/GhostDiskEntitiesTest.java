package obsidivision.ghostpath.test;

import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import obsidivision.ghostpath.ghost.GhostCopy;
import obsidivision.ghostpath.ghost.GhostLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entities of a chunk taken from the save (item 4 of the ghost-from-save change). Entities are stored apart from the blocks (the "entities"
 * region files); the ghost gets them the same way as the blocks: read through the real level's own entity storage, written into the ghost's,
 * loaded by the ghost's own entity manager. Chunks A (copied from memory) and B (copied from the save, {@code forceDisk}) each hold a
 * primed TNT and an item; the ghost must hold exactly one of each per chunk, B's with the state they were saved with (fuse, position,
 * item stack), and B's real entities must not be copied a second time from memory.
 */
public class GhostDiskEntitiesTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:ghost_disk_entities")
    public void entitiesOfAChunkFromTheSaveAreInTheGhost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos a = new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4);
        ChunkPos b = new ChunkPos(a.x() + 1, a.z());
        level.setChunkForced(a.x(), a.z(), true);
        level.setChunkForced(b.x(), b.z(), true);
        whenLoaded(helper, level, a, b, 0, () -> TestSupport.whenSavesFree(helper, TestSupport.once(() -> run(helper, level, origin, a, b))));
    }

    private static PrimedTnt tnt(ServerLevel level, double x, double y, double z, int fuse) {
        PrimedTnt t = new PrimedTnt(level, x, y, z, null);
        t.setNoGravity(true);
        t.setDeltaMovement(0.0, 0.0, 0.0);
        t.setFuse(fuse);
        level.addFreshEntity(t);
        return t;
    }

    private static ItemEntity item(ServerLevel level, double x, double y, double z) {
        ItemEntity i = new ItemEntity(level, x, y, z, new ItemStack(Items.DIAMOND, 5));
        i.setNoGravity(true);
        i.setDeltaMovement(0.0, 0.0, 0.0);
        level.addFreshEntity(i);
        return i;
    }

    private static void run(GameTestHelper helper, ServerLevel level, BlockPos origin, ChunkPos a, ChunkPos b) {
        int y = origin.getY() + 3;
        double zc = a.getMinBlockZ() + 8.5;
        inA = List.of(tnt(level, a.getMinBlockX() + 4.5, y, zc, 300).getUUID(), item(level, a.getMinBlockX() + 6.5, y, zc).getUUID());
        PrimedTnt tb = tnt(level, b.getMinBlockX() + 4.5, y, zc, 250);
        ItemEntity ib = item(level, b.getMinBlockX() + 6.5, y, zc);
        level.save(null, true, false); // chunks and entities to disk (ServerLevel.java:873-891; ServerChunkCache.save alone leaves the entities)
        // what B really holds at the save: the game-test world is shared, and B (next to the structure's chunk) can hold
        // entities other tests left there (seen: 15 items), so the ghost is compared with this, not with "one of each"
        savedInB = uuids(level, boxOf(level, b));
        // positions at the save: the real entities go on ticking while the ghost chunks load (how long depends on the
        // machine: seen, the real item 3 blocks away by then), so the ghost is compared with these, not with "now"
        savedTntPos = tb.position();
        savedItemPos = ib.position();
        GhostLevel ghost;
        GhostCopy.Prepared prepared;
        try {
            ghost = GhostLevel.create(level);
            prepared = GhostCopy.prepare(level, ghost, a, b, GhostCopy.Start.NOW, true, Set.of(b));
        } catch (java.io.IOException | RuntimeException e) {
            release(level, a, b);
            throw new IllegalStateException(e);
        }
        poll(helper, level, ghost, prepared, a, b, new int[] {0}, tb, ib);
    }

    private static void poll(GameTestHelper helper, ServerLevel level, GhostLevel ghost, GhostCopy.Prepared prepared, ChunkPos a, ChunkPos b, int[] polls, PrimedTnt realTnt, ItemEntity realItem) {
        polls[0]++;
        try {
            if (!prepared.poll()) {
                if (TestSupport.overRealTime(helper)) {
                    throw new IllegalStateException("the ghost chunks were not ready within the real-time limit (" + TestSupport.REAL_TIME_LIMIT_S + " s)");
                }
                helper.runAfterDelay(1, () -> poll(helper, level, ghost, prepared, a, b, polls, realTnt, realItem));
                return;
            }
            GhostCopy.Report report = prepared.finish();
            LOG.info("[ghostpath] ghost entities from the save: {}", report.lines());
            AABB boxB = new AABB(b.getMinBlockX(), level.getMinY(), b.getMinBlockZ(), b.getMaxBlockX() + 1.0, level.getMaxY(), b.getMaxBlockZ() + 1.0);
            AABB boxA = new AABB(a.getMinBlockX(), level.getMinY(), a.getMinBlockZ(), a.getMaxBlockX() + 1.0, level.getMaxY(), a.getMaxBlockZ() + 1.0);
            List<PrimedTnt> tntB = ghost.getEntitiesOfClass(PrimedTnt.class, boxB, e -> e.getUUID().equals(realTnt.getUUID()));
            List<ItemEntity> itemB = ghost.getEntitiesOfClass(ItemEntity.class, boxB, e -> e.getUUID().equals(realItem.getUUID()));
            helper.assertTrue(tntB.size() == 1, "the test's TNT in B in the ghost: " + tntB.size() + " (0: not read from the save, 2: also copied from memory)");
            helper.assertTrue(itemB.size() == 1, "the test's item in B in the ghost: " + itemB.size());
            List<java.util.UUID> ghostB = uuids(ghost, boxB);
            helper.assertTrue(ghostB.size() == savedInB.size() && new java.util.HashSet<>(ghostB).equals(new java.util.HashSet<>(savedInB)),
                "B's entities in the ghost are not those saved (once each): ghost " + ghostB.size() + ", saved " + savedInB.size());
            helper.assertTrue(uuids(ghost, boxA).stream().filter(inA::contains).count() == 2, "A's entities were not copied from memory");
            PrimedTnt g = tntB.getFirst();
            // the saved state (the real TNT went on ticking while the ghost chunks loaded): the fuse it had when it was saved, the same place
            helper.assertTrue(g.getFuse() == 250 && g.position().equals(savedTntPos), "TNT from the save: fuse " + g.getFuse() + " at " + g.position()
                + ", saved with fuse 250 at " + savedTntPos);
            // the item stack and the position as saved
            helper.assertTrue(itemB.getFirst().getItem().is(Items.DIAMOND) && itemB.getFirst().getItem().getCount() == 5 && itemB.getFirst().position().equals(savedItemPos),
                "item from the save: " + itemB.getFirst().getItem() + " at " + itemB.getFirst().position() + ", saved at " + savedItemPos + ", real item now at " + realItem.position());
            // the ghost ticks them like any other entity: the fuse runs down
            for (int i = 0; i < 10; i++) {
                ghost.tickGhost();
            }
            helper.assertTrue(tntB.getFirst().getFuse() == 240, "the fuse of the TNT from the save did not run: " + tntB.getFirst().getFuse());
            ghost.discard();
            release(level, a, b);
            realTnt.discard();
            realItem.discard();
            helper.succeed();
        } catch (RuntimeException e) {
            ghost.discard();
            release(level, a, b);
            throw e;
        }
    }

    private static List<java.util.UUID> savedInB = List.of();
    private static List<java.util.UUID> inA = List.of();
    private static net.minecraft.world.phys.Vec3 savedTntPos = net.minecraft.world.phys.Vec3.ZERO;
    private static net.minecraft.world.phys.Vec3 savedItemPos = net.minecraft.world.phys.Vec3.ZERO;

    private static AABB boxOf(ServerLevel level, ChunkPos c) {
        return new AABB(c.getMinBlockX(), level.getMinY(), c.getMinBlockZ(), c.getMaxBlockX() + 1.0, level.getMaxY(), c.getMaxBlockZ() + 1.0);
    }

    private static List<java.util.UUID> uuids(ServerLevel level, AABB box) {
        return level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> !(e instanceof net.minecraft.world.entity.player.Player)).stream()
            .map(net.minecraft.world.entity.Entity::getUUID).toList();
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
        } else if (TestSupport.overRealTime(helper)) {
            helper.fail("chunks not loaded");
        } else {
            helper.runAfterDelay(1, () -> whenLoaded(helper, level, a, b, waited + 1, then));
        }
    }
}
