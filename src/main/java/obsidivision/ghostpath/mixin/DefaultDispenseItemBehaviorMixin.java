package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DefaultDispenseItemBehavior.class)
public abstract class DefaultDispenseItemBehaviorMixin {
    /**
     * 26.2 DefaultDispenseItemBehavior.java:30-48 {@code spawnItem(Level, ItemStack, int, Direction, Position)}
     * (droppers and dispensers spitting an item): {@code level.getRandom()} at :41 gives the launch speed and spread.
     */
    @ModifyExpressionValue(method = "spawnItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getRandom()Lnet/minecraft/util/RandomSource;"))
    private static RandomSource ghostpath$dispenseRandom(RandomSource original, @Local(argsOnly = true) Level level) {
        return CauseTracker.randomFor(level, original);
    }
}
