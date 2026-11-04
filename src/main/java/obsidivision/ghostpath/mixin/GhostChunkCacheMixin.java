package obsidivision.ghostpath.mixin;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import obsidivision.ghostpath.ghost.GhostLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class GhostChunkCacheMixin {
    @Shadow
    @Final
    private ServerLevel level;

    /**
     * 26.2 ServerChunkCache.java:369 {@code tickChunks(ProfilerFiller, long)}: natural spawning (:376-399), random ticks
     * and precipitation ({@code level.tickChunk}, :402, ServerLevel.java:497-535), thunder (:415-417) and custom spawners
     * (:404-407). A ghost has random ticks and mob spawning off (DESIGN pipeline step 2), so none of it runs there. Also
     * skipped there: the spawning-chunk shuffle's draw from the ghost's own Level.random (:392) and inhabited time (:414).
     */
    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At("HEAD"), cancellable = true)
    private void ghostpath$noRandomTicksInGhost(ProfilerFiller profiler, long timeDiff, CallbackInfo ci) {
        if (this.level instanceof GhostLevel) {
            ci.cancel();
        }
    }
}
