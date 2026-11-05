package obsidivision.ghostpath.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 26.2 ComparatorBlock.java:158 {@code private void refreshOutputState(Level, BlockPos, BlockState)}, called at the end of a click (:140). */
@Mixin(ComparatorBlock.class)
public interface ComparatorBlockInvoker {
    @Invoker("refreshOutputState")
    void ghostpath$refreshOutputState(Level level, BlockPos pos, BlockState state);
}
