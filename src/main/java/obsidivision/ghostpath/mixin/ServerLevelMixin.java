package obsidivision.ghostpath.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    /**
     * 26.2 ServerLevel.java:992 {@code private boolean addEntity(Entity)}. addFreshEntity (:957), addWithUUID (:961)
     * and the passenger variants all end here; chunk-loaded entities do not (they go through the entity manager).
     * RETURN: only entities that were really added.
     */
    @Inject(method = "addEntity", at = @At("RETURN"))
    private void ghostpath$afterAddEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        UndoLog log = UndoLog.active();
        if (log != null && cir.getReturnValueZ()) {
            log.onEntityAdded(entity);
        }
    }
}
