package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.WatchedRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class EntityRandomMixin {
    /**
     * 26.2 Entity.java:254 {@code protected final RandomSource random = RandomSource.create();} (the only
     * {@code RandomSource.create()} in Entity's constructor, Entity.java:312). The entity's own random also makes its
     * UUID (:287) and, for items, the spawn velocity, yaw and bob offset (ItemEntity.java:54,58,65). An entity built by
     * chain work gets a random seeded from one chain-stream draw, so all of that repeats exactly (DESIGN R1); any other
     * entity's random is watched, and its draws during chain work are reported as unseeded.
     */
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private RandomSource ghostpath$entityRandom(RandomSource original, @Local(argsOnly = true) Level level) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null && tracker.inChain(level)) {
            return new WatchedRandom(RandomSource.create(tracker.nextEntitySeed()), level, "Entity.random", true);
        }
        return new WatchedRandom(original, level, "Entity.random", false, (Entity) (Object) this);
    }

    /**
     * 26.2 Entity.java:315 {@code this.id = level.getNextEntityId()}. The id decides behaviour: a resting item moves only
     * when {@code (tickCount + id) % 4 == 0} (ItemEntity.java:141), mobs use it for their tick phase (Mob.java:718), boats
     * for the seat side (AbstractBoat.java:620). The counter differs between two runs of the same machine, so two seeded
     * runs diverged once their items came to rest (found by MachineCommandTest). An entity built by chain work therefore
     * takes the next ids until {@code id % 4} equals its construction index in the chain, mod 4: the same residue in every
     * run (the skipped ids are simply unused). Entities that existed before the chain keep their ids (the ghost copy must
     * do the same, DESIGN step 2).
     */
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getNextEntityId()I"))
    private int ghostpath$entityId(int original, @Local(argsOnly = true) Level level) {
        CauseTracker tracker = CauseTracker.active();
        return tracker != null && tracker.inChain(level) ? tracker.chainEntityId(level, original) : original;
    }
}
