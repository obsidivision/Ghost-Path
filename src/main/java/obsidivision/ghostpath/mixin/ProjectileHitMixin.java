package obsidivision.ghostpath.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import obsidivision.ghostpath.preview.PreviewSim;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Projectile.class)
public abstract class ProjectileHitMixin {
    /**
     * 26.2 Projectile.java:245 {@code protected ProjectileDeflection hitTargetOrDeflectSelf(HitResult)}: the one place every
     * projectile hit goes through (ThrowableProjectile.java:63, AbstractArrow.java:295 and :312, AbstractHurtingProjectile.java:93,
     * FireworkRocketEntity.java:163, LlamaSpit.java:47, FishingHook.java:270, ShulkerBullet.java:220); it calls {@code onHit}
     * (:265), which most subclasses override with their effects.
     * <ul>
     * <li>Flight preview (step 5): the client copy being ticked by the continuation records the hit and returns without
     *     running any of it (hit effects off).</li>
     * <li>Chain work: a chain projectile's hit (block or entity) is noted, so the path can end with "hit block".</li>
     * </ul>
     */
    @Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
    private void ghostpath$onHit(HitResult hitResult, CallbackInfoReturnable<ProjectileDeflection> cir) {
        Projectile self = (Projectile) (Object) this;
        if (PreviewSim.simulating(self)) {
            PreviewSim.hit(hitResult);
            cir.setReturnValue(ProjectileDeflection.NONE);
            return;
        }
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) { // onProjectileHit ignores projectiles that are not chain entities of its level
            tracker.onProjectileHit(self, hitResult.getType());
        }
    }
}
