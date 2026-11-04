package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.item.ItemStack;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gap 6 (ruling). 26.2 DispenseItemBehavior.java:93-114, the anonymous {@code Items.CHEST} behaviour (compiled as
 * {@code DispenseItemBehavior$2}, checked with javap): puts a chest on a tamed donkey, mule or llama.
 */
@Mixin(targets = "net.minecraft.core.dispenser.DispenseItemBehavior$2")
public abstract class ChestOnHorseDispenseMixin {
    /** :104 {@code slot.set(dispensed)} on the horse's chest slot (499). */
    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/SlotAccess;set(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean ghostpath$chest(SlotAccess slot, ItemStack stack, Operation<Boolean> original, @Local AbstractChestedHorse horse) {
        CauseTracker.beforeDispenserChange(horse);
        boolean changed = original.call(slot, stack);
        if (changed) {
            CauseTracker.afterDispenserChange(horse, "chest");
        }
        return changed;
    }
}
