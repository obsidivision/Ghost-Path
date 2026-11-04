package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.PrimedTnt;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {
    private static final String CTOR = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/LivingEntity;)V";

    /**
     * 26.2 PrimedTnt.java:65-69, constructor {@code PrimedTnt(Level, double, double, double, LivingEntity)}: the kick,
     * {@code level.getRandom().nextDouble()} at :68. Chain TNT draws it from the seeded chain random (DESIGN R1).
     */
    @ModifyExpressionValue(method = CTOR, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getRandom()Lnet/minecraft/util/RandomSource;"))
    private RandomSource ghostpath$kickRandom(RandomSource original) {
        return CauseTracker.randomFor(((PrimedTnt) (Object) this).level(), original);
    }

    /**
     * Same constructor, :69 {@code setDeltaMovement(-sin(rot) * 0.02, 0.2F, -cos(rot) * 0.02)}. With {@code /path kick off}
     * the angle is still drawn (the stream stays in step) but the horizontal kick is 0; the fixed upward 0.2 stays.
     */
    @WrapOperation(method = CTOR, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/PrimedTnt;setDeltaMovement(DDD)V"))
    private void ghostpath$kick(PrimedTnt self, double x, double y, double z, Operation<Void> original) {
        if (CauseTracker.kickApplied(self.level())) {
            original.call(self, x, y, z);
        } else {
            original.call(self, 0.0, y, 0.0);
        }
    }
}
