package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.WatchedRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Level.class)
public abstract class LevelMixin {
    /**
     * 26.2 Level.java:124 {@code protected final RandomSource random = RandomSource.create();} (the only
     * {@code RandomSource.create()} in Level). Wrapped so chain draws from it are reported as unseeded (DESIGN R1);
     * the wrapper passes every call through unchanged.
     */
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private RandomSource ghostpath$watchRandom(RandomSource original) {
        return new WatchedRandom(original, (Level) (Object) this, "Level.random", false);
    }

    /**
     * 26.2 Level.java:536 {@code public void tickBlockEntities()}, called from ServerLevel.tick (:~447, "blockEntities").
     * Only sets the phase stored on the chain events (step 3: names where a ghost/real difference happened).
     */
    @WrapMethod(method = "tickBlockEntities")
    private void ghostpath$phaseBlockEntities(Operation<Void> original) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.setPhase((net.minecraft.world.level.Level) (Object) this, "block entities");
        }
        original.call();
    }
}
