package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.UndoLog;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    /**
     * 26.2 HopperBlockEntity.java:265 {@code public static boolean addItem(Container, ItemEntity)}: a hopper (block, or
     * hopper minecart through the {@code Hopper} interface) takes items from an item entity, called from
     * {@code suckInItems} (:236) and {@code MinecartHopper} (MinecartHopper.java:110). If the item is a chain item and anything was taken
     * (item discarded or its count changed), the hopper joins the chain (ruling, gap 5). Its own setChanged is the static
     * {@code BlockEntity.setChanged(Level, BlockPos, BlockState)} (:125), which the instance hook does not see.
     */
    @WrapMethod(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z")
    private static boolean ghostpath$pickup(Container container, ItemEntity entity, Operation<Boolean> original) {
        UndoLog log = UndoLog.active();
        if (log != null) {
            log.onEntityTouched(entity); // setItem on the entity goes through no hooked method
        }
        int before = entity.getItem().getCount();
        boolean result = original.call(container, entity);
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null && (entity.isRemoved() || entity.getItem().getCount() != before)) {
            tracker.onHopperPickup(container, entity);
        }
        return result;
    }
}
