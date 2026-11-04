package obsidivision.ghostpath.mixin;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.2 LegacyRandomSource.java:14-15 {@code seed} and {@code gaussianSource}: the whole state of the random that
 * {@code RandomSource.create()} returns, so a copy can continue the same sequence.
 */
@Mixin(LegacyRandomSource.class)
public interface GhostRandomAccessor {
    @Accessor("seed")
    AtomicLong ghostpath$seed();

    @Accessor("gaussianSource")
    MarsagliaPolarGaussian ghostpath$gaussian();
}
