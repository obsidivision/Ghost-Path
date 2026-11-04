package obsidivision.ghostpath.mixin;

import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Undo log (R4): first-touch snapshot of a container block entity <b>before</b> {@code removeItem} changes it, also when
 * the caller holds a reference obtained before the recording (no lookup to hook). Targets: every 26.2 block entity
 * class that declares {@code removeItem} (BaseContainerBlockEntity.java:105-123, RandomizableContainerBlockEntity.java:61-73
 * (loot unpack happens before super), and the overrides listed). setChanged() cannot do this: vanilla calls it after the
 * change (BaseContainerBlockEntity.java:108,123).
 */
@Mixin({
    net.minecraft.world.level.block.entity.BaseContainerBlockEntity.class,
    net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.class,
    net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity.class,
    net.minecraft.world.level.block.entity.HopperBlockEntity.class,
})
public abstract class ContainerRemoveItemMixin {
    @Inject(method = "removeItem", at = @At("HEAD"))
    private void ghostpath$beforeChange(int slot, int count, CallbackInfoReturnable<ItemStack> ci) {
        UndoLog log = UndoLog.active();
        if (log != null) {
            log.onBlockEntityAccess((BlockEntity) (Object) this);
        }
        // live rerun (step 4, ruling 2): removing from a non-empty slot is a content change
        if (count > 0 && obsidivision.ghostpath.watch.LiveWatch.active() && !((net.minecraft.world.Container) this).getItem(slot).isEmpty()) {
            obsidivision.ghostpath.watch.LiveWatch.onContentsChanged((BlockEntity) (Object) this);
        }
    }
}
