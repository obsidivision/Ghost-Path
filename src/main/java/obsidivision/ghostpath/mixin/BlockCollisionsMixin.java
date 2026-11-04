package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity block collisions (DESIGN finding 3): 26.2 BlockCollisions reads {@code chunk.getBlockState(pos)} on the chunk
 * returned by {@code collisionGetter.getChunkForCollisions} (BlockCollisions.java:64-89, read at :89), never Level.getBlockState, so
 * the read footprint needs this hook. Only collisions against a Level are recorded (not, for example, a mob's
 * PathNavigationRegion).
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin {
    /** 26.2 BlockCollisions.java:25. */
    @Shadow
    @Final
    private CollisionGetter collisionGetter;

    /** 26.2 BlockCollisions.java:89, in computeNext (:79): {@code BlockState blockState = chunk.getBlockState(this.pos);}. */
    @WrapOperation(method = "computeNext", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState ghostpath$collisionRead(BlockGetter chunk, BlockPos pos, Operation<BlockState> original) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null && this.collisionGetter instanceof Level level) {
            tracker.onRead(level, pos, CauseTracker.ReadKind.COLLISION);
        }
        return original.call(chunk, pos);
    }

    /**
     * 26.2 BlockCollisions.java:64-77 {@code getChunk(int x, int z)}: null when Level.getChunkForCollisions finds the chunk
     * below FULL (Level.java:770-771, {@code getChunk(x, z, FULL, false)}); computeNext then skips every block there
     * (:87). Recorded as a "no collision: chunk below FULL" event.
     */
    @Inject(method = "getChunk", at = @At("RETURN"))
    private void ghostpath$chunkForCollision(int x, int z, CallbackInfoReturnable<BlockGetter> cir) {
        if (cir.getReturnValue() != null) {
            return;
        }
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null && this.collisionGetter instanceof Level level) {
            tracker.onNoCollisionChunk(level, SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        }
    }
}
