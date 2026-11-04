package obsidivision.ghostpath.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import obsidivision.ghostpath.preview.PreviewSim;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flight preview (step 5): while the client continues a projectile with a copy of its entity ({@link PreviewSim#active()},
 * on the client thread, around one {@code tick()} call), the client level shows nothing and plays nothing. The funnels, all
 * in 26.2 ClientLevel.java:
 * <ul>
 * <li>{@code private void doAddParticle(...)} (:913): every {@code addParticle} and {@code addAlwaysVisibleParticle} overload ends here;</li>
 * <li>{@code private void playSound(double, double, double, SoundEvent, SoundSource, float, float, boolean, long)} (:737): both
 *     {@code playLocalSound(double...)} and {@code playSeededSound} that plays for the local player end here;</li>
 * <li>{@code public void playLocalSound(Entity, SoundEvent, SoundSource, float, float)} (:714): the entity-bound sound;</li>
 * <li>{@code public void levelEvent(Entity, int, BlockPos, int)} (:863) and {@code globalLevelEvent} (:859): splash, break and similar effects.</li>
 * </ul>
 * Outside the continuation these do nothing: the flag is false.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelPreviewMixin {
    @Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
    private void ghostpath$noParticles(ParticleOptions particle, boolean overrideLimiter, boolean alwaysShowParticles, double x, double y, double z,
                                       double xd, double yd, double zd, CallbackInfo ci) {
        if (PreviewSim.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
    private void ghostpath$noSound(double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay,
                                   long seed, CallbackInfo ci) {
        if (PreviewSim.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "playLocalSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", at = @At("HEAD"), cancellable = true)
    private void ghostpath$noEntitySound(Entity sourceEntity, SoundEvent sound, SoundSource source, float volume, float pitch, CallbackInfo ci) {
        if (PreviewSim.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
    private void ghostpath$noLevelEvent(@Nullable Entity entity, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (PreviewSim.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "globalLevelEvent", at = @At("HEAD"), cancellable = true)
    private void ghostpath$noGlobalLevelEvent(int type, BlockPos pos, int data, CallbackInfo ci) {
        if (PreviewSim.active()) {
            ci.cancel();
        }
    }
}
