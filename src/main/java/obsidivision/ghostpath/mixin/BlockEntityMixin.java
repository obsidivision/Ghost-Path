package obsidivision.ghostpath.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    /**
     * 26.2 BlockEntity.java:194 {@code public void setChanged()}. Chain work that changes a block entity makes it part of
     * the chain (its own ticks become chain work) and counts as a write. Not covered: callers of the static
     * {@code setChanged(Level, BlockPos, BlockState)} (:200) that bypass the instance method.
     */
    @Inject(method = "setChanged()V", at = @At("HEAD"))
    private void ghostpath$beforeSetChanged(CallbackInfo ci) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onBlockEntityChanged((BlockEntity) (Object) this);
        }
        // undo log (R4): setChanged() runs after the change, so a first touch seen here is a late snapshot (reported)
        UndoLog log = UndoLog.active();
        if (log != null) {
            log.onBlockEntityChanged((BlockEntity) (Object) this);
        }
    }
}
