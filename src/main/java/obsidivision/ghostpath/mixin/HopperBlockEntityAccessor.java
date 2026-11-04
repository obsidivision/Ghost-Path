package obsidivision.ghostpath.mixin;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.2 HopperBlockEntity.java:38 {@code private int cooldownTime}; above 0 means on cooldown (isOnCooldown, :426-428). */
@Mixin(HopperBlockEntity.class)
public interface HopperBlockEntityAccessor {
    @Accessor("cooldownTime")
    int ghostpath$cooldownTime();
}
