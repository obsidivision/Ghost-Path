package obsidivision.ghostpath.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.2 Level.java:120-123 {@code protected float oRainLevel, rainLevel, oThunderLevel, thunderLevel}. Read directly:
 * {@code getThunderLevel(a)} (:920-921) multiplies by the rain level, so it does not give the stored value.
 */
@Mixin(Level.class)
public interface LevelWeatherAccessor {
    @Accessor("oRainLevel")
    float ghostpath$oRainLevel();

    @Accessor("rainLevel")
    float ghostpath$rainLevel();

    @Accessor("oThunderLevel")
    float ghostpath$oThunderLevel();

    @Accessor("thunderLevel")
    float ghostpath$thunderLevel();
}
