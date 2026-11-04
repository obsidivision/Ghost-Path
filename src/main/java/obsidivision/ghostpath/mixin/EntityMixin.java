package obsidivision.ghostpath.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
    /** 26.2 Entity.java:3794 {@code public final void setPosRaw(double, double, double)}: every position change ends here. */
    @Inject(method = "setPosRaw", at = @At("HEAD"))
    private void ghostpath$beforeMove(double x, double y, double z, CallbackInfo ci) {
        this.ghostpath$touch();
        this.ghostpath$chainTouch("moved");
    }

    /**
     * 26.2 Entity.java:3722 {@code public void setDeltaMovement(Vec3)}. The (double,double,double) overload
     * (:3734) and addDeltaMovement (:3727) both call this one.
     */
    @Inject(method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void ghostpath$beforeVelocityChange(Vec3 deltaMovement, CallbackInfo ci) {
        this.ghostpath$touch();
        this.ghostpath$chainTouch("velocity"); // push(...) ends here too (Entity.java:1908-1910)
    }

    /** 26.2 Entity.java:3920 {@code public final void setRemoved(Entity.RemovalReason)}. HEAD: still intact. */
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void ghostpath$beforeRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        this.ghostpath$touch();
        // gaps 7 and 8: UNLOADED_TO_CHUNK (PersistentEntitySectionManager.java:224) and CHANGED_DIMENSION
        // (Entity.removeAfterChangingDimensions, Entity.java:3175) are logged for chain entities
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onEntityRemoved((Entity) (Object) this, reason);
        }
    }

    private void ghostpath$chainTouch(String why) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onEntityTouched((Entity) (Object) this, why);
        }
    }

    private void ghostpath$touch() {
        UndoLog log = UndoLog.active();
        if (log != null) {
            log.onEntityTouched((Entity) (Object) this);
        }
    }
}
