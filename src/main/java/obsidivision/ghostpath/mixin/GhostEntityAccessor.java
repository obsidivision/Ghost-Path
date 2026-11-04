package obsidivision.ghostpath.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Entity state the ghost copy needs that NBT does not hold and that has no public field (26.2 Entity.java:221-305):
 * {@code boardingCooldown} (:221), {@code stuckSpeedMultiplier} (:241), {@code nextStep} (:249), {@code random} (:254),
 * {@code wasTouchingWater}/{@code wasEyeInWater} (:258-259), {@code firstTick} (:261), {@code pistonDeltas} and
 * {@code pistonDeltasGameTime} (:290-291, a piston push in the same game tick is capped by them),
 * {@code onGroundNoBlocks} (:297), {@code lastKnownSpeed}/{@code lastKnownPosition} (:300-301).
 */
@Mixin(Entity.class)
public interface GhostEntityAccessor {
    @Accessor("boardingCooldown")
    int ghostpath$boardingCooldown();

    @Accessor("boardingCooldown")
    void ghostpath$setBoardingCooldown(int value);

    @Accessor("stuckSpeedMultiplier")
    Vec3 ghostpath$stuckSpeedMultiplier();

    @Accessor("stuckSpeedMultiplier")
    void ghostpath$setStuckSpeedMultiplier(Vec3 value);

    @Accessor("nextStep")
    float ghostpath$nextStep();

    @Accessor("nextStep")
    void ghostpath$setNextStep(float value);

    @Accessor("random")
    RandomSource ghostpath$random();

    @Accessor("wasTouchingWater")
    boolean ghostpath$wasTouchingWater();

    @Accessor("wasTouchingWater")
    void ghostpath$setWasTouchingWater(boolean value);

    @Accessor("wasEyeInWater")
    boolean ghostpath$wasEyeInWater();

    @Accessor("wasEyeInWater")
    void ghostpath$setWasEyeInWater(boolean value);

    @Accessor("firstTick")
    boolean ghostpath$firstTick();

    @Accessor("firstTick")
    void ghostpath$setFirstTick(boolean value);

    @Accessor("pistonDeltas")
    double[] ghostpath$pistonDeltas();

    @Accessor("pistonDeltasGameTime")
    long ghostpath$pistonDeltasGameTime();

    @Accessor("pistonDeltasGameTime")
    void ghostpath$setPistonDeltasGameTime(long value);

    @Accessor("onGroundNoBlocks")
    boolean ghostpath$onGroundNoBlocks();

    @Accessor("onGroundNoBlocks")
    void ghostpath$setOnGroundNoBlocks(boolean value);

    @Accessor("lastKnownSpeed")
    Vec3 ghostpath$lastKnownSpeed();

    @Accessor("lastKnownSpeed")
    void ghostpath$setLastKnownSpeed(Vec3 value);

    @Accessor("lastKnownPosition")
    @Nullable Vec3 ghostpath$lastKnownPosition();

    @Accessor("lastKnownPosition")
    void ghostpath$setLastKnownPosition(@Nullable Vec3 value);
}
