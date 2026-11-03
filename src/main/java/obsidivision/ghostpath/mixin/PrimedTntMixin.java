package obsidivision.ghostpath.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.PrimedTnt;
import obsidivision.ghostpath.record.CauseTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {
    /**
     * 26.2 PrimedTnt.java:65-69, constructor {@code PrimedTnt(Level, double, double, double, LivingEntity)}: the kick,
     * {@code level.getRandom().nextDouble()} at :68. Chain TNT draws it from the seeded chain random (DESIGN R1).
     */
    @ModifyExpressionValue(
        method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/LivingEntity;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getRandom()Lnet/minecraft/util/RandomSource;")
    )
    private RandomSource ghostpath$kickRandom(RandomSource original) {
        return CauseTracker.randomFor(((PrimedTnt) (Object) this).level(), original);
    }
}
