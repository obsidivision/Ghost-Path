package obsidivision.ghostpath.mixin;

import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Undo log (R4): first-touch snapshot of a container block entity <b>before</b> {@code removeItemNoUpdate} changes it, also when
 * the caller holds a reference obtained before the recording (no lookup to hook). Targets: every 26.2 block entity
 * class that declares {@code removeItemNoUpdate} (BaseContainerBlockEntity.java:105-123, RandomizableContainerBlockEntity.java:61-73
 * (loot unpack happens before super), and the overrides listed). setChanged() cannot do this: vanilla calls it after the
 * change (BaseContainerBlockEntity.java:108,123).
 */
@Mixin({
    net.minecraft.world.level.block.entity.BaseContainerBlockEntity.class,
    net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.class,
})
public abstract class ContainerRemoveItemNoUpdateMixin {
    @Inject(method = "removeItemNoUpdate", at = @At("HEAD"))
    private void ghostpath$beforeChange(int slot, CallbackInfoReturnable<ItemStack> ci) {
        UndoLog log = UndoLog.active();
        if (log != null) {
            log.onBlockEntityAccess((BlockEntity) (Object) this);
        }
    }
}
