package obsidivision.ghostpath.record;

import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No autosave while a recording (and later a ghost run) changes the world (ruling), so a half-run machine is never
 * written by the periodic save. Only non-forced {@code MinecraftServer.saveEverything} calls are held
 * ({@code MinecraftServerSaveMixin}): the autosave and the singleplayer pause save. A held save is done once, right
 * after the restore. {@code ServerLevel.noSave} is left alone: setting it would also stop chunk unloading
 * (ChunkMap.java:448) and change how the real world behaves during the recording.
 *
 * <p>Not held: {@code /save-all} (forced), chunks saved because they unload (normal behaviour; the restore loads them
 * again, see UndoLog), worldgen's eager chunk saves (ChunkMap.java:490-505, only chunks marked by world generation,
 * :211-212), the final save on stop (the recorder restores first, SERVER_STOPPING).
 */
public final class SaveGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("ghostpath");
    private static @Nullable SaveGuard active;
    private static int heldSavesDone;

    /** How many held saves were done after a restore, since start-up (for tests). */
    public static int heldSavesDone() {
        return heldSavesDone;
    }

    public static @Nullable SaveGuard active() {
        return active;
    }

    public static SaveGuard begin(MinecraftServer server) {
        if (active != null) {
            throw new IllegalStateException("saves are already held");
        }
        active = new SaveGuard(server);
        return active;
    }

    private final MinecraftServer server;
    private int held;
    private boolean ended;

    private SaveGuard(MinecraftServer server) {
        this.server = server;
    }

    /** A non-forced save was asked for: hold it. Returns true (the caller skips the save). */
    public boolean holdSave() {
        if (this.held++ == 0) {
            LOGGER.info("[ghostpath] autosave held until the recording is restored");
        }
        return true;
    }

    /** Non-forced saves skipped so far. */
    public int heldSaves() {
        return this.held;
    }

    /** Stops holding; if a save was held, does it now (unless {@code saveNow} is false, e.g. the server is stopping). */
    public void end(boolean saveNow) {
        if (this.ended) {
            return;
        }
        this.ended = true;
        if (active == this) {
            active = null;
        }
        if (this.held > 0 && saveNow) {
            LOGGER.info("[ghostpath] doing the autosave held during the recording");
            heldSavesDone++;
            this.server.saveEverything(true, false, false);
        }
    }
}
