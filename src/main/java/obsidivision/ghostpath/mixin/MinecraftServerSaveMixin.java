package obsidivision.ghostpath.mixin;

import net.minecraft.server.MinecraftServer;
import obsidivision.ghostpath.record.SaveGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerSaveMixin {
    /**
     * 26.2 MinecraftServer.java:627 {@code public boolean saveEverything(boolean silent, boolean flush, boolean force)}.
     * Every save that is not forced goes through here with {@code force = false}: the periodic autosave
     * ({@code autoSave}, :1018-1023, called at :991 every autosave interval and at :973 when the server pauses empty) and
     * the singleplayer pause save ({@code IntegratedServer.tickServer}, IntegratedServer.java:153). Forced saves
     * ({@code /save-all}, SaveAllCommand.java:26; server start, IntegratedServer.java:112; DedicatedServer.java:273) pass
     * {@code force = true}, and the stop save calls {@code saveAllChunks} directly (:678). While a recording holds saves
     * ({@link SaveGuard}), a non-forced save is skipped and done once the recording has been restored.
     */
    @Inject(method = "saveEverything", at = @At("HEAD"), cancellable = true)
    private void ghostpath$holdAutosave(boolean silent, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        SaveGuard guard = SaveGuard.active();
        if (guard != null && !force && guard.holdSave()) {
            cir.setReturnValue(false);
        }
    }
}
