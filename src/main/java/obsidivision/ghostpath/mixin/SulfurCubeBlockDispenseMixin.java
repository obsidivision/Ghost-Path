package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.dispenser.SulfurCubeBlockDispenseItemBehavior;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gap 6 (ruling). 26.2 SulfurCubeBlockDispenseItemBehavior.java:20-29 {@code dispenseBlock} (also called for TNT,
 * DispenseItemBehavior.java:208; and DispenserBlock.java:125).
 */
@Mixin(SulfurCubeBlockDispenseItemBehavior.class)
public abstract class SulfurCubeBlockDispenseMixin {
    /** :22 {@code entity.equipItem(dispensed)}: gives a sulfur cube a block. */
    @WrapOperation(method = "dispenseBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/cubemob/SulfurCube;equipItem(Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean ghostpath$equip(SulfurCube cube, ItemStack stack, Operation<Boolean> original) {
        CauseTracker.beforeDispenserChange(cube);
        boolean changed = original.call(cube, stack);
        if (changed) {
            CauseTracker.afterDispenserChange(cube, "sulfur cube block");
        }
        return changed;
    }
}
