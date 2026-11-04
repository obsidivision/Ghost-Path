package obsidivision.ghostpath.client.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import obsidivision.ghostpath.preview.PreviewSim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Second line behind {@link ClientLevelPreviewMixin}: 26.2 ParticleEngine.java:68 {@code public void add(Particle)} is where every
 * client particle enters the engine. During a continuation tick nothing may get here; if one does (a path that does not go through
 * {@code ClientLevel.doAddParticle}) it is dropped and counted, and the client game test fails on the count.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineGuardMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void ghostpath$noParticles(Particle particle, CallbackInfo ci) {
        if (PreviewSim.active()) {
            PreviewSim.leakedParticles++;
            ci.cancel();
        }
    }
}
