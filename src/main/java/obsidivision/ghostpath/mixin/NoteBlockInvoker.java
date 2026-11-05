package obsidivision.ghostpath.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 26.2 NoteBlock.java:101 {@code private void playNote(@Nullable Entity, BlockState, Level, BlockPos)}: what a click does after the tune (:128). */
@Mixin(NoteBlock.class)
public interface NoteBlockInvoker {
    @Invoker("playNote")
    void ghostpath$playNote(@Nullable Entity source, BlockState state, Level level, BlockPos pos);
}
