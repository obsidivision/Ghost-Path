package obsidivision.ghostpath.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 LevelChunk.java:728 {@code private class BoundTickingBlockEntity<T extends BlockEntity>}; its {@code tick()}
 * (:739) runs a block entity's own ticker (hoppers, pistons moving, ...), which can change it without any lookup.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
    @Shadow
    @Final
    private BlockEntity blockEntity;

    @Inject(method = "tick", at = @At("HEAD"))
    private void ghostpath$beforeTick(CallbackInfo ci) {
        UndoLog log = UndoLog.active();
        if (log != null) {
            log.onBlockEntityAccess(this.blockEntity);
        }
    }
}
