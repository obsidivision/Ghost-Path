package obsidivision.ghostpath.mixin;

import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.2 PistonMovingBlockEntity.java:46-48 {@code progressO, lastTicked, deathTicks}: not in its NBT. */
@Mixin(PistonMovingBlockEntity.class)
public interface GhostPistonAccessor {
    @Accessor("progressO")
    float ghostpath$progressO();

    @Accessor("progressO")
    void ghostpath$setProgressO(float value);

    @Accessor("lastTicked")
    long ghostpath$lastTicked();

    @Accessor("lastTicked")
    void ghostpath$setLastTicked(long value);

    @Accessor("deathTicks")
    int ghostpath$deathTicks();

    @Accessor("deathTicks")
    void ghostpath$setDeathTicks(int value);
}
