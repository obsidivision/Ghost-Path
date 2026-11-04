package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.level.Level;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Containers.class)
public abstract class ContainersMixin {
    /**
     * 26.2 Containers.java:32-46 {@code dropItemStack(Level, double, double, double, ItemStack)} (a container's contents
     * spilling when it is destroyed): {@code level.getRandom()} at :36 gives position, stack split and velocity.
     */
    @ModifyExpressionValue(method = "dropItemStack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getRandom()Lnet/minecraft/util/RandomSource;"))
    private static RandomSource ghostpath$spillRandom(RandomSource original, @Local(argsOnly = true) Level level) {
        return CauseTracker.randomFor(level, original);
    }
}
