package obsidivision.ghostpath.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Light reads of the footprint (found by the test machine, which hits them through {@code Level.precipitationAt},
 * Level.java:961, from {@code canSeeSky}). Both default methods of 26.2 {@code BlockAndLightGetter} (:10, :14) read the
 * light engine; {@code canSeeSky} (:18) goes through {@code getBrightness}. {@code Level} overrides none of them.
 * Light read through a {@code LevelLightEngine} directly, or by a getter that is not a {@code Level}, is not recorded.
 */
@Mixin(BlockAndLightGetter.class)
public interface BlockAndLightGetterMixin {
    @Inject(method = "getBrightness", at = @At("HEAD"))
    private void ghostpath$readBrightness(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        record(this, pos);
    }

    @Inject(method = "getRawBrightness", at = @At("HEAD"))
    private void ghostpath$readRawBrightness(BlockPos pos, int darkening, CallbackInfoReturnable<Integer> cir) {
        record(this, pos);
    }

    private static void record(Object getter, BlockPos pos) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null && getter instanceof Level level) {
            tracker.onRead(level, pos, CauseTracker.ReadKind.LIGHT);
        }
    }
}
