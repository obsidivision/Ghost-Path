package obsidivision.ghostpath.client.mixin;

import net.minecraft.client.Minecraft;
import obsidivision.ghostpath.client.Inspector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the inspect key is held, a left click is the inspector's (pin or unpin) and attacks nothing. 26.2 Minecraft.java:1700
 * {@code private boolean startAttack()} (a click; returns whether it was an instant attack) and :1675 {@code private void
 * continueAttack(boolean down)} (holding the button to break a block).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftInspectMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void ghostpath$inspectClick(CallbackInfoReturnable<Boolean> cir) {
        if (Inspector.onAttack()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void ghostpath$inspectHold(boolean down, CallbackInfo ci) {
        if (Inspector.inspecting()) {
            ci.cancel();
        }
    }
}
