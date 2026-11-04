package obsidivision.ghostpath.mixin;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.2 HopperBlockEntity.java:38 {@code private int cooldownTime}; above 0 means on cooldown (isOnCooldown, :426-428). */
@Mixin(HopperBlockEntity.class)
public interface HopperBlockEntityAccessor {
    @Accessor("cooldownTime")
    int ghostpath$cooldownTime();

    /** 26.2 HopperBlockEntity.java:39 {@code tickedGameTime}: not saved; decides whether a pushed-into hopper gets the shorter cooldown (:334). */
    @Accessor("tickedGameTime")
    long ghostpath$tickedGameTime();

    @Accessor("tickedGameTime")
    void ghostpath$setTickedGameTime(long value);
}
