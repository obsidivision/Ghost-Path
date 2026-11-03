package obsidivision.ghostpath.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    /**
     * 26.2 LivingEntity.java:1169 {@code public boolean hurtServer(ServerLevel, DamageSource, float)} (Entity.hurtServer,
     * Entity.java:1931, is abstract). Chain work hurting an entity makes it part of the chain. Non-living entities that
     * override hurtServer themselves are not covered here (they join the chain when pushed, moved or removed).
     */
    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void ghostpath$beforeHurt(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onEntityTouched((LivingEntity) (Object) this, "hurt");
        }
    }
}
