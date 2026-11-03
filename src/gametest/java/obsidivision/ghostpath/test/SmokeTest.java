package obsidivision.ghostpath.test;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

public class SmokeTest implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void blocksWork(TestContext ctx) {
        ctx.setBlockState(new BlockPos(0, 1, 0), Blocks.STONE);
        ctx.expectBlock(Blocks.STONE, new BlockPos(0, 1, 0));
        ctx.complete();
    }
}
