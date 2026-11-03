package obsidivision.ghostpath.record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.ScheduledTick;
import org.jspecify.annotations.Nullable;

/**
 * Cause tracking for one recording (build step 1b).
 *
 * <p>Work belongs to the <b>chain</b> if it runs while chain context is on: the trigger itself, and everything it
 * causes. Context is on during synchronous work below a chain call (neighbour and shape updates finish before the
 * call returns), and is switched on at each deferred entry point when the deferred thing was created or touched by
 * chain work:
 * <ul>
 *   <li>scheduled block/fluid ticks queued by chain work (merged into the chain if an equal pending tick existed);</li>
 *   <li>block events queued by chain work (pistons; same merge rule, the queue is a set);</li>
 *   <li>entities spawned, moved, pushed (velocity change) or hurt by chain work: their own ticks become chain work;</li>
 *   <li>block entities created or changed ({@code setChanged}) by chain work: their own ticks become chain work.</li>
 * </ul>
 * Everything else (players, mobs, random ticks, unrelated redstone) keeps context off and never enters the event log or
 * the footprint.
 *
 * <p>Randomness (DESIGN ruling R1): while context is on, the redirected sites (TNT kick and fuse, the three
 * {@code ServerExplosion} sites) draw from {@link #chainRandom()}, one mod-owned stream reset to the seed at
 * {@link #begin}. Any other chain draw from the level's own random is logged as {@link ChainEvent.Kind#UNSEEDED_DRAW}
 * with its call site.
 *
 * <p>Single server thread only; one tracker at a time.
 */
public final class CauseTracker {
    private static @Nullable CauseTracker active;

    public static @Nullable CauseTracker active() {
        return active;
    }

    public static CauseTracker begin(ServerLevel level, long seed) {
        if (active != null) {
            throw new IllegalStateException("cause tracking is already active");
        }
        active = new CauseTracker(level, seed);
        return active;
    }

    /** Runs {@code body} with chain context on if {@code chain}; nests correctly. */
    public static void within(@Nullable CauseTracker tracker, boolean chain, Runnable body) {
        if (tracker == null || !chain) {
            body.run();
            return;
        }
        tracker.depth++;
        try {
            body.run();
        } finally {
            tracker.depth--;
        }
    }

    /** The random a redirected site should use: the chain stream during chain work in the tracked level, else vanilla's. */
    public static RandomSource randomFor(Level level, RandomSource vanilla) {
        CauseTracker t = active;
        return t != null && t.inChain(level) ? t.chainRandom : vanilla;
    }

    private record TickKey(Object type, BlockPos pos) {}

    private final ServerLevel level;
    private final Thread thread;
    private final long startTime;
    private final RandomSource chainRandom;
    private int depth;

    private final List<ChainEvent> events = new ArrayList<>();
    private final Set<BlockPos> writes = new LinkedHashSet<>();
    private final Map<String, Integer> unseededSites = new LinkedHashMap<>();
    private final Set<ScheduledTick<?>> chainTicks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<TickKey> mergedTicks = new HashSet<>();
    private final Set<BlockEventData> chainBlockEvents = new HashSet<>();
    private final Map<Entity, Integer> chainEntities = new IdentityHashMap<>();
    private final Set<BlockEntity> chainBlockEntities = Collections.newSetFromMap(new IdentityHashMap<>());

    private CauseTracker(ServerLevel level, long seed) {
        this.level = level;
        this.thread = Thread.currentThread();
        this.startTime = level.getGameTime();
        this.chainRandom = RandomSource.create(seed);
    }

    public ServerLevel level() {
        return this.level;
    }

    /** Runs the trigger as chain work. */
    public void runAsChain(Runnable trigger) {
        within(this, true, trigger);
    }

    public void end() {
        if (active == this) {
            active = null;
        }
    }

    // ---- results ----

    public List<ChainEvent> events() {
        return Collections.unmodifiableList(this.events);
    }

    /** Block positions written by chain work (state or block entity changes; destroyed blocks included). */
    public Set<BlockPos> writes() {
        return Collections.unmodifiableSet(this.writes);
    }

    /** Call site -> number of unseeded chain draws there. */
    public Map<String, Integer> unseededDrawSites() {
        return Collections.unmodifiableMap(this.unseededSites);
    }

    public String eventsAsText(int limit) {
        return this.events.stream().limit(limit).map(ChainEvent::toString).collect(Collectors.joining("\n"));
    }

    // ---- context ----

    /** Chain context is on, on this thread, for this level. */
    public boolean inChain(@Nullable Level level) {
        return this.depth > 0 && level == this.level && Thread.currentThread() == this.thread;
    }

    /** Chain context is on (for hooks that do not know their level, such as tick containers). */
    public boolean inChainAnyLevel() {
        return this.depth > 0 && Thread.currentThread() == this.thread;
    }

    public RandomSource chainRandom() {
        return this.chainRandom;
    }

    private void log(ChainEvent.Kind kind, @Nullable BlockPos pos, String detail) {
        this.events.add(new ChainEvent(this.level.getGameTime() - this.startTime, kind, pos == null ? null : pos.immutable(), detail));
    }

    // ---- hooks: blocks ----

    /** Before {@code LevelChunk.setBlockState}. */
    public void onBlockWrite(LevelChunk chunk, BlockPos pos, BlockState newState) {
        if (!this.inChain(chunk.getLevel())) {
            return;
        }
        BlockState old = chunk.getBlockState(pos);
        if (old == newState) {
            return;
        }
        this.writes.add(pos.immutable());
        this.log(ChainEvent.Kind.WRITE, pos, old + " -> " + newState);
    }

    /** A block entity is being put into a chunk of {@code level} (its own level field is not set yet at that point). */
    public void onBlockEntitySet(Level level, BlockEntity be) {
        if (this.inChain(level) && this.chainBlockEntities.add(be)) {
            this.log(ChainEvent.Kind.BLOCK_ENTITY_TAGGED, be.getBlockPos(), "created " + typeName(be));
        }
    }

    /** Before {@code BlockEntity.setChanged()}. */
    public void onBlockEntityChanged(BlockEntity be) {
        if (!this.inChain(be.getLevel())) {
            return;
        }
        if (this.chainBlockEntities.add(be)) {
            this.log(ChainEvent.Kind.BLOCK_ENTITY_TAGGED, be.getBlockPos(), "changed " + typeName(be));
        }
        this.writes.add(be.getBlockPos().immutable());
        this.log(ChainEvent.Kind.WRITE, be.getBlockPos(), "block entity data " + typeName(be));
    }

    private static String typeName(BlockEntity be) {
        return String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
    }

    public boolean isChainBlockEntity(BlockEntity be) {
        return this.chainBlockEntities.contains(be);
    }

    // ---- hooks: scheduled ticks ----

    /** Before {@code LevelChunkTicks.schedule}; {@code alreadyPending}: an equal (type, pos) tick is queued, so ours is dropped. */
    public void onTickScheduled(ScheduledTick<?> tick, boolean alreadyPending) {
        if (!this.inChainAnyLevel()) {
            return;
        }
        if (alreadyPending) {
            this.mergedTicks.add(new TickKey(tick.type(), tick.pos()));
        } else {
            this.chainTicks.add(tick);
        }
        this.log(ChainEvent.Kind.TICK_SCHEDULED, tick.pos(), tick.type() + " delay=" + (tick.triggerTick() - this.level.getGameTime())
            + " priority=" + tick.priority() + (alreadyPending ? " merged" : ""));
    }

    /** A collected tick is about to run. Returns true if it is chain work. */
    public boolean onTickRun(ScheduledTick<?> tick) {
        boolean own = this.chainTicks.remove(tick);
        boolean merged = this.mergedTicks.remove(new TickKey(tick.type(), tick.pos()));
        if (own || merged) {
            this.log(ChainEvent.Kind.TICK_RUN, tick.pos(), String.valueOf(tick.type()));
            return true;
        }
        return false;
    }

    // ---- hooks: block events ----

    public void onBlockEventQueued(Level level, BlockEventData data, boolean alreadyQueued) {
        if (!this.inChain(level)) {
            return;
        }
        this.chainBlockEvents.add(data);
        this.log(ChainEvent.Kind.BLOCK_EVENT_QUEUED, data.pos(), data.block() + " " + data.paramA() + "," + data.paramB()
            + (alreadyQueued ? " merged" : ""));
    }

    public boolean onBlockEventRun(BlockEventData data) {
        if (this.chainBlockEvents.remove(data)) {
            this.log(ChainEvent.Kind.BLOCK_EVENT_RUN, data.pos(), data.block() + " " + data.paramA() + "," + data.paramB());
            return true;
        }
        return false;
    }

    // ---- hooks: entities ----

    /** Chain work spawned, moved, pushed or hurt {@code entity}. */
    public void onEntityTouched(Entity entity, String why) {
        if (entity instanceof Player || !this.inChain(entity.level()) || this.chainEntities.containsKey(entity)) {
            return;
        }
        int index = this.chainEntities.size();
        this.chainEntities.put(entity, index);
        // no position: an entity is often tagged inside its constructor, before its position is set
        this.log(ChainEvent.Kind.ENTITY_TAGGED, null, "#" + index + " " + EntityType.getKey(entity.getType()) + " " + why);
    }

    public boolean isChainEntity(Entity entity) {
        return this.chainEntities.containsKey(entity);
    }

    /** After a chain entity's tick. */
    public void onChainEntityTicked(Entity entity) {
        Integer index = this.chainEntities.get(entity);
        if (index == null) {
            return;
        }
        Vec3 p = entity.position();
        Vec3 v = entity.getDeltaMovement();
        this.log(ChainEvent.Kind.ENTITY_STATE, null, "#" + index + " " + EntityType.getKey(entity.getType())
            + " pos=(" + p.x + ", " + p.y + ", " + p.z + ") v=(" + v.x + ", " + v.y + ", " + v.z + ")"
            + (entity.isRemoved() ? " removed" : ""));
    }

    // ---- hooks: randomness ----

    /** Chain work drew from the level's unseeded random (called by {@link WatchedRandom}). */
    public void onUnseededDraw(String method) {
        String site = callSite();
        this.unseededSites.merge(site, 1, Integer::sum);
        this.log(ChainEvent.Kind.UNSEEDED_DRAW, null, method + " at " + site);
    }

    /** First caller outside the random classes; a frame in net.minecraft.util (Util.shuffle, Mth) also names its caller. */
    private static String callSite() {
        return StackWalker.getInstance().walk(frames -> {
            List<String> parts = new ArrayList<>();
            frames.filter(f -> !f.getClassName().startsWith("obsidivision.ghostpath.")
                    && !f.getClassName().equals("net.minecraft.util.RandomSource"))
                .limit(2)
                .forEach(f -> parts.add(f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName()
                    + "(" + f.getFileName() + ":" + f.getLineNumber() + ")"));
            if (parts.isEmpty()) {
                return "unknown";
            }
            return parts.get(0).startsWith("Util.") || parts.get(0).startsWith("Mth.") ? String.join(" <- ", parts) : parts.get(0);
        });
    }
}
