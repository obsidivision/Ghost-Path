package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.item.ItemStack;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gap 6 (ruling). 26.2 DispenseItemBehavior.java:318-338, the anonymous {@code Items.BRUSH} behaviour (compiled as
 * {@code DispenseItemBehavior$11}, checked with javap): brushes a scute off an armadillo.
 */
@Mixin(targets = "net.minecraft.core.dispenser.DispenseItemBehavior$11")
public abstract class BrushDispenseMixin {
    /** :330 {@code armadillo.brushOffScute(null, dispensed)}. */
    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/armadillo/Armadillo;brushOffScute(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean ghostpath$brush(Armadillo armadillo, Entity user, ItemStack tool, Operation<Boolean> original) {
        CauseTracker.beforeDispenserChange(armadillo);
        boolean changed = original.call(armadillo, user, tool);
        if (changed) {
            CauseTracker.afterDispenserChange(armadillo, "brush");
        }
        return changed;
    }
}
