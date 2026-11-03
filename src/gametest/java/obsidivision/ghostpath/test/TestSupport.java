package obsidivision.ghostpath.test;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

final class TestSupport {
    private TestSupport() {
    }

    /**
     * The 26.2 game-test framework encases every test structure in barrier blocks, one block outside its bounds, ceiling
     * included unless skyAccess (GameTestInfo.java:75 encaseStructure; TestInstanceBlockEntity.java:358-363, 374-391); they
     * are removed only after the test passes. Machines larger than the 8x8x8 default structure would have a barrier wall
     * running through them, which no real world has. Clears that shell before building.
     */
    static void removeBarriers(GameTestHelper helper) {
        AABB box = helper.getBounds().inflate(1.0);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (helper.getLevel().getBlockState(pos).is(Blocks.BARRIER)) {
                helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }
}
