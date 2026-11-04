package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.dispenser.ShearsDispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Gap 6 (ruling): dispense behaviours that change an entity tag it. 26.2 ShearsDispenseItemBehavior.java:50-64 {@code tryShearEntity}. */
@Mixin(ShearsDispenseItemBehavior.class)
public abstract class ShearsDispenseItemBehaviorMixin {
    /** :52 {@code entity.shearOffAllLeashConnections(null)} (cuts its leashes; true if it had any). */
    @WrapOperation(method = "tryShearEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;shearOffAllLeashConnections(Lnet/minecraft/world/entity/player/Player;)Z"))
    private static boolean ghostpath$unleash(Entity entity, Player player, Operation<Boolean> original) {
        CauseTracker.beforeDispenserChange(entity);
        boolean changed = original.call(entity, player);
        if (changed) {
            CauseTracker.afterDispenserChange(entity, "shears: leash");
        }
        return changed;
    }

    /** :57 {@code shearable.shear(level, SoundSource.BLOCKS, tool)} (sheep, mooshroom, snow golem, bogged, ...). */
    @WrapOperation(method = "tryShearEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Shearable;shear(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/sounds/SoundSource;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void ghostpath$shear(Shearable shearable, ServerLevel level, SoundSource source, ItemStack tool, Operation<Void> original) {
        if (shearable instanceof Entity entity) {
            CauseTracker.beforeDispenserChange(entity);
        }
        original.call(shearable, level, source, tool);
        if (shearable instanceof Entity entity) {
            CauseTracker.afterDispenserChange(entity, "shears: shear");
        }
    }
}
