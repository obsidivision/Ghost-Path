package obsidivision.ghostpath.mixin;

import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.2 EntityStorage.java:{@code simpleRegionStorage}: the region storage ("entities" folder) behind a level's entity storage, which
 * reads through its own {@code IOWorker} (SimpleRegionStorage.read -> IOWorker.loadAsync, which also sees writes still queued).
 */
@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {
    @Accessor("simpleRegionStorage")
    SimpleRegionStorage ghostpath$simpleRegionStorage();
}
