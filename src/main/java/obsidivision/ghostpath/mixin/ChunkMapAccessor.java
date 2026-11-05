package obsidivision.ghostpath.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.2 ChunkMap.java:129 {@code pendingUnloads} (holders whose chunk is being unloaded; the save is handed to the IOWorker only when the
 * unload task runs, scheduleUnload :508-531) and {@code toDrop} (chunks whose level just went above the loaded range). A chunk in one of
 * them is gone from {@code getChunkNow} but its latest state is not in the region storage yet.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> ghostpath$pendingUnloads();

    @Accessor("toDrop")
    LongSet ghostpath$toDrop();
}
