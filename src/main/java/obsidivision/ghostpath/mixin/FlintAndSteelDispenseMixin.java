package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.dispenser.FlintAndSteelDispenseItemBehavior;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Gap 6 (ruling). 26.2 FlintAndSteelDispenseItemBehavior.java:51-58 {@code tryIgniteExplosiveEntities}. */
@Mixin(FlintAndSteelDispenseItemBehavior.class)
public abstract class FlintAndSteelDispenseMixin {
    /** :57 {@code entities.getFirst().primeTime(false)}: primes a sulfur cube. */
    @WrapOperation(method = "tryIgniteExplosiveEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/cubemob/SulfurCube;primeTime(Z)Z"))
    private static boolean ghostpath$ignite(SulfurCube cube, boolean imminent, Operation<Boolean> original) {
        CauseTracker.beforeDispenserChange(cube);
        boolean changed = original.call(cube, imminent);
        if (changed) {
            CauseTracker.afterDispenserChange(cube, "flint and steel: prime");
        }
        return changed;
    }
}
