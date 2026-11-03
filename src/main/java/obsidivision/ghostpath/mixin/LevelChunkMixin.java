package obsidivision.ghostpath.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    /**
     * 26.2 LevelChunk.java:268 {@code public @Nullable BlockState setBlockState(BlockPos, BlockState, @UpdateFlags int)}.
     * This is the lowest point every block write goes through (Level.setBlock calls it at Level.java:233).
     * HEAD, so the old state and block entity are still intact.
     */
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void ghostpath$beforeSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        UndoLog log = UndoLog.active();
        if (log != null) {
            log.onBlockChange((LevelChunk) (Object) this, pos);
        }
    }

    /**
     * 26.2 LevelChunk.java:368 {@code getBlockEntity(BlockPos, LevelChunk.EntityCreationType)}; Level.getBlockEntity
     * (Level.java:700-707) ends here. RETURN: the entity is handed out before the caller can change it.
     */
    @Inject(
        method = "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
        at = @At("RETURN")
    )
    private void ghostpath$afterGetBlockEntity(BlockPos pos, LevelChunk.EntityCreationType type, CallbackInfoReturnable<BlockEntity> cir) {
        UndoLog log = UndoLog.active();
        BlockEntity be = cir.getReturnValue();
        if (log != null && be != null) {
            log.onBlockEntityAccess(be);
        }
    }
}
