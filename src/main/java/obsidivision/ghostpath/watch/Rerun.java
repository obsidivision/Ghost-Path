package obsidivision.ghostpath.watch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.ghost.GhostCopy;
import obsidivision.ghostpath.ghost.GhostLevel;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.record.Triggers;
import org.jspecify.annotations.Nullable;

/**
 * One rerun of a watched machine in a fresh ghost (build step 4), spread over server ticks.
 * <ol>
 * <li><b>Wait</b>: the ghost's tickets are added and the ghost chunks are polled once per server tick until they are at the
 * real chunks' levels ({@link GhostCopy.Prepared#poll}); nothing blocks. At most 20 s of real time.</li>
 * <li><b>Copy</b>: the sections, heightmaps and light are copied over server ticks with the same time budget as the run (at least
 * one chunk per tick); block entities, ticks, block events, entities and weather follow in one go, at one moment of the real world,
 * in the tick the last chunk is done. The cause tracker is started on the ghost and the trigger is pressed there, as the recording and the ghost check do. Same seed, kick
 * setting, clocks, weather and game time as the recording's start.</li>
 * <li><b>Run</b>: the ghost is ticked until its chain has no pending work (and at least as many ticks as the recording
 * had), or until the tick limit. Each server tick runs ghost ticks until the time budget
 * ({@link PathSettings#rerunBudgetMs}) is used up, at least one; then the server goes on. The cause tracker is paused
 * between slices, so no hook sees it while the real world ticks.</li>
 * </ol>
 * The real machine is never touched: the trigger is pressed in the ghost only.
 */
final class Rerun {
    static final long WAIT_LIMIT_NANOS = 20_000_000_000L;

    final Watched watch;
    private final ServerLevel real;
    private final GhostLevel ghost;
    private final GhostCopy.Prepared prepared;
    final ChunkPos min;
    final ChunkPos max;
    final boolean widened;
    private @Nullable CauseTracker tracker;
    private int ticksRun;
    private int serverTicks;
    private final long createdNanos = System.nanoTime();
    private long workNanos;
    private long copyNanos;
    private int copyTicks;
    private boolean copying;
    private long createNanos;
    private @Nullable String triggerNote;
    private List<String> loadingChanged = List.of();
    private java.util.Map<Long, String> notTicking = java.util.Map.of();
    private boolean running;
    private long maxSliceNanos;
    private final int minTicks;
    private final int limit = PathSettings.tickLimit;
    private @Nullable Prediction result;
    private boolean finished;
    private boolean discarded;

    Rerun(Watched watch, ServerLevel real, ChunkPos min, ChunkPos max, boolean widened) throws IOException {
        int[] core = watch.baseRect;
        this.watch = watch;
        this.real = real;
        this.min = min;
        this.max = max;
        this.widened = widened;
        this.minTicks = watch.recording().ticks();
        long t0 = System.nanoTime();
        GhostLevel.resetStrayPackets();
        this.ghost = GhostLevel.create(real);
        this.createNanos = System.nanoTime() - t0;
        try {
            Recording rec = watch.recording();
            // a widened run (the chain reached beyond the first area) takes the chunks that are not loaded from the save
            this.prepared = GhostCopy.prepare(real, this.ghost, min, max,
                new GhostCopy.Start(rec.weatherAtStart(), rec.clocksAtStart(), rec.startGameTime()), widened && obsidivision.ghostpath.PathSettings.beyondLoaded, java.util.Set.of(), core);
        } catch (RuntimeException e) {
            this.ghost.discard();
            throw e;
        }
        this.workNanos += System.nanoTime() - t0;
    }

    /** What the run is doing now, for {@code /path watch list}. */
    String phase() {
        if (this.tracker == null) {
            return "copying (waiting for the ghost chunks)";
        }
        return "running, tick " + this.ticksRun + " of at least " + this.minTicks;
    }

    /** One slice, on the server thread. Returns true when the run is finished ({@link #result()} is set). Throws on failure. */
    boolean step(long budgetNanos) {
        long start = System.nanoTime();
        this.serverTicks++;
        try {
            if (this.tracker == null) {
                if (System.nanoTime() - this.createdNanos > WAIT_LIMIT_NANOS) {
                    throw new IllegalStateException("timed out waiting for the ghost chunks: " + this.prepared.describeNotReady());
                }
                if (!this.copying) {
                    if (!this.prepared.poll()) {
                        return false;
                    }
                    this.copying = true;
                }
                // the sections, heightmaps and light are copied over server ticks with the run's time budget (at least one chunk per
                // tick); the rest of the copy, which is one moment of the real world, follows in the tick the last chunk is done
                long copyStart = System.nanoTime();
                this.copyTicks++;
                boolean sectionsDone = this.prepared.copySome(start + budgetNanos);
                if (!sectionsDone) {
                    this.copyNanos += System.nanoTime() - copyStart;
                    return false;
                }
                this.prepared.finish();
                this.notTicking = java.util.Map.copyOf(this.prepared.realNotTicking());
                Recording rec = this.watch.recording();
                this.triggerNote = this.prepareTrigger(rec);
                // the copy takes the real levels as they are now: say so if they are not the recording's (session "frozen entities")
                this.loadingChanged = List.copyOf(rec.loadingAtStart().changed(this.real, this.min, this.max, 3));
                CauseTracker t = CauseTracker.begin(this.ghost, rec.seed(), rec.kickApplied());
                this.tracker = t;
                try {
                    t.enableEntityTrace();
                    BlockPos trigger = rec.trigger();
                    GhostLevel.runWork(() -> t.runAsChain(() -> MachineRecorder.press(this.ghost, trigger, this.ghost.getBlockState(trigger))));
                } finally {
                    t.pause();
                }
                this.copyNanos += System.nanoTime() - copyStart;
                return false; // the copy used this server tick; the ghost ticks start on the next
            }
            CauseTracker t = this.tracker;
            this.running = true;
            long deadline = start + budgetNanos;
            t.resume();
            try {
                do {
                    this.ghost.tickGhost();
                    this.ticksRun++;
                    boolean idle = !t.pending().any();
                    if (this.ticksRun >= this.limit || idle && this.ticksRun >= this.minTicks) {
                        this.finish(t);
                        return true;
                    }
                } while (System.nanoTime() < deadline);
            } finally {
                t.pause();
            }
            return false;
        } finally {
            long used = System.nanoTime() - start;
            this.workNanos += used;
            if (this.running) {
                this.maxSliceNanos = Math.max(this.maxSliceNanos, used);
            }
        }
    }

    /**
     * Ruling 1 after step 4: the rerun predicts the same press as the recording. The ghost's trigger is set to the state the
     * recording found it in, <b>without updates</b> (neighbours are not told: they stay as copied), and then pressed. When
     * the real trigger is in another state (a lever the player pulled since) the chat line says so. Done before the cause
     * tracker starts, so it is neither chain work nor part of the footprint.
     */
    private @Nullable String prepareTrigger(Recording rec) {
        BlockPos pos = rec.trigger();
        BlockState wanted = rec.triggerStateAtStart();
        BlockState real = this.real.getBlockState(pos);
        BlockState inGhost = this.ghost.getBlockState(pos);
        if (!inGhost.equals(wanted)) {
            this.ghost.setBlock(pos, wanted, NO_UPDATES);
        }
        if (real.equals(wanted)) {
            return null;
        }
        return "real " + describe(real, wanted) + "; prediction is for " + after(wanted);
    }

    /** Flags for putting the trigger back: clients only (the ghost has none), no neighbour update, no shape update, no block-entity or on-place side effects. */
    static final int NO_UPDATES = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
        | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SKIP_ON_PLACE;

    private static String describe(BlockState real, BlockState wanted) {
        if (!real.is(wanted.getBlock())) {
            return "trigger is now " + real.getBlock().getName().getString();
        }
        return wanted.getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT) + " is " + Triggers.stateText(real);
    }

    private static String after(BlockState before) {
        return Triggers.pressText(before);
    }

    private void finish(CauseTracker t) {
        boolean limitHit = this.ticksRun >= this.limit && t.pending().any();
        List<ChunkPos> beyond = new ArrayList<>();
        Footprint fp = Footprint.of(t);
        it.unimi.dsi.fastutil.longs.LongIterator it = fp.chunks().iterator();
        while (it.hasNext()) {
            ChunkPos c = ChunkPos.unpack(it.nextLong());
            if (c.x() < this.min.x() || c.x() > this.max.x() || c.z() < this.min.z() || c.z() > this.max.z()) {
                beyond.add(c);
            }
        }
        List<String> gaps = t.gaps().stream().map(Object::toString).toList();
        this.result = new Prediction(this.ticksRun, limitHit, t.addedChainEntities(EntityTypes.TNT),
            Prediction.tntSpawnTicks(t.events(), t.entityTrace()), List.copyOf(t.events()), List.copyOf(t.entityTrace()), fp, t.pending(),
            this.min, this.max, this.widened, List.copyOf(beyond), gaps, this.serverTicks, this.workNanos / 1_000_000L,
            this.copyNanos / 1_000_000L, this.maxSliceNanos / 1_000_000L, this.createNanos / 1_000_000L, this.triggerNote,
            List.copyOf(t.flightPoints()), List.copyOf(t.flightNotes()), t.captureLiveProjectiles(), this.notTicking, this.copyTicks, t.notSimulated(), this.loadingChanged);
        this.finished = true;
        this.discard();
    }

    @Nullable Prediction result() {
        return this.result;
    }

    boolean finished() {
        return this.finished;
    }

    int ticksRun() {
        return this.ticksRun;
    }

    int serverTicks() {
        return this.serverTicks;
    }

    ServerLevel real() {
        return this.real;
    }

    /** Ends the tracker and throws the ghost away (its temp storage, the autosave hold). Safe to call twice. */
    void discard() {
        if (this.discarded) {
            return;
        }
        this.discarded = true;
        if (this.tracker != null) {
            this.tracker.end();
        }
        this.ghost.discard();
    }

    /** Stops an unfinished run. */
    void cancel() {
        this.discard();
    }
}
