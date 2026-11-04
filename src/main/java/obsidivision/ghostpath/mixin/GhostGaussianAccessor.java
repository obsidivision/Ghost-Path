package obsidivision.ghostpath.mixin;

import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.2 MarsagliaPolarGaussian.java:8-9: the cached second gaussian. */
@Mixin(MarsagliaPolarGaussian.class)
public interface GhostGaussianAccessor {
    @Accessor("nextNextGaussian")
    double ghostpath$next();

    @Accessor("nextNextGaussian")
    void ghostpath$setNext(double value);

    @Accessor("haveNextNextGaussian")
    boolean ghostpath$have();

    @Accessor("haveNextNextGaussian")
    void ghostpath$setHave(boolean value);
}
