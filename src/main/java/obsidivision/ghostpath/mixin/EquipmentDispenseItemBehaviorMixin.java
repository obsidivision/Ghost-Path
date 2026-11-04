package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.dispenser.EquipmentDispenseItemBehavior;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gap 6 (ruling). 26.2 EquipmentDispenseItemBehavior.java:20-36 {@code dispenseEquipment}: armour, saddles, carpets on
 * llamas, horse armour, heads and pumpkins (also through the skull and carved-pumpkin behaviours,
 * DispenseItemBehavior.java:237,258, and DispenserBlock.java:123 for any equippable item).
 */
@Mixin(EquipmentDispenseItemBehavior.class)
public abstract class EquipmentDispenseItemBehaviorMixin {
    /** :30 {@code target.setItemSlot(slot, equip)}. */
    @WrapOperation(method = "dispenseEquipment", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setItemSlot(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void ghostpath$equip(LivingEntity target, EquipmentSlot slot, ItemStack stack, Operation<Void> original) {
        CauseTracker.beforeDispenserChange(target);
        original.call(target, slot, stack);
        CauseTracker.afterDispenserChange(target, "equipment");
    }
}
