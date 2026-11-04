package obsidivision.ghostpath.record;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import obsidivision.ghostpath.mixin.HopperBlockEntityAccessor;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueOutput;
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
    /** Index for the next tagged entity; not the map size, since entities that leave the dimension leave the map. */
    private int nextEntityIndex;

    private final List<ChainEvent> events = new ArrayList<>();
    /** Where in the level tick we are (step 3): set by hooks in ServerLevel.tick, stored on each event. */
    private String phase = PHASE_TRIGGER;
    /** Step 3: record every chain entity's full state after each of its ticks ({@link #entityTrace()}). */
    private boolean traceEntities;
    private final List<EntityTrace> trace = new ArrayList<>();
    /** Game tick (relative) at which each position was first read by chain work. */
    private final Map<BlockPos, Long> readFirstTick = new HashMap<>();
    /** Gaps the ghost run hit (ruling 3 of step 3); never in the event log, so real and ghost logs stay comparable. */
    private final List<Gap> gaps = new ArrayList<>();
    private final Set<String> gapsSeen = new HashSet<>();
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
    /** Game time each chain block entity was last tagged, changed or had its block state changed by chain work. */
    private final Map<BlockEntity, Long> blockEntityChangedAt = new IdentityHashMap<>();
    private final Set<Entity> addedChainEntities = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Every entity ever tagged (also ones that left the dimension), for the restore scope. */
    private final Set<Entity> everTagged = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Every tick and block event the chain itself queued (not merged ones), run or not, for the restore scope. */
    private final Set<ScheduledTick<?>> chainTicksEver = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BlockEventData> chainEventsEver = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Item entities of the chain: the position after their last tick and for how many ticks in a row it did not change. */
    private final Map<Entity, Vec3> lastItemPos = new IdentityHashMap<>();
    private final Map<Entity, Integer> itemRestTicks = new IdentityHashMap<>();
    /** Item ticks whose position changed, but by less than {@link #ITEM_STILL_EPSILON}, and so counted as unchanged. */
    private int itemDriftTicks;
    /** Chain entities whose tick is running now, innermost last (passengers tick inside their vehicle's tick). */
    private final Deque<Entity> ticking = new ArrayDeque<>();

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

    /**
     * What is still going on, for the recorder's "is the chain finished?" check. {@code blockEntities}: chain block
     * entities that are still busy (see {@link #pending()}), moving pistons included.
     */
    public record Pending(int ticks, int blockEvents, int entities, int blockEntities) {
        public boolean any() {
            return this.ticks + this.blockEvents + this.entities + this.blockEntities > 0;
        }

        @Override
        public String toString() {
            return this.ticks + " ticks, " + this.blockEvents + " block events, " + this.entities + " entities, " + this.blockEntities + " block entities";
        }
    }

    /** A chain block entity counts as busy for this many ticks after chain work last changed it (ruling). */
    public static final int BLOCK_ENTITY_BUSY_TICKS = 8;

    /**
     * Item settle rule (ruling): on the ground and its position unchanged for this many consecutive ticks of its own
     * (two full 4-tick cycles of ItemEntity.java:141). Velocity is ignored: it is never zero for a resting item.
     */
    public static final int ITEM_SETTLE_TICKS = 8;
    /** A position change below this on every axis counts as unchanged: the friction-decayed x/z drift (about 1e-8). */
    public static final double ITEM_STILL_EPSILON = 1.0E-6;

    /** Marks an unseeded draw made inside a tagged mob's tick (UNSEEDED_DRAW detail). */
    public static final String INEXACT_MOB_AI = "inexact: mob AI";
    /** Marks an unseeded draw from a mob's own random outside its tick (UNSEEDED_DRAW detail), e.g. sheared wool. */
    public static final String INEXACT_MOB = "inexact: mob";

    /**
     * Pending chain work: scheduled ticks and block events queued by the chain and not yet run or removed; chain
     * entities still in the level (an item settled on the ground, ITEM_SETTLE_TICKS, does not count: items stay for
     * 6000 ticks and would otherwise keep every recording running); chain block entities still in the level that are
     * busy: a moving piston, a block entity changed in the last {@value #BLOCK_ENTITY_BUSY_TICKS} ticks, or a hopper
     * whose transfer cooldown is above 0 ({@code HopperBlockEntity.cooldownTime}, :38, {@code isOnCooldown} :426-428).
     * An entity or block entity unloaded with its chunk counts as gone (it is logged, see {@link #notFollowed()}).
     */
    public Pending pending() {
        int entities = 0;
        for (Entity e : this.chainEntities.keySet()) {
            if (e.isRemoved() || this.level.getEntity(e.getId()) != e) {
                continue; // gone, or tagged in its constructor and never added
            }
            if (!canHoldOpen(e)) {
                continue; // living mobs, armor stands, ...: tagged, but never pending (ruling)
            }
            if (e instanceof ItemEntity && e.onGround() && this.itemRestTicks.getOrDefault(e, 0) >= ITEM_SETTLE_TICKS) {
                continue;
            }
            entities++;
        }
        int busy = 0;
        long now = this.level.getGameTime();
        for (BlockEntity be : this.chainBlockEntities) {
            if (be.isRemoved()) {
                continue;
            }
            Long changed = this.blockEntityChangedAt.get(be);
            if (be instanceof PistonMovingBlockEntity
                || changed != null && now - changed <= BLOCK_ENTITY_BUSY_TICKS
                || be instanceof HopperBlockEntity && ((HopperBlockEntityAccessor) be).ghostpath$cooldownTime() > 0) {
                busy++;
            }
        }
        return new Pending(this.chainTicks.size(), this.chainBlockEvents.size(), entities, busy);
    }

    /**
     * Chain things the tracker lost or did not follow, in order: entities and block entities unloaded with their chunk,
     * entities that left the dimension, vibrations scheduled on an entity listener. For the summary.
     */
    public List<ChainEvent> notFollowed() {
        return this.events.stream().filter(e -> e.kind() == ChainEvent.Kind.CHAIN_UNLOADED || e.kind() == ChainEvent.Kind.LEFT_DIMENSION
            || e.kind() == ChainEvent.Kind.VIBRATION_UNTRACKED).toList();
    }

    /**
     * The only entity kinds that can hold a recording open (ruling): TNT, projectiles, items (until settled), vehicles
     * (boats, minecarts: {@code VehicleEntity}) and falling blocks. Tagged mobs and everything else stay chain work but
     * never count as pending.
     */
    public static boolean canHoldOpen(Entity e) {
        return e instanceof PrimedTnt || e instanceof Projectile || e instanceof ItemEntity || e instanceof VehicleEntity
            || e instanceof FallingBlockEntity;
    }

    /** Item ticks counted as "unchanged" although the position moved by less than {@link #ITEM_STILL_EPSILON}. */
    public int itemDriftTicks() {
        return this.itemDriftTicks;
    }

    /**
     * What the undo log should put back after this recording (ruling): positions chain work wrote (block states, and
     * block entities through setChanged), positions of block entities that joined the chain, every entity that joined
     * the chain (spawned ones are removed, touched ones restored), and the ticks and block events the chain queued.
     */
    public UndoLog.Scope restoreScope() {
        Set<BlockPos> positions = new LinkedHashSet<>(this.writes);
        for (BlockEntity be : this.chainBlockEntities) {
            positions.add(be.getBlockPos().immutable());
        }
        Set<java.util.UUID> entities = new HashSet<>();
        for (Entity e : this.everTagged) {
            entities.add(e.getUUID());
        }
        Set<ScheduledTick<?>> ticks = Collections.newSetFromMap(new IdentityHashMap<>());
        ticks.addAll(this.chainTicksEver);
        Set<BlockEventData> events = Collections.newSetFromMap(new IdentityHashMap<>());
        events.addAll(this.chainEventsEver);
        return new UndoLog.Scope(positions, entities, ticks, events);
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
        this.events.add(new ChainEvent(this.level.getGameTime() - this.startTime, kind, pos == null ? null : pos.immutable(), detail, this.phase));
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
            BlockPos key = pos.immutable();
            this.reads.put(key, kinds);
            this.readFirstTick.put(key, this.level.getGameTime() - this.startTime);
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
        if (this.inChain(level)) {
            this.tagBlockEntity(be, "created");
        }
    }

    /**
     * Chain work set a new block state at a position that keeps its block entity ({@code LevelChunk.setBlockState},
     * the {@code blockEntity.setBlockState(state)} call at LevelChunk.java:344): the block entity joins the chain, so
     * its own ticks (a hopper whose ENABLED changed) are chain work.
     */
    public void onBlockEntityStateChanged(Level level, BlockEntity be) {
        if (this.inChain(level)) {
            this.tagBlockEntity(be, "block state changed");
        }
    }

    /**
     * Chain work emitted a game event that schedules a vibration on {@code system}'s listener
     * ({@code VibrationSystem.Listener.scheduleVibration}). A block-entity listener (sculk sensor, calibrated sensor,
     * shrieker) joins the chain; an entity listener (warden, allay) is logged and not followed (ruling).
     */
    public void onVibrationScheduled(Level level, Object system) {
        if (!this.inChain(level)) {
            return;
        }
        if (system instanceof BlockEntity be) {
            this.tagBlockEntity(be, "vibration");
        } else if (system instanceof Entity e) {
            this.log(ChainEvent.Kind.VIBRATION_UNTRACKED, e.blockPosition(), "vibration to untracked entity " + EntityType.getKey(e.getType()));
        }
    }

    /** A hopper (block or minecart) took items from a chain item entity ({@code HopperBlockEntity.addItem(Container, ItemEntity)}). */
    public void onHopperPickup(Object container, ItemEntity item) {
        if (!this.chainEntities.containsKey(item)) {
            return;
        }
        if (container instanceof BlockEntity be && be.getLevel() == this.level) {
            this.tagBlockEntity(be, "picked up chain item");
        } else if (container instanceof Entity e) {
            this.tag(e, "picked up chain item");
        }
    }

    /** {@code from} is merging into {@code to} ({@code ItemEntity.merge}, ItemEntity.java:252): a chain item tags the receiver. */
    public void onItemMerge(ItemEntity to, ItemEntity from) {
        if (this.chainEntities.containsKey(from)) {
            this.tag(to, "merged chain item");
        }
    }

    /**
     * Called by the dispense-behaviour hooks just before the behaviour may change {@code target}: the undo log takes its
     * first-touch snapshot (these changes go through no hooked method).
     */
    public static void beforeDispenserChange(Entity target) {
        UndoLog log = UndoLog.active();
        if (log != null) {
            log.onEntityTouched(target);
        }
    }

    /**
     * Called by the dispense-behaviour hooks after the behaviour changed {@code target} (for calls that report success,
     * only if they did): the target joins the chain if the dispenser runs as chain work. {@code what} names the behaviour.
     */
    public static void afterDispenserChange(Entity target, String what) {
        CauseTracker t = active;
        if (t != null) {
            t.onDispenserChangesEntity(target, what);
        }
    }

    /** A dispense behaviour run by chain work changed {@code target} (shears, equipment, ...). */
    public void onDispenserChangesEntity(Entity target, String what) {
        if (this.inChain(target.level())) {
            this.tag(target, "dispenser " + what);
        }
    }

    private void tagBlockEntity(BlockEntity be, String why) {
        this.blockEntityChangedAt.put(be, this.level.getGameTime());
        if (this.chainBlockEntities.add(be)) {
            this.log(ChainEvent.Kind.BLOCK_ENTITY_TAGGED, be.getBlockPos(), why + " " + typeName(be));
        }
    }

    /** {@code ServerLevel.unload(LevelChunk)}: chain block entities in it are lost (logged; they then count as gone). */
    public void onChunkUnload(LevelChunk chunk) {
        if (chunk.getLevel() != this.level) {
            return;
        }
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (this.chainBlockEntities.contains(be)) {
                this.log(ChainEvent.Kind.CHAIN_UNLOADED, be.getBlockPos(), "chain block entity unloaded " + typeName(be));
            }
        }
    }

    /**
     * Before {@code Entity.setRemoved}. A chain entity unloaded with its chunk is logged (it then counts as gone); one
     * that changes dimension is logged and no longer followed (portals are out of scope).
     */
    public void onEntityRemoved(Entity entity, Entity.RemovalReason reason) {
        Integer index = this.chainEntities.get(entity);
        if (index == null || entity.level() != this.level) {
            return;
        }
        String what = "#" + index + " " + EntityType.getKey(entity.getType());
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            this.log(ChainEvent.Kind.CHAIN_UNLOADED, entity.blockPosition(), "chain entity unloaded " + what + " pos=" + entity.position());
        } else if (reason == Entity.RemovalReason.CHANGED_DIMENSION) {
            this.log(ChainEvent.Kind.LEFT_DIMENSION, entity.blockPosition(), "left dimension " + what + " pos=" + entity.position());
            this.chainEntities.remove(entity);
            this.lastItemPos.remove(entity);
            this.itemRestTicks.remove(entity);
        }
    }

    /** Before {@code BlockEntity.setChanged()}. */
    public void onBlockEntityChanged(BlockEntity be) {
        if (!this.inChain(be.getLevel())) {
            return;
        }
        this.tagBlockEntity(be, "changed");
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
            this.chainTicksEver.add(tick);
        }
        this.log(ChainEvent.Kind.TICK_SCHEDULED, tick.pos(), tick.type() + " delay=" + (tick.triggerTick() - this.level.getGameTime())
            + " priority=" + tick.priority() + how);
    }

    /** A collected tick is about to run. Returns true if it is chain work. */
    public boolean onTickRun(ScheduledTick<?> tick) {
        if (this.chainTicks.remove(tick)) {
            this.phase = tick.type() instanceof net.minecraft.world.level.material.Fluid ? "fluid ticks" : "block ticks";
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
        if (!merged) {
            this.chainEventsEver.add(queued);
        }
        this.log(ChainEvent.Kind.BLOCK_EVENT_QUEUED, queued.pos(), queued.block() + " " + queued.paramA() + "," + queued.paramB()
            + (merged ? " merged" : ""));
    }

    public boolean onBlockEventRun(BlockEventData data) {
        if (this.chainBlockEvents.remove(data)) {
            this.phase = "block events";
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
        if (this.inChain(entity.level())) {
            this.tag(entity, why);
        }
    }

    /** Tags {@code entity} whatever the context (players never; only entities of the tracked level). */
    private void tag(Entity entity, String why) {
        if (entity instanceof Player || entity.level() != this.level || this.chainEntities.containsKey(entity)) {
            return;
        }
        int index = this.nextEntityIndex++;
        this.chainEntities.put(entity, index);
        this.everTagged.add(entity);
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

    /** Before a chain entity's tick (ServerLevelMixin); paired with {@link #onChainEntityTicked}. */
    public void onChainEntityTickStart(Entity entity) {
        this.ticking.addLast(entity);
    }

    /** After a chain entity's tick. */
    public void onChainEntityTicked(Entity entity) {
        if (this.ticking.peekLast() == entity) {
            this.ticking.removeLast();
        }
        Integer index = this.chainEntities.get(entity);
        if (index == null) {
            return;
        }
        Vec3 p = entity.position();
        Vec3 v = entity.getDeltaMovement();
        if (entity instanceof ItemEntity) {
            Vec3 last = this.lastItemPos.put(entity, p);
            boolean still = last != null && Math.abs(p.x - last.x) < ITEM_STILL_EPSILON && Math.abs(p.y - last.y) < ITEM_STILL_EPSILON
                && Math.abs(p.z - last.z) < ITEM_STILL_EPSILON;
            if (still && !p.equals(last)) {
                this.itemDriftTicks++;
            }
            this.itemRestTicks.put(entity, still && entity.onGround() ? this.itemRestTicks.getOrDefault(entity, 0) + 1 : 0);
        }
        String item = entity instanceof ItemEntity ie ? " item=" + ie.getItem().getCount() + " " + BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()) : "";
        this.log(ChainEvent.Kind.ENTITY_STATE, null, "#" + index + " " + EntityType.getKey(entity.getType()) + item
            + " pos=(" + p.x + ", " + p.y + ", " + p.z + ") v=(" + v.x + ", " + v.y + ", " + v.z + ")"
            + (entity.isRemoved() ? " removed" : ""));
        if (this.traceEntities) {
            this.trace.add(new EntityTrace(this.events.size(), this.level.getGameTime() - this.startTime, this.phase, index,
                EntityType.getKey(entity.getType()).toString(), exactState(entity)));
        }
    }

    // ---- step 3: phase, entity trace, gaps ----

    public static final String PHASE_TRIGGER = "trigger";

    /** Sets the phase stored on the events that follow (ServerLevelMixin, around the parts of {@code ServerLevel.tick}). */
    public void setPhase(Level level, String phase) {
        if (level == this.level) {
            this.phase = phase;
        }
    }

    public String phase() {
        return this.phase;
    }

    /** Turns on the per-tick full entity state record (for {@code /path machine} runs and the ghost run). */
    public void enableEntityTrace() {
        this.traceEntities = true;
    }

    /**
     * One chain entity's state right after its tick, as text: its saved data without the UUID (position, motion,
     * rotation, fuse, fall distance, on-ground, ...) plus the transient state that is not saved (previous positions, tick
     * count, collision flags). {@code seq} is the number of events logged before it, so the trace and the event log
     * have one order.
     */
    public record EntityTrace(int seq, long tick, String phase, int index, String type, String state) {
    }

    /** Every chain entity's state after each of its ticks (empty unless {@link #enableEntityTrace()} was called). */
    public List<EntityTrace> entityTrace() {
        return Collections.unmodifiableList(this.trace);
    }

    /** First relative tick at which chain work read each position. */
    public Map<BlockPos, Long> readFirstTicks() {
        return Collections.unmodifiableMap(this.readFirstTick);
    }

    private static String exactState(Entity e) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.level().registryAccess());
        e.saveWithoutId(out);
        CompoundTag tag = out.buildResult();
        tag.remove("UUID");
        return tag + " | old=(" + e.xo + ", " + e.yo + ", " + e.zo + ") xOld=(" + e.xOld + ", " + e.yOld + ", " + e.zOld
            + ") rotO=(" + e.xRotO + ", " + e.yRotO + ") tickCount=" + e.tickCount + " onGround=" + e.onGround()
            + " hCollision=" + e.horizontalCollision + " vCollision=" + e.verticalCollision + " hurtMarked=" + e.hurtMarked
            + " noPhysics=" + e.noPhysics + " fall=" + e.fallDistance;
    }

    /**
     * A gap the ghost run hit: something the ghost does not copy (world border, POI, players, lookup by dimension key)
     * that chain work in the ghost used. Logged once per (tick, kind); {@code seq} orders it against the event log.
     */
    public record Gap(int seq, long tick, String phase, String kind, String detail) {
        @Override
        public String toString() {
            return "t+" + this.tick + " (" + this.phase + ") gap: " + this.kind + " " + this.detail;
        }
    }

    /** Chain work in a ghost used something the ghost does not copy. Only counts in chain context. */
    public void onGap(Level level, String kind, String detail) {
        if (!this.inChain(level)) {
            return;
        }
        long tick = this.level.getGameTime() - this.startTime;
        if (this.gapsSeen.add(tick + "|" + kind)) {
            this.gaps.add(new Gap(this.events.size(), tick, this.phase, kind, detail));
        }
    }

    public List<Gap> gaps() {
        return Collections.unmodifiableList(this.gaps);
    }

    // ---- hooks: randomness ----

    /** Chain work drew from an unseeded random ({@code Level.random} or an entity's own); called by {@link WatchedRandom}. */
    public void onUnseededDraw(String source, String method, @Nullable Entity owner) {
        String[] site = callSite();
        String reason = UnseededDrawAllowlist.reason(site[1]);
        String display = source + "." + method + " at " + site[0];
        // a draw inside the tick of a tagged mob is its AI (goals, look and stroll targets, ...): inexact, not a warning (ruling);
        // so is any other draw from a mob's own random (sheared wool spread, Sheep.java:176-178: ruled "inexact: mob")
        boolean mobAi = reason == null && this.ticking.peekLast() instanceof Mob;
        boolean mobOwn = reason == null && !mobAi && owner instanceof Mob;
        if (this.unseededSites.merge(display, 1, Integer::sum) == 1 && reason == null && !mobAi && !mobOwn) {
            LOGGER.warn("[ghostpath] unseeded draw by chain work, not on the allowlist: {}", display);
        }
        this.log(ChainEvent.Kind.UNSEEDED_DRAW, null, display + (reason != null ? " allowed: " + reason
            : mobAi ? " " + INEXACT_MOB_AI : mobOwn ? " " + INEXACT_MOB : " WARNING: not allowed"));
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
