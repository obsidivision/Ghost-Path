package obsidivision.ghostpath.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 26.2 TrapDoorBlock.java:112 {@code private void toggle(BlockState, Level, BlockPos, @Nullable Player)} (what a click does, :97) and :52 {@code private final BlockSetType type}. */
@Mixin(TrapDoorBlock.class)
public interface TrapDoorBlockAccessor {
    @Invoker("toggle")
    void ghostpath$toggle(BlockState state, Level level, BlockPos pos, @Nullable Player player);

    @Accessor("type")
    BlockSetType ghostpath$type();
}
