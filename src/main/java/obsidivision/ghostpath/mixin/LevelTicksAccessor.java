package obsidivision.ghostpath.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.2 LevelTicks.java:34 {@code allContainers}: chunk key -> that chunk's tick container. */
@Mixin(LevelTicks.class)
public interface LevelTicksAccessor<T> {
    @Accessor("allContainers")
    Long2ObjectMap<LevelChunkTicks<T>> ghostpath$allContainers();
}
