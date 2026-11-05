package obsidivision.ghostpath.client.mixin;

import net.minecraft.client.MouseHandler;
import obsidivision.ghostpath.client.Inspector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the inspect key is held and something is hovered, the scroll wheel steps through the stack instead of moving the hotbar.
 * 26.2 MouseHandler.java:185 {@code private void onScroll(long handle, double xoffset, double yoffset)}.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerInspectMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void ghostpath$inspectScroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        if (Inspector.onScroll(yoffset)) {
            ci.cancel();
        }
    }
}
