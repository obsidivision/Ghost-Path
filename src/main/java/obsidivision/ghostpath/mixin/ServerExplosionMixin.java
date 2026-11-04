package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerExplosion;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The three explosion draws named in DESIGN R1. Each reads the {@code Level.random} field through
 * {@code this.level} (26.2 ServerExplosion.java:45 {@code private final ServerLevel level}); during chain work they get
 * the seeded chain random instead.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    private static final String LEVEL_RANDOM = "Lnet/minecraft/server/level/ServerLevel;random:Lnet/minecraft/util/RandomSource;";

    @Shadow
    @Final
    private ServerLevel level;

    /** 26.2 ServerExplosion.java:135, in calculateExplodedPositions: ray strength {@code 0.7F + random.nextFloat() * 0.6F}. */
    @ModifyExpressionValue(method = "calculateExplodedPositions", at = @At(value = "FIELD", target = LEVEL_RANDOM))
    private RandomSource ghostpath$rayRandom(RandomSource original) {
        return CauseTracker.randomFor(this.level, original);
    }

    /** 26.2 ServerExplosion.java:215, in interactWithBlocks: {@code Util.shuffle(targetBlocks, level.random)}. */
    @ModifyExpressionValue(method = "interactWithBlocks", at = @At(value = "FIELD", target = LEVEL_RANDOM))
    private RandomSource ghostpath$shuffleRandom(RandomSource original) {
        return CauseTracker.randomFor(this.level, original);
    }

    /** 26.2 ServerExplosion.java:228, in createFire: {@code random.nextInt(3) == 0}. */
    @ModifyExpressionValue(method = "createFire", at = @At(value = "FIELD", target = LEVEL_RANDOM))
    private RandomSource ghostpath$fireRandom(RandomSource original) {
        return CauseTracker.randomFor(this.level, original);
    }
}
