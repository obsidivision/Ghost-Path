package obsidivision.ghostpath.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.2 LivingEntity.java:250 {@code protected float lastHurt}: not saved, decides how much the next hit deals (:1210-1220). */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("lastHurt")
    float ghostpath$lastHurt();

    @Accessor("lastHurt")
    void ghostpath$setLastHurt(float lastHurt);
}
