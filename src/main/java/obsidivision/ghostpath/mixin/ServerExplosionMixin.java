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

    /** 26.2 ServerExplosion.java:234 {@code public int explode()}: a chain explosion gets its index (frozen entities note which pushed them). */
    @org.spongepowered.asm.mixin.injection.Inject(method = "explode", at = @At("HEAD"))
    private void ghostpath$explosionStart(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> ci) {
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onExplosionStart(this.level);
        }
    }

    /**
     * 26.2 ServerExplosion.java:199, in hurtEntities: {@code entity.push(knockback)}. Found through accessible sections (:181,
     * EntitySectionStorage.java:56), so also entities in lazy chunks; after the push the tracker notes it on a frozen chain entity.
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "hurtEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;push(Lnet/minecraft/world/phys/Vec3;)V"))
    private void ghostpath$explosionPush(net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.Vec3 knockback,
                                         com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        original.call(entity, knockback);
        CauseTracker tracker = CauseTracker.active();
        if (tracker != null) {
            tracker.onExplosionPush(this.level, entity);
        }
    }
}
