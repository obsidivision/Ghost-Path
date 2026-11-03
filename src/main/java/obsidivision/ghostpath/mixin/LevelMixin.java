package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import obsidivision.ghostpath.record.WatchedRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Level.class)
public abstract class LevelMixin {
    /**
     * 26.2 Level.java:124 {@code protected final RandomSource random = RandomSource.create();} (the only
     * {@code RandomSource.create()} in Level). Wrapped so chain draws from it are reported as unseeded (DESIGN R1);
     * the wrapper passes every call through unchanged.
     */
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private RandomSource ghostpath$watchRandom(RandomSource original) {
        return new WatchedRandom(original, (Level) (Object) this);
    }
}
