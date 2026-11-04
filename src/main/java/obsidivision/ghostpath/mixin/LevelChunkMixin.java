package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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
            log.onBlockChange((LevelChunk) (Object) this, pos, state);
        }
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onBlockWrite((LevelChunk) (Object) this, pos, state);
        }
    }

    /**
     * 26.2 LevelChunk.java:268 {@code setBlockState}: returns the old state when the block changed and null when it did not
     * (:273, :281, :323 return null). RETURN, so the new state is in place. Live rerun (step 4): a real change at a watched
     * machine's footprint starts the quiet period before a rerun. The ghost's own writes carry another dimension key and are
     * ignored there.
     */
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void ghostpath$afterSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null && obsidivision.ghostpath.watch.LiveWatch.active()
            && ((LevelChunk) (Object) this).getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            obsidivision.ghostpath.watch.LiveWatch.onBlockChanged(level, pos);
        }
    }

    /**
     * 26.2 LevelChunk.java:344, in setBlockState: {@code blockEntity.setBlockState(state)}, the branch where the block
     * entity is kept across a state change (same block, e.g. a hopper's ENABLED or FACING). Chain work there tags the
     * block entity (ruling, gap 1). Its undo-log snapshot was already taken at HEAD of setBlockState (captureBlock saves
     * the state and the block entity NBT of the position before the change).
     */
    @WrapOperation(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setBlockState(Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void ghostpath$keptBlockEntityState(BlockEntity blockEntity, BlockState state, Operation<Void> original) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onBlockEntityStateChanged(((LevelChunk) (Object) this).getLevel(), blockEntity);
        }
        original.call(blockEntity, state);
    }

    /**
     * 26.2 LevelChunk.java:422 {@code public void setBlockEntity(BlockEntity)}; addAndRegisterBlockEntity (:395-396),
     * Level.setBlockEntity (Level.java:710-713) and block-entity creation on lookup (:382-384) all end here. A block
     * entity created by chain work (such as a moving piston) is part of the chain.
     */
    @Inject(method = "setBlockEntity", at = @At("HEAD"))
    private void ghostpath$beforeSetBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onBlockEntitySet(((LevelChunk) (Object) this).getLevel(), blockEntity);
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
