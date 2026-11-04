package obsidivision.ghostpath.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    /**
     * 26.2 ItemEntity.java:252 {@code private static void merge(ItemEntity toItem, ItemStack toStack, ItemEntity
     * fromItem, ItemStack fromStack)}, the only place one item entity takes another's stack (from {@code tryToMerge},
     * :222-231, via {@code mergeWithNeighbours} :203). A chain item merging into another item tags the receiver (ruling,
     * gap 5), whichever of the two is ticking. HEAD: the undo log snapshots both before the stacks change.
     */
    @Inject(method = "merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private static void ghostpath$beforeMerge(ItemEntity toItem, ItemStack toStack, ItemEntity fromItem, ItemStack fromStack, CallbackInfo ci) {
        UndoLog log = UndoLog.active();
        if (log != null) {
            log.onEntityTouched(toItem);
            log.onEntityTouched(fromItem);
        }
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onItemMerge(toItem, fromItem);
        }
    }
}
