package obsidivision.ghostpath.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import obsidivision.ghostpath.ghost.GhostLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.server.level.ServerLevel;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerLookupMixin {
    /**
     * 26.2 MinecraftServer.java:1191 {@code public @Nullable ServerLevel getLevel(ResourceKey<Level>)}. The ghost is not
     * registered (DESIGN step 2), so asking for its key gives null. Code that looks its level up this way (dimension
     * changes, ...) therefore does not find the ghost: noted as a gap for the check.
     */
    @Inject(method = "getLevel", at = @At("HEAD"))
    private void ghostpath$noteGhostLookup(ResourceKey<Level> dimension, CallbackInfoReturnable<ServerLevel> cir) {
        if (GhostLevel.KEY.equals(dimension)) {
            GhostLevel.onLookupByKey();
        }
    }
}
