package obsidivision.ghostpath.record;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saving off while a recording (and later a ghost run) changes the world (ruling), back on afterwards, also after an
 * early end or a server stop. Uses the same switch as {@code /save-off}: {@code ServerLevel.noSave} (ServerLevel.java:207;
 * {@code MinecraftServer.setAutoSave}, :2070-2078). With it set:
 * <ul>
 *   <li>autosave and pause-menu saves skip every level ({@code saveAllChunks} passes {@code level.noSave && !force},
 *       MinecraftServer.java:603; {@code ServerLevel.save} then writes nothing, :873-891: no level data, chunks or
 *       entities). Only {@code /save-all} ({@code force}) still saves;</li>
 *   <li>chunks are not unloaded either ({@code ChunkMap.tick}: {@code processUnloads} only if {@code !noSave}, ChunkMap.java:448),
 *       so a chunk the machine runs through stays loaded until the recording ends.</li>
 * </ul>
 * Each level's previous value is put back, so a world that was already {@code /save-off} stays off. The stop sequence
 * sets every level's {@code noSave = false} itself before its final save (MinecraftServer.java:661-665).
 */
public final class SaveGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("ghostpath");
    private static @Nullable SaveGuard active;

    public static @Nullable SaveGuard active() {
        return active;
    }

    /** Turns saving off for every level of {@code server}. */
    public static SaveGuard begin(MinecraftServer server) {
        if (active != null) {
            throw new IllegalStateException("saving is already held off");
        }
        SaveGuard guard = new SaveGuard();
        for (ServerLevel level : server.getAllLevels()) {
            guard.before.put(level, level.noSave);
            level.noSave = true;
        }
        active = guard;
        LOGGER.info("[ghostpath] saving off during the recording");
        return guard;
    }

    private final Map<ServerLevel, Boolean> before = new IdentityHashMap<>();
    private boolean ended;

    private SaveGuard() {
    }

    /** Puts every level's save switch back as it was. Safe to call twice. */
    public void end() {
        if (this.ended) {
            return;
        }
        this.ended = true;
        this.before.forEach((level, noSave) -> level.noSave = noSave);
        if (active == this) {
            active = null;
        }
        LOGGER.info("[ghostpath] saving back as before the recording");
    }
}
