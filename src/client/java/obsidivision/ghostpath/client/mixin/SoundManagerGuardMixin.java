package obsidivision.ghostpath.client.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import obsidivision.ghostpath.preview.PreviewSim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Second line behind {@link ClientLevelPreviewMixin}: 26.2 SoundManager.java:182 {@code public SoundEngine.PlayResult play(SoundInstance)}
 * is where every client sound is started. During a continuation tick nothing may get here; if one does it is dropped and counted.
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerGuardMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void ghostpath$noSounds(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (PreviewSim.active()) {
            PreviewSim.leakedSounds++;
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }
}
