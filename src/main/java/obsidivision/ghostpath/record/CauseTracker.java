package obsidivision.ghostpath.record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.ScheduledTick;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger("ghostpath");
    private static @Nullable CauseTracker active;

    public static @Nullable CauseTracker active() {
        return active;
    }

    public static CauseTracker begin(ServerLevel level, long seed) {
        return begin(level, seed, true);
    }

    /** {@code kick}: whether the TNT kick is applied during chain work (off: drawn, discarded, horizontal part 0). */
    public static CauseTracker begin(ServerLevel level, long seed, boolean kick) {
        if (active != null) {
            throw new IllegalStateException("cause tracking is already active");
        }
        active = new CauseTracker(level, seed, kick);
        return active;
    }

    /** False while chain work in {@code level} builds a TNT and the kick is switched off ({@code /path kick off}). */
    public static boolean kickApplied(Level level) {
        CauseTracker t = active;
        return t == null || !t.inChain(level) || t.kick;
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

    private final ServerLevel level;
    private final Thread thread;
    private final long startTime;
    private final RandomSource chainRandom;
    private final boolean kick;
    private int depth;
    private int entitiesConstructed;

    private final List<ChainEvent> events = new ArrayList<>();
    private final Set<BlockPos> writes = new LinkedHashSet<>();
    private final Map<BlockPos, EnumSet<ReadKind>> reads = new HashMap<>();
    private final Set<String> noCollisionSeen = new HashSet<>();
    private final Set<AABB> entityAreas = new LinkedHashSet<>();
    private final Set<Column> heightmapColumns = new LinkedHashSet<>();
    private final Map<String, Integer> unseededSites = new LinkedHashMap<>();
    // identity, not equality: a queued tick or block event that is removed without running must never let a later,
    // equal-looking one count as chain work (ScheduledTick/BlockEventData are records with value equality)
    private final Set<ScheduledTick<?>> chainTicks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BlockEventData> chainBlockEvents = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Entity, Integer> chainEntities = new IdentityHashMap<>();
    private final Set<BlockEntity> chainBlockEntities = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Entity> addedChainEntities = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Item entities of the chain: the position after their last tick and for how many ticks in a row it did not change. */
    private final Map<Entity, Vec3> lastItemPos = new IdentityHashMap<>();
    private final Map<Entity, Integer> itemRestTicks = new IdentityHashMap<>();

    private CauseTracker(ServerLevel level, long seed, boolean kick) {
        this.kick = kick;
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

    /** How chain work read a block position (step 1c). */
    public enum ReadKind {
        /** {@code Level.getBlockState} */
        BLOCK,
        /** {@code Level.getFluidState} */
        FLUID,
        /** {@code Level.getBlockEntity} */
        BLOCK_ENTITY,
        /** entity block collision, read from the chunk by {@code BlockCollisions} (bypasses Level.getBlockState) */
        COLLISION,
        /** light level, through {@code BlockAndLightGetter.getBrightness / getRawBrightness} (also {@code canSeeSky}) */
        LIGHT
    }

    /** A heightmap read: the column (x, z), whatever the height type. */
    public record Column(int x, int z) {}

    /** Block positions read by chain work, with how they were read. */
    public Map<BlockPos, Set<ReadKind>> reads() {
        return Collections.unmodifiableMap(this.reads);
    }

    /** The footprint: every block position chain work read or wrote. */
    public Set<BlockPos> footprint() {
        Set<BlockPos> all = new LinkedHashSet<>(this.writes);
        all.addAll(this.reads.keySet());
        return all;
    }

    /**
     * Boxes that chain work queried for entities ({@code Level.getEntities}, {@code hasEntities}): TNT knockback,
     * hoppers picking up items, pressure plates and detector rails, and the collision queries of every moving chain
     * entity. A real entity entering or leaving such a box can change the run. Each distinct box once.
     */
    public Set<AABB> entityAreas() {
        return Collections.unmodifiableSet(this.entityAreas);
    }

    /** Chain entities of type {@code type} that were really added to the level. */
    public int addedChainEntities(EntityType<?> type) {
        int n = 0;
        for (Entity e : this.addedChainEntities) {
            if (e.getType() == type) {
                n++;
            }
        }
        return n;
    }

    /** What is still going on, for the recorder's "is the chain finished?" check. */
    public record Pending(int ticks, int blockEvents, int entities, int movingPistons) {
        public boolean any() {
            return this.ticks + this.blockEvents + this.entities + this.movingPistons > 0;
        }

        @Override
        public String toString() {
            return this.ticks + " ticks, " + this.blockEvents + " block events, " + this.entities + " entities, " + this.movingPistons + " moving pistons";
        }
    }

    /**
     * Pending chain work: scheduled ticks and block events queued by the chain and not yet run or removed; chain
     * entities still in the level (an item that has lain still on the ground for two ticks is settled: items stay for
     * 6000 ticks and would otherwise keep every recording running); chain moving-piston block entities still moving.
     * Other chain block entities (hoppers on cooldown, ...) are not counted: not covered.
     */
    public Pending pending() {
        int entities = 0;
        for (Entity e : this.chainEntities.keySet()) {
            if (e.isRemoved() || this.level.getEntity(e.getId()) != e) {
                continue; // gone, or tagged in its constructor and never added
            }
            if (e instanceof ItemEntity && e.onGround() && this.itemRestTicks.getOrDefault(e, 0) >= 2) {
                continue;
            }
            entities++;
        }
        int pistons = 0;
        for (BlockEntity be : this.chainBlockEntities) {
            if (be instanceof PistonMovingBlockEntity && !be.isRemoved()) {
                pistons++;
            }
        }
        return new Pending(this.chainTicks.size(), this.chainBlockEvents.size(), entities, pistons);
    }

    /** Columns whose heightmap chain work read (a real block change in the column can change the height). */
    public Set<Column> heightmapColumns() {
        return Collections.unmodifiableSet(this.heightmapColumns);
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

    // ---- hooks: reads (1c) ----

    /** Chain work read {@code pos} in {@code level}. Not an event (an explosion reads thousands); footprint only. */
    public void onRead(Level level, BlockPos pos, ReadKind kind) {
        if (!this.inChain(level)) {
            return;
        }
        EnumSet<ReadKind> kinds = this.reads.get(pos);
        if (kinds == null) {
            kinds = EnumSet.noneOf(ReadKind.class);
            this.reads.put(pos.immutable(), kinds);
        }
        kinds.add(kind);
    }

    /** Chain work read a heightmap of {@code level} at column (x, z). */
    public void onHeightmapRead(Level level, int x, int z) {
        if (this.inChain(level)) {
            this.heightmapColumns.add(new Column(x, z));
        }
    }

    /** Chain work asked {@code level} for entities inside {@code box}. Footprint only, not an event. */
    public void onEntityArea(Level level, AABB box) {
        if (this.inChain(level)) {
            this.entityAreas.add(box);
        }
    }

    /**
     * Entity collision during chain work found chunk (x, z) not at FULL status ({@code Level.getChunkForCollisions}
     * returned null): every block in it counts as no collision, which changes the path (DESIGN finding 3). Logged once
     * per chunk and tick.
     */
    public void onNoCollisionChunk(Level level, int chunkX, int chunkZ) {
        if (!this.inChain(level)) {
            return;
        }
        long tick = this.level.getGameTime() - this.startTime;
        if (this.noCollisionSeen.add(chunkX + "," + chunkZ + "@" + tick)) {
            this.log(ChainEvent.Kind.NO_COLLISION_BELOW_FULL, null, "chunk " + chunkX + "," + chunkZ);
        }
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

    /**
     * Before {@code LevelChunkTicks.schedule}. {@code pending}: the queued tick with the same (type, pos), which makes
     * vanilla drop ours (it joins the chain instead: "merged"); {@code pendingUnreachable}: such a tick exists only as
     * a not-yet-unpacked saved tick, so there is no object to follow.
     */
    public void onTickScheduled(ScheduledTick<?> tick, @Nullable ScheduledTick<?> pending, boolean pendingUnreachable) {
        if (!this.inChainAnyLevel()) {
            return;
        }
        String how = "";
        if (pending != null) {
            this.chainTicks.add(pending);
            how = " merged";
        } else if (pendingUnreachable) {
            how = " merged into a saved tick: not followed";
        } else {
            this.chainTicks.add(tick);
        }
        this.log(ChainEvent.Kind.TICK_SCHEDULED, tick.pos(), tick.type() + " delay=" + (tick.triggerTick() - this.level.getGameTime())
            + " priority=" + tick.priority() + how);
    }

    /** A collected tick is about to run. Returns true if it is chain work. */
    public boolean onTickRun(ScheduledTick<?> tick) {
        if (this.chainTicks.remove(tick)) {
            this.log(ChainEvent.Kind.TICK_RUN, tick.pos(), String.valueOf(tick.type()));
            return true;
        }
        return false;
    }

    /** A queued tick was removed without running ({@code LevelTicks.clearArea}, or its chunk was unloaded). */
    public void onTickRemoved(ScheduledTick<?> tick, String why) {
        if (this.chainTicks.remove(tick)) {
            this.log(ChainEvent.Kind.TICK_CANCELLED, tick.pos(), tick.type() + " " + why);
        }
    }

    // ---- hooks: block events ----

    /** {@code queued} is the object now in the queue: ours, or the equal one already queued ({@code merged}). */
    public void onBlockEventQueued(Level level, BlockEventData queued, boolean merged) {
        if (!this.inChain(level)) {
            return;
        }
        this.chainBlockEvents.add(queued);
        this.log(ChainEvent.Kind.BLOCK_EVENT_QUEUED, queued.pos(), queued.block() + " " + queued.paramA() + "," + queued.paramB()
            + (merged ? " merged" : ""));
    }

    public boolean onBlockEventRun(BlockEventData data) {
        if (this.chainBlockEvents.remove(data)) {
            this.log(ChainEvent.Kind.BLOCK_EVENT_RUN, data.pos(), data.block() + " " + data.paramA() + "," + data.paramB());
            return true;
        }
        return false;
    }

    /** A queued block event was removed without running ({@code ServerLevel.clearBlockEvents}). */
    public void onBlockEventRemoved(BlockEventData data) {
        if (this.chainBlockEvents.remove(data)) {
            this.log(ChainEvent.Kind.BLOCK_EVENT_CANCELLED, data.pos(), data.block() + " " + data.paramA() + "," + data.paramB());
        }
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

    /** After {@code ServerLevel.addEntity} accepted {@code entity} (the call that tags it comes first). */
    public void onEntityAdded(Entity entity) {
        if (this.chainEntities.containsKey(entity)) {
            this.addedChainEntities.add(entity);
        }
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
        if (entity instanceof ItemEntity) {
            Vec3 last = this.lastItemPos.put(entity, p);
            this.itemRestTicks.put(entity, p.equals(last) ? this.itemRestTicks.getOrDefault(entity, 0) + 1 : 0);
        }
        this.log(ChainEvent.Kind.ENTITY_STATE, null, "#" + index + " " + EntityType.getKey(entity.getType())
            + " pos=(" + p.x + ", " + p.y + ", " + p.z + ") v=(" + v.x + ", " + v.y + ", " + v.z + ")"
            + (entity.isRemoved() ? " removed" : ""));
    }

    // ---- hooks: randomness ----

    /** Chain work drew from an unseeded random ({@code Level.random} or an entity's own); called by {@link WatchedRandom}. */
    public void onUnseededDraw(String source, String method) {
        String[] site = callSite();
        String reason = UnseededDrawAllowlist.reason(site[1]);
        String display = source + "." + method + " at " + site[0];
        if (this.unseededSites.merge(display, 1, Integer::sum) == 1 && reason == null) {
            LOGGER.warn("[ghostpath] unseeded draw by chain work, not on the allowlist: {}", display);
        }
        this.log(ChainEvent.Kind.UNSEEDED_DRAW, null, display + (reason == null ? " WARNING: not allowed" : " allowed: " + reason));
    }

    /**
     * The id for an entity built by chain work: the first of the level's next ids whose residue mod 4 is this entity's
     * construction index mod 4 (see EntityRandomMixin). {@code first} is the id the level just handed out.
     */
    public int chainEntityId(Level level, int first) {
        int wanted = this.entitiesConstructed++ & 3;
        int id = first;
        for (int i = 0; i < 8 && (id & 3) != wanted; i++) {
            id = level.getNextEntityId();
        }
        return id;
    }

    /** Seed for the own random of an entity built by chain work: one draw from the chain stream. */
    public long nextEntitySeed() {
        return this.chainRandom.nextLong();
    }

    /**
     * {display, key}. Display: the first caller outside the random classes; if that is a helper in net.minecraft.util
     * (Mth, Util), its caller is added. Key: "File.java:line" of the first frame outside those helpers (allowlist key).
     */
    private static String[] callSite() {
        return StackWalker.getInstance().walk(frames -> {
            List<String> parts = new ArrayList<>();
            String key = null;
            for (StackWalker.StackFrame f : (Iterable<StackWalker.StackFrame>) frames::iterator) {
                String cls = f.getClassName();
                if (cls.startsWith("obsidivision.ghostpath.") || cls.equals("net.minecraft.util.RandomSource")) {
                    continue;
                }
                String name = cls.substring(cls.lastIndexOf('.') + 1) + "." + f.getMethodName() + "(" + f.getFileName() + ":" + f.getLineNumber() + ")";
                boolean helper = cls.equals("net.minecraft.util.Mth") || cls.equals("net.minecraft.util.Util");
                if (parts.isEmpty() || parts.size() == 1 && parts.get(0).startsWith("Mth.") || parts.size() == 1 && parts.get(0).startsWith("Util.")) {
                    parts.add(name);
                }
                if (!helper) {
                    key = f.getFileName() + ":" + f.getLineNumber();
                    break;
                }
            }
            return new String[] {parts.isEmpty() ? "unknown" : String.join(" <- ", parts), key == null ? "unknown" : key};
        });
    }
}
