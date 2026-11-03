package obsidivision.ghostpath.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.record.UndoLog;

/** Build step 1a: undo log. A piston pushes a redstone block into TNT; everything must be restored exactly. */
public class UndoLogTest {
    private record EntityState(String type, Vec3 pos, Vec3 motion) {}

    private static final class World {
        final Map<BlockPos, BlockState> blocks = new TreeMap<>();
        final Map<BlockPos, CompoundTag> blockEntities = new TreeMap<>();
        final Map<UUID, EntityState> entities = new HashMap<>();
    }

    private static World capture(ServerLevel level, AABB box) {
        World w = new World();
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockPos pos = p.immutable();
            w.blocks.put(pos, level.getBlockState(pos));
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                w.blockEntities.put(pos, be.saveWithFullMetadata(level.registryAccess()));
            }
        }
        List<Entity> found = level.getEntities(EntityTypeTest.forClass(Entity.class), box.inflate(24), e -> !(e instanceof Player));
        for (Entity e : found) {
            w.entities.put(e.getUUID(), new EntityState(e.getType().toShortString(), e.position(), e.getDeltaMovement()));
        }
        return w;
    }

    /** Returns the first difference, or null if identical. */
    private static String firstDifference(World before, World after) {
        for (Map.Entry<BlockPos, BlockState> e : before.blocks.entrySet()) {
            BlockState now = after.blocks.get(e.getKey());
            if (!e.getValue().equals(now)) {
                return "block at " + e.getKey() + ": before " + e.getValue() + ", after " + now;
            }
        }
        if (!before.blockEntities.keySet().equals(after.blockEntities.keySet())) {
            return "block entity positions: before " + before.blockEntities.keySet() + ", after " + after.blockEntities.keySet();
        }
        for (Map.Entry<BlockPos, CompoundTag> e : before.blockEntities.entrySet()) {
            if (!e.getValue().equals(after.blockEntities.get(e.getKey()))) {
                return "block entity at " + e.getKey() + ": before " + e.getValue() + ", after " + after.blockEntities.get(e.getKey());
            }
        }
        if (!before.entities.keySet().equals(after.entities.keySet())) {
            return "entity set: before " + before.entities + ", after " + after.entities;
        }
        for (Map.Entry<UUID, EntityState> e : before.entities.entrySet()) {
            if (!e.getValue().equals(after.entities.get(e.getKey()))) {
                return "entity " + e.getKey() + ": before " + e.getValue() + ", after " + after.entities.get(e.getKey());
            }
        }
        return null;
    }

    @GameTest(maxTicks = 400)
    public void pistonTntRestoredExactly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pistonRel = new BlockPos(1, 2, 3);
        BlockPos pushedRel = new BlockPos(2, 2, 3);
        BlockPos tntRel = new BlockPos(4, 2, 3);
        BlockPos chestRel = new BlockPos(4, 2, 5);
        BlockPos triggerRel = new BlockPos(0, 2, 3);

        // --- machine, not recorded ---
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        helper.setBlock(pistonRel, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        helper.setBlock(pushedRel, Blocks.REDSTONE_BLOCK);
        helper.setBlock(tntRel, Blocks.TNT);
        helper.setBlock(chestRel, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(chestRel, ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.APPLE, 5));
        chest.setItem(5, new ItemStack(Items.STICK, 3));
        chest.setItem(26, new ItemStack(Items.COBBLESTONE, 64));

        ArmorStand stand = helper.spawn(EntityTypes.ARMOR_STAND, new Vec3(3.5, 2, 5.5));
        ItemEntity item = new ItemEntity(level, helper.absoluteVec(new Vec3(5.5, 2, 3.5)).x, helper.absoluteVec(new Vec3(5.5, 2, 3.5)).y,
            helper.absoluteVec(new Vec3(5.5, 2, 3.5)).z, new ItemStack(Items.APPLE, 2));
        item.setDeltaMovement(Vec3.ZERO);
        level.addFreshEntity(item);

        AABB box = new AABB(helper.absolutePos(new BlockPos(-2, 0, -2)).getX(), helper.absolutePos(new BlockPos(-2, 0, -2)).getY(),
            helper.absolutePos(new BlockPos(-2, 0, -2)).getZ(), helper.absolutePos(new BlockPos(10, 7, 10)).getX(),
            helper.absolutePos(new BlockPos(10, 7, 10)).getY(), helper.absolutePos(new BlockPos(10, 7, 10)).getZ());
        World before = capture(level, box);
        int tntBefore = level.getEntities(EntityTypeTest.forClass(PrimedTnt.class), box.inflate(24), e -> true).size();

        // --- recording ---
        UndoLog log = UndoLog.begin(level);
        // deterministic touches, so that every restore path is exercised even if the explosion rolls kindly
        ChestBlockEntity recordedChest = (ChestBlockEntity) level.getBlockEntity(helper.absolutePos(chestRel));
        recordedChest.setItem(0, ItemStack.EMPTY);
        recordedChest.setItem(7, new ItemStack(Items.DIAMOND, 9));
        stand.setPos(stand.getX() + 1.0, stand.getY(), stand.getZ());
        stand.setDeltaMovement(0.25, 0.5, 0.0);
        item.discard();
        helper.setBlock(triggerRel, Blocks.REDSTONE_BLOCK); // powers the piston from behind

        helper.runAfterDelay(140, () -> {
            try {
                // the run really did something
                helper.assertTrue(!helper.getBlockState(tntRel).is(Blocks.TNT), "TNT block should be gone after the run");
                helper.assertTrue(log.spawnedCount() > 0, "entities should have spawned during the run (primed TNT)");
                helper.assertTrue(log.blockCount() > 3, "several blocks should have been logged, got " + log.blockCount());
                helper.assertTrue(log.entitySnapshotCount() >= 2, "stand and item should be snapshotted, got " + log.entitySnapshotCount());

                log.restore();

                World after = capture(level, box);
                String diff = firstDifference(before, after);
                helper.assertTrue(diff == null, "not restored: " + diff);
                int tntAfter = level.getEntities(EntityTypeTest.forClass(PrimedTnt.class), box.inflate(24), e -> true).size();
                helper.assertTrue(tntAfter == tntBefore, "primed TNT left over: " + tntAfter);
                helper.succeed();
            } catch (RuntimeException ex) {
                log.abandon();
                throw ex;
            }
        });
    }
}
