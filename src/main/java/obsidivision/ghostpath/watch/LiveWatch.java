package obsidivision.ghostpath.watch;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.ghost.GhostCheck;
import obsidivision.ghostpath.mixin.HopperBlockEntityAccessor;
import obsidivision.ghostpath.mixin.LevelTicksAccessor;
import obsidivision.ghostpath.mixin.ServerLevelAccessor;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.record.UndoLog;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live rerun (build step 4). After a successful {@code /path machine} the machine is <em>watched</em>: its recording
 * (trigger, footprint, seed, kick setting, tick count) is kept. When a real block changes at a footprint position (or
 * inside an entity area, or in a heightmap column it read), the watch waits until there were no footprint changes for
 * {@value #QUIET_TICKS} server ticks and the real machine is idle (no chain work pending at footprint positions), then
 * reruns the machine <b>in a fresh ghost only</b>: the real machine never fires again. The run is spread over server ticks
 * with a time budget ({@link PathSettings#rerunBudgetMs}); a new footprint change cancels a run in progress and starts over
 * after the quiet period. A chain that reaches beyond the copied chunks is rerun once with a larger area. Each rerun makes a
 * {@link Prediction} whose footprint replaces the watched one, and a chat line:
 * {@code rerun: N ticks, K TNT spawned, changed since last: ...}.
 *
 * <p>Server thread only. One ghost at a time; a recording or a ghost check that starts cancels a run in progress
 * ({@link #preempt}; the change stays pending and the rerun starts again later).
 *
 * <p>Not seen: block changes made while a recording runs (the recording's own restore writes the footprint), block entity
 * contents, entity moves (only {@code Level.setBlock}-level state changes, {@code LevelChunk.setBlockState}).
 */
public final class LiveWatch {
    private static final Logger LOGGER = LoggerFactory.getLogger("ghostpath");
    /** Server ticks without a footprint change before a rerun starts. */
    public static final int QUIET_TICKS = 10;
    /**
     * Chunks of margin around the first run's footprint when a chain reached beyond the copied area. The first run's
     * footprint stops where the copied area stops (the rest is void in the ghost), so a margin of one chunk would find the
     * edge again; the largest margin up to this that is loaded is used.
     */
    public static final int WIDEN_MARGIN = 3;
    /** How many times in a row a rerun may widen its area (each adds WIDEN_MARGIN chunks around the footprint it just saw). */
    public static final int MAX_WIDEN_ROUNDS = 4;

    private static final List<Watched> WATCHES = new ArrayList<>();
    private static long serverTick;
    private static @Nullable Rerun run;

    private LiveWatch() {
    }

    // ---- API ----

    /**
     * Starts watching the machine of a finished recording. Replaces a watch of the same trigger. {@code chat} gets the lines
     * ("rerun: ..."), on the server thread.
     */
    public static Watched watch(Recording recording, Consumer<String> chat) {
        for (Watched old : new ArrayList<>(WATCHES)) {
            if (old.recording().dimension().equals(recording.dimension()) && old.trigger().equals(recording.trigger())) {
                stop(old);
            }
        }
        Watched w = new Watched(recording, chat);
        w.lastChange = serverTick;
        WATCHES.add(w);
        return w;
    }

    /** Stops one watch (cancels its run in progress). */
    public static void stop(Watched w) {
        if (run != null && run.watch == w) {
            cancelRun();
        }
        WATCHES.remove(w);
    }

    /** Stops every watch. Returns how many there were. */
    public static int stopAll() {
        int n = WATCHES.size();
        cancelRun();
        WATCHES.clear();
        return n;
    }

    public static List<Watched> watches() {
        return List.copyOf(WATCHES);
    }

    public static boolean active() {
        return !WATCHES.isEmpty();
    }

    /** The run in progress, as text, or null. */
    public static @Nullable String running() {
        return run == null ? null : run.phase();
    }

    /** Lines for {@code /path watch list}. */
    public static List<String> list() {
        List<String> out = new ArrayList<>();
        if (WATCHES.isEmpty()) {
            out.add("No machine is watched. (/path machine <x y z> starts a watch.)");
            return out;
        }
        int i = 1;
        for (Watched w : WATCHES) {
            out.add("#" + i++ + " " + w.trigger().toShortString() + ": " + w.status() + "; reruns " + w.reruns()
                + (w.cancelled() > 0 ? ", cancelled " + w.cancelled() : "") + (w.failures() > 0 ? ", failed " + w.failures() : "")
                + "; footprint " + w.footprint() + "; last area " + w.lastArea() + "; last change: " + w.lastWhat);
            Prediction p = w.latest();
            if (p != null) {
                out.add("   last rerun: " + p.ticks() + " ticks, " + p.tntSpawned() + " TNT" + (p.tntSpawnTicks().isEmpty() ? "" : " (spawn t+"
                    + p.tntSpawnTicks().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", t+")) + ")")
                    + ", " + p.serverTicks() + " server ticks, " + p.millis() + " ms (ghost created " + p.createMillis() + " ms, copy " + p.copyMillis() + " ms, longest slice "
                    + p.maxSliceMillis() + " ms, budget " + PathSettings.rerunBudgetMs + " ms)");
            }
        }
        return out;
    }

    /** Server stopping: the ghost of a run in progress must be closed and its temp storage deleted. */
    public static void shutdown(MinecraftServer server) {
        stopAll();
    }

    // ---- real block changes ----

    /**
     * A real block state changed ({@code LevelChunk.setBlockState} returned a changed state). Cheap when nothing is watched.
     * Ignored while a recording runs: its chain and its restore write the footprint.
     */
    public static void onBlockChanged(ServerLevel level, BlockPos pos) {
        if (ignoring()) {
            return;
        }
        for (Watched w : WATCHES) {
            if (level.dimension().equals(w.recording().dimension()) && w.footprint.hit(pos)) {
                markChanged(w, "block at " + pos.toShortString() + " is now " + level.getBlockState(pos));
            }
        }
    }

    /**
     * The contents of a container block entity changed ({@code setItem}, {@code removeItem}, {@code removeItemNoUpdate}, called
     * before the change; ruling 2 after step 4: TNT in a dispenser, items in a dropper or hopper matter). Counts when the
     * block entity's position is a footprint position. The ghost's block entities carry another dimension key.
     */
    public static void onContentsChanged(net.minecraft.world.level.block.entity.BlockEntity be) {
        if (ignoring() || !(be.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        for (Watched w : WATCHES) {
            if (level.dimension().equals(w.recording().dimension()) && w.footprint.containsPosition(pos)) {
                markChanged(w, "container at " + pos.toShortString());
            }
        }
    }

    /** Nothing is noticed while a recording runs: its chain and its restore write the footprint. */
    private static boolean ignoring() {
        return WATCHES.isEmpty() || MachineRecorder.current() != null || UndoLog.active() != null;
    }

    private static void markChanged(Watched w, String what) {
        w.lastWhat = what;
        w.dirty = true;
        w.lastChange = serverTick;
        w.changes++;
        if (run != null && run.watch == w) {
            cancelRun();
        }
    }

    /**
     * A recording or a ghost check is about to start: a rerun in progress is cancelled (one ghost at a time, and the
     * autosave hold is shared). Its change stays pending.
     */
    public static void preempt() {
        if (run != null) {
            cancelRun();
        }
    }

    private static void cancelRun() {
        Rerun r = run;
        if (r == null) {
            return;
        }
        run = null;
        r.cancel();
        r.watch.cancelled++;
        r.watch.dirty = true;
        r.watch.lastChange = serverTick;
        r.watch.status = "changed, waiting for quiet";
    }

    // ---- the tick ----

    /** End of every server tick. */
    public static void tick(MinecraftServer server) {
        serverTick++;
        if (WATCHES.isEmpty()) {
            return;
        }
        scanEntities(server);
        if (run != null) {
            if (MachineRecorder.current() != null) {
                cancelRun();
                return;
            }
            Rerun r = run;
            try {
                if (r.step(Math.max(1L, PathSettings.rerunBudgetMs) * 1_000_000L)) {
                    run = null;
                    finished(server, r);
                } else {
                    r.watch.status = "rerunning: " + r.phase();
                }
            } catch (RuntimeException e) {
                run = null;
                r.cancel();
                failed(r.watch, e);
            }
            return;
        }
        if (MachineRecorder.current() != null) {
            return;
        }
        for (Watched w : List.copyOf(WATCHES)) {
            if (!w.dirty) {
                continue;
            }
            long quiet = serverTick - w.lastChange;
            if (quiet < QUIET_TICKS) {
                w.status = "changed, waiting for quiet (" + quiet + "/" + QUIET_TICKS + " ticks)";
                continue;
            }
            ServerLevel level = server.getLevel(w.recording().dimension());
            if (level == null) {
                continue;
            }
            if (!idle(level, w.footprint)) {
                w.status = "changed, waiting for the machine to settle";
                continue;
            }
            ChunkPos[] rect = GhostCheck.chooseRectangle(level, w.footprint.positions(), w.footprint.columns(), w.footprint.areas(), w.lastRect);
            if (rect == null) {
                w.status = "changed, waiting for its chunks to be loaded";
                continue;
            }
            start(w, level, rect, false);
            return;
        }
    }

    /** Ruling 3: real entities added to, removed from or settling at a new place in a footprint count as a change. */
    private static void scanEntities(MinecraftServer server) {
        for (Watched w : List.copyOf(WATCHES)) {
            if (MachineRecorder.current() != null) {
                w.entities.invalidate(); // a recording spawns and removes entities in the same places
                continue;
            }
            ServerLevel level = server.getLevel(w.recording().dimension());
            if (level != null && w.entities.scan(level, w.footprint)) {
                w.entityChanges++;
                markChanged(w, "entity change");
            }
        }
    }

    private static void start(Watched w, ServerLevel level, ChunkPos[] rect, boolean widened) {
        w.widenRounds = widened ? w.widenRounds + 1 : 0;
        try {
            run = new Rerun(w, level, rect[0], rect[1], widened);
            w.dirty = false;
            w.status = "rerunning: " + run.phase();
        } catch (IOException | RuntimeException e) {
            run = null;
            failed(w, e);
        }
    }

    private static void failed(Watched w, Exception e) {
        LOGGER.error("[ghostpath] rerun of {} failed", w.trigger().toShortString(), e);
        w.failures++;
        w.dirty = false;
        w.status = "rerun failed: " + e.getMessage() + " (waits for the next change)";
        w.chat.accept("rerun failed: " + e.getMessage());
    }

    private static void finished(MinecraftServer server, Rerun r) {
        Watched w = r.watch;
        Prediction p = r.result();
        if (p == null) {
            return;
        }
        ServerLevel level = r.real();
        if (!p.beyond().isEmpty() && w.widenRounds < MAX_WIDEN_ROUNDS) {
            // the chain reached beyond the copied chunks: copy a larger area (chunks that are not loaded come from the save) and rerun;
            // again if it still reaches beyond, up to MAX_WIDEN_ROUNDS times
            ChunkPos[] rect = GhostCheck.chooseRectangle(level, p.footprint().positions(), p.footprint().columns(), p.footprint().areas(),
                new int[] {r.min.x(), r.min.z(), r.max.x(), r.max.z()}, WIDEN_MARGIN, true);
            if (rect != null && (!rect[0].equals(r.min) || !rect[1].equals(r.max))) {
                LOGGER.info("[ghostpath] rerun of {} reached beyond the copied chunks ({} chunks); rerunning with {}..{}", w.trigger().toShortString(),
                    p.beyond().size(), rect[0], rect[1]);
                w.widenings++;
                start(w, level, rect, true);
                return;
            }
        }
        List<String> changes = Prediction.Summary.of(p).changesSince(w.last);
        StringBuilder line = new StringBuilder("rerun: ").append(p.ticks()).append(" ticks, ").append(p.tntSpawned()).append(" TNT spawned");
        if (p.limitHit()) {
            line.append(" (STOPPED AT THE TICK LIMIT, chain work pending: ").append(p.pendingAtEnd()).append(")");
        }
        if (p.widened()) {
            line.append(" (copied area widened to ").append(p.copiedMin()).append("..").append(p.copiedMax()).append(")");
        }
        if (!p.beyond().isEmpty()) {
            line.append(" (the chain reaches beyond the copied chunks, ").append(p.beyond().size()).append(" chunks not copied: the prediction may be wrong)");
        }
        line.append(" (ghost created in ").append(p.createMillis()).append(" ms)");
        if (p.triggerNote() != null) {
            line.append(" (").append(p.triggerNote()).append(")");
        }
        line.append(", changed since last: ");
        if (changes.isEmpty()) {
            line.append("nothing");
        } else {
            line.append(String.join("; ", changes.subList(0, Math.min(6, changes.size()))));
            if (changes.size() > 6) {
                line.append("; +").append(changes.size() - 6).append(" more");
            }
        }
        w.last = Prediction.Summary.of(p);
        w.latest = p;
        w.footprint = p.footprint();
        w.entities.invalidate(); // the footprint is another one now: entities "entering" it are not changes
        w.lastRect = new int[] {p.copiedMin().x(), p.copiedMin().z(), p.copiedMax().x(), p.copiedMax().z()};
        w.reruns++;
        // step 5: the flight preview shows this prediction (built from copies, the ghost is gone: Rerun.finish discarded it)
        obsidivision.ghostpath.preview.PathStore.publish(serial -> obsidivision.ghostpath.preview.PathBuilder.of(serial, p, w.recording().dimension().identifier().toString()));
        w.status = w.dirty ? "changed again, waiting for quiet" : "idle";
        w.chat.accept(line.toString());
    }

    // ---- is the real machine idle? ----

    /**
     * No chain work pending at footprint positions: no scheduled block or fluid tick, no queued block event, no moving
     * piston or hopper on cooldown at one, and no TNT, falling block, projectile or moving vehicle in a footprint chunk.
     * (Items are not waited for: they settle by themselves and may rest in the area forever.)
     */
    public static boolean idle(ServerLevel level, Footprint fp) {
        if (hasTicksAt(level.getBlockTicks(), fp) || hasTicksAt(level.getFluidTicks(), fp)) {
            return false;
        }
        for (BlockEventData e : ((ServerLevelAccessor) level).ghostpath$blockEvents()) {
            if (fp.containsPosition(e.pos())) {
                return false;
            }
        }
        for (BlockPos pos : fp.positions()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                continue;
            }
            var state = chunk.getBlockState(pos);
            if (state.is(Blocks.MOVING_PISTON)) {
                return false;
            }
            if (state.is(Blocks.HOPPER)) {
                BlockEntity be = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                if (be instanceof HopperBlockEntity && ((HopperBlockEntityAccessor) be).ghostpath$cooldownTime() > 0) {
                    return false;
                }
            }
        }
        it.unimi.dsi.fastutil.longs.LongIterator chunks = fp.chunks().iterator();
        while (chunks.hasNext()) {
            ChunkPos c = ChunkPos.unpack(chunks.nextLong());
            AABB box = new AABB(c.getMinBlockX(), level.getMinY(), c.getMinBlockZ(), c.getMaxBlockX() + 1.0, level.getMaxY() + 1.0, c.getMaxBlockZ() + 1.0);
            if (!level.getEntitiesOfClass(Entity.class, box, LiveWatch::holdsOpen).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean holdsOpen(Entity e) {
        if (e.isRemoved()) {
            return false;
        }
        if (e instanceof PrimedTnt || e instanceof FallingBlockEntity || e instanceof Projectile) {
            return true;
        }
        return e instanceof VehicleEntity && e.getDeltaMovement().lengthSqr() > 1.0E-4;
    }

    private static <T> boolean hasTicksAt(LevelTicks<T> ticks, Footprint fp) {
        for (Long2ObjectMap.Entry<LevelChunkTicks<T>> e : ((LevelTicksAccessor<T>) (Object) ticks).ghostpath$allContainers().long2ObjectEntrySet()) {
            if (fp.chunks().contains(e.getLongKey()) && e.getValue().getAll().anyMatch(t -> fp.containsPosition(t.pos()))) {
                return true;
            }
        }
        return false;
    }
}
