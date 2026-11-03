package obsidivision.ghostpath.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Read footprint (1c): block reads by chain work through the level. ServerLevel overrides none of these three (26.2).
 * Entity block collisions do not come through here (they read the chunk directly): see BlockCollisionsMixin.
 */
@Mixin(Level.class)
public abstract class LevelReadMixin {
    /** 26.2 Level.java:368 {@code public BlockState getBlockState(BlockPos)}. */
    @Inject(method = "getBlockState", at = @At("HEAD"))
    private void ghostpath$readBlock(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onRead((Level) (Object) this, pos, CauseTracker.ReadKind.BLOCK);
        }
    }

    /** 26.2 Level.java:378 {@code public FluidState getFluidState(BlockPos)} (reads the chunk, not getBlockState, :383-384). */
    @Inject(method = "getFluidState", at = @At("HEAD"))
    private void ghostpath$readFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onRead((Level) (Object) this, pos, CauseTracker.ReadKind.FLUID);
        }
    }

    /** 26.2 Level.java:700 {@code public @Nullable BlockEntity getBlockEntity(BlockPos)}. */
    @Inject(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", at = @At("HEAD"))
    private void ghostpath$readBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onRead((Level) (Object) this, pos, CauseTracker.ReadKind.BLOCK_ENTITY);
        }
    }
}
