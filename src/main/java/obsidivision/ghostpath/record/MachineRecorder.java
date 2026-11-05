package obsidivision.ghostpath.record;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import obsidivision.ghostpath.GhostPath;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /path machine} (build step 1d): presses a trigger ({@link Triggers}: lever, button, note block, trapdoor, door, fence gate, repeater, comparator) as chain work and records, with the undo log and cause
 * tracking on, until the chain has no pending work ({@link CauseTracker#pending()}) or the tick limit is reached; then
 * restores the world and stores the {@link Recording}.
 *
 * <p>Driven from the server tick: {@link #tick} runs at the end of every server tick (after the levels ticked, so the
 * scheduled ticks and block events of this tick are done). One recording at a time. The real recording never
 * force-loads a chunk or changes a ticket (R9): the position must already be loaded.
 *
 * <p>Autosave is held for the whole recording and its restore ({@link SaveGuard}); a held save is done right after the
 * restore. A stop request restores first ({@link #abort}). Not covered: {@code /save-all} (forced save) during a
 * recording, and a crash.
 *
 * <p>Chunks unload normally during a recording (ruling). If one unloaded with changes in it, the recorder does not
 * restore at once: it loads those chunks with {@link GhostPath#RESTORE_TICKET} (radius 0: FULL, loaded, not ticking)
 * and waits until their entities are loaded (at most {@link #RESTORE_WAIT_LIMIT_NANOS}, real time), then restores and removes the
 * tickets. A stop request during the wait restores what is loaded (blocks in unloaded chunks are loaded synchronously by
 * setBlock; entities saved in them are counted as not restored).
 */
public final class MachineRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("ghostpath");
    /** The chain seed of every recording; the same machine therefore gives the same run (R1). */
    public static final long SEED = 0x6A05_7FA7_0000_0001L;
    /**
     * Longest real time to wait for unloaded chunks (and their entities) to load for the restore; then restore what is
     * there. Real time, not ticks: loading is asynchronous disk work, and a server that ticks fast (game tests run
     * without the 50 ms sleep) passes thousands of ticks before a chunk is read (seen: 1200 ticks in under a second).
     */
    public static final long RESTORE_WAIT_LIMIT_NANOS = 60_000_000_000L;

    /** Reported to whoever started the recording, on the server thread. */
    public interface Listener {
        void done(Recording recording);

        void failed(String message);
    }

    /** A problem with the request itself, with a message for the player. Nothing was changed. */
    public static final class RejectedException extends Exception {
        public RejectedException(String message) {
            super(message);
        }
    }

    private static @Nullable MachineRecorder current;

    public static @Nullable MachineRecorder current() {
        return current;
    }

    /**
     * Starts a recording and presses the trigger. Throws {@link RejectedException} (world untouched) if the position is
     * not a lever or an unpressed button, is not loaded, or a recording is running already.
     */
    public static MachineRecorder start(ServerLevel level, BlockPos pos, int limit, boolean kick, Listener listener) throws RejectedException {
        if (current != null || UndoLog.active() != null || CauseTracker.active() != null) {
            throw new RejectedException("a recording is already running");
        }
        if (limit < 1) {
            throw new RejectedException("the tick limit must be at least 1");
        }
        if (!level.isLoaded(pos)) {
            throw new RejectedException(pos.toShortString() + " is not loaded (a recording never loads chunks)");
        }
        BlockState state = level.getBlockState(pos);
        String reason = Triggers.reject(pos, state);
        if (reason != null) {
            throw new RejectedException(reason);
        }
        BlockPos trigger = pos.immutable();
        long startTime = level.getGameTime();
        Weather weather = Weather.of(level);
        net.minecraft.world.clock.PackedClockStates clocks = level.clockManager().packState();
        LoadingLevels loading = LoadingLevels.capture(level, pos.getX() >> 4, pos.getZ() >> 4);
        obsidivision.ghostpath.watch.LiveWatch.preempt(); // a live rerun's ghost holds the autosave guard (one ghost at a time)
        SaveGuard saves = SaveGuard.begin(level.getServer());
        UndoLog undo;
        CauseTracker tracker;
        try {
            undo = UndoLog.begin(level);
            try {
                tracker = CauseTracker.begin(level, SEED, kick);
            } catch (RuntimeException e) {
                undo.abandon();
                throw e;
            }
        } catch (RuntimeException e) {
            saves.end(false);
            throw e;
        }
        MachineRecorder recorder = new MachineRecorder(level, trigger, limit, kick, listener, undo, tracker, startTime, saves, weather, clocks, state, loading);
        current = recorder;
        try {
            tracker.enableEntityTrace();
            tracker.runAsChain(() -> press(level, trigger, state));
        } catch (RuntimeException e) {
            recorder.cancel();
            throw e;
        }
        return recorder;
    }

    /** What the player's click would do, without a player: see {@link Triggers}. */
    public static void press(ServerLevel level, BlockPos pos, BlockState state) {
        Triggers.press(level, pos, state);
    }

    /** Call at the end of every server tick. */
    public static void tick(MinecraftServer server) {
        MachineRecorder recorder = current;
        if (recorder != null) {
            recorder.step();
        }
    }

    /** The server is stopping: put the world back before it is saved. */
    public static void abort(MinecraftServer server) {
        MachineRecorder recorder = current;
        if (recorder != null) {
            LOGGER.warn("[ghostpath] server stopping during a recording: restoring the world and dropping the recording");
            recorder.cancel();
            recorder.listener.failed("the server stopped during the recording");
        }
    }

    private final ServerLevel level;
    private final BlockPos trigger;
    private final int limit;
    private final boolean kick;
    private final Listener listener;
    private final UndoLog undo;
    private final CauseTracker tracker;
    private final long startTime;
    private final SaveGuard saves;
    private final Weather weatherAtStart;
    /** The trigger's state before the press (a rerun sets it back before pressing, ruling 1 after step 4). */
    private final BlockState triggerState;
    private final net.minecraft.world.clock.PackedClockStates clocksAtStart;
    private final LoadingLevels loading;
    private int serverTicks;
    // set when the recording part is over and the restore waits for chunks
    private boolean restoring;
    private int restoreWait;
    private long restoreStartNanos;
    /** What the restore puts back: only what the chain did (ruling); null until the recording part is over. */
    private UndoLog.@org.jspecify.annotations.Nullable Scope scope;
    private LongSet restoreChunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private int endTicks;
    private boolean endLimitHit;
    private CauseTracker.@org.jspecify.annotations.Nullable Pending endPending;
    private @org.jspecify.annotations.Nullable Weather weatherAtEnd;
    private java.util.List<CauseTracker.LiveEntity> liveProjectiles = java.util.List.of();

    private MachineRecorder(ServerLevel level, BlockPos trigger, int limit, boolean kick, Listener listener, UndoLog undo,
                            CauseTracker tracker, long startTime, SaveGuard saves, Weather weatherAtStart,
                            net.minecraft.world.clock.PackedClockStates clocksAtStart, BlockState triggerState, LoadingLevels loading) {
        this.loading = loading;
        this.triggerState = triggerState;
        this.saves = saves;
        this.clocksAtStart = clocksAtStart;
        this.weatherAtStart = weatherAtStart;
        this.level = level;
        this.trigger = trigger;
        this.limit = limit;
        this.kick = kick;
        this.listener = listener;
        this.undo = undo;
        this.tracker = tracker;
        this.startTime = startTime;
    }

    public BlockPos trigger() {
        return this.trigger;
    }

    private void step() {
        if (this.restoring) {
            this.restoreWait++;
            boolean ready = true;
            for (long key : this.restoreChunks) {
                ready &= this.undo.loadedWithEntities(key);
            }
            boolean late = System.nanoTime() - this.restoreStartNanos > RESTORE_WAIT_LIMIT_NANOS;
            if (ready || late) {
                if (!ready) {
                    LOGGER.warn("[ghostpath] chunks for the restore not loaded after {} ticks (60 s); restoring what is loaded", this.restoreWait);
                } else {
                    LOGGER.info("[ghostpath] chunks for the restore loaded after {} ticks", this.restoreWait);
                }
                this.finish();
            }
            return;
        }
        this.serverTicks++;
        CauseTracker.Pending pending = this.tracker.pending();
        boolean limitHit = pending.any() && this.serverTicks >= this.limit;
        if (pending.any() && !limitHit) {
            return;
        }
        // game time, not server ticks, is what the events are counted in; a frozen game (/tick freeze) still ends at the limit
        this.endTicks = (int) (this.level.getGameTime() - this.startTime);
        this.endLimitHit = limitHit;
        this.endPending = pending;
        this.weatherAtEnd = Weather.of(this.level);
        this.liveProjectiles = this.tracker.captureLiveProjectiles(); // before the restore removes them
        this.tracker.end();
        this.scope = this.tracker.restoreScope();
        this.restoreChunks = this.undo.chunksToLoad(this.scope);
        if (this.restoreChunks.isEmpty()) {
            this.finish();
            return;
        }
        LOGGER.info("[ghostpath] {} chunks unloaded during the recording; loading them for the restore: {}", this.restoreChunks.size(),
            this.restoreChunks.longStream().mapToObj(ChunkPos::unpack).toList());
        for (long key : this.restoreChunks) {
            this.level.getChunkSource().addTicketWithRadius(GhostPath.RESTORE_TICKET, ChunkPos.unpack(key), 0);
        }
        this.restoring = true;
        this.restoreStartNanos = System.nanoTime();
    }

    private void removeRestoreTickets() {
        for (long key : this.restoreChunks) {
            this.level.getChunkSource().removeTicketWithRadius(GhostPath.RESTORE_TICKET, ChunkPos.unpack(key), 0);
        }
    }

    private void finish() {
        Recording recording;
        try {
            this.undo.restore(this.scope);
            recording = Recording.of(this.tracker, this.undo, this.trigger, this.endTicks, this.limit, this.endLimitHit, this.endPending, this.kick,
                SEED, this.startTime, this.weatherAtStart, this.weatherAtEnd, this.restoreChunks.size(), this.clocksAtStart, this.triggerState, this.liveProjectiles, this.loading);
        } catch (RuntimeException e) {
            current = null;
            this.removeRestoreTickets();
            this.saves.end(true);
            LOGGER.error("[ghostpath] restoring the world after the recording failed", e);
            this.listener.failed("restoring the world failed: " + e);
            return;
        }
        current = null;
        this.removeRestoreTickets();
        this.saves.end(true);
        RecordingStore.set(recording);
        // step 5: the flight preview shows the real run (copied now, on the server thread)
        // (the area overlay's chunk layer: the rectangle the ghost check is going to copy, chosen the same way)
        net.minecraft.world.level.ChunkPos[] rect = obsidivision.ghostpath.ghost.GhostCheck.chooseRectangle(this.level, recording.footprint(), recording.heightmapColumns(), recording.entityAreas(), null);
        int[] copied = rect == null ? null : new int[] {rect[0].x(), rect[0].z(), rect[1].x(), rect[1].z()};
        obsidivision.ghostpath.preview.PathStore.publish(serial -> obsidivision.ghostpath.preview.PathBuilder.of(serial, recording, copied)
            .withOrigin(new obsidivision.ghostpath.preview.PathSnapshot.Origin(recording.trigger().getX(), recording.trigger().getY(), recording.trigger().getZ(), recording.startGameTime())));
        this.listener.done(recording);
    }

    /** Ends the recording and puts the world back; no result. */
    private void cancel() {
        this.tracker.end();
        try {
            this.undo.restore(this.tracker.restoreScope());
        } finally {
            current = null;
            this.removeRestoreTickets();
            this.saves.end(false);
        }
    }
}
