package obsidivision.ghostpath.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 26.2 AbstractArrow.java:354 {@code protected boolean isInGround()} (entity data {@code IN_GROUND}); set by {@code onHitBlock}
 * (:542), cleared by {@code startFalling} (:~350, when the block it stuck in is gone) and read by the tick (:195-221).
 */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {
    @Invoker("isInGround")
    boolean ghostpath$isInGround();
}
