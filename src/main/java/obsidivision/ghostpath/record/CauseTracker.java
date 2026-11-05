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
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import obsidivision.ghostpath.mixin.AbstractArrowAccessor;
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

    /**
     * Runs {@code body} with chain context off, if this thread is doing chain work now; context is put back afterwards.
     * Used for {@code BlockableEventLoop.managedBlock} (the server thread waiting, e.g. for a chunk that a chain read
     * needs: {@code ServerChunkCache.getChunk}, ServerChunkCache.java:166, :219). While it waits, that loop runs whatever
     * main-thread work is queued: chunk promotions with their block entities and world-gen entities, distance-manager
     * updates. None of it is the chain's, and what is queued at that moment depends on how far the asynchronous chunk
     * work has got. Run as chain work, each entity built there took a chain construction index (the id residue of the
     * chain's later entities, {@link #chainEntityId}) and a chain-stream draw (its own random's seed), so everything the
     * chain drew afterwards moved: the order of an explosion's drops changed between two runs of the same machine.
     */
    public static void outsideChain(Runnable body) {
        CauseTracker t = active;
        if (t == null || t.depth == 0 || Thread.currentThread() != t.thread) {
            body.run();
            return;
        }
        int saved = t.depth;
        t.depth = 0;
        if (t.chainWaits++ == 0) {
            LOGGER.info("[ghostpath] chain work waits for the server (a chunk load); main-thread tasks run meanwhile are not chain work");
        }
        try {
            body.run();
        } finally {
            t.depth = saved;
        }
    }

    /** How often chain work waited in {@code managedBlock} (see {@link #outsideChain}). */
    public int chainWaits() {
        return this.chainWaits;
    }

    /** The random a redirected site should use: the chain stream during chain work in the tracked level, else vanilla's. */
    public static RandomSource randomFor(Level level, RandomSource vanilla) {
        CauseTracker t = active;
        return t != null && t.inChain(level) ? t.chainRandom : vanilla;
    }

    /**
     * The random of a loot roll during chain work (ruling after the follow-up, item 5). 26.2 {@code LootContext.Builder.create}
     * (:138-143) takes the builder's own random if it has one (a seeded chest), else the server's per-table
     * {@code RandomSequences} stream ({@code server.getRandomSequence(key)}, saved with the world and <b>shared by the real level and
     * every ghost</b>), else the level's random. During chain work this returns a stream of the chain instead: for a table with a key
     * one of its own, derived from the chain seed and the key (so the rolls of one table repeat in order whatever else the chain
     * draws in between, and the world's sequence is not advanced by a recording or a rerun), for no key the chain stream.
     */
    public static RandomSource lootRandomFor(Level level, net.minecraft.resources.@Nullable Identifier key, RandomSource vanilla) {
        CauseTracker t = active;
        if (t == null || !t.inChain(level)) {
            return vanilla;
        }
        if (key == null) {
            return t.chainRandom;
        }
        return t.lootRandoms.computeIfAbsent(key.toString(), k -> RandomSource.create(t.seed ^ (k.hashCode() * 0x9E3779B97F4A7C15L)));
    }

    private final Map<String, RandomSource> lootRandoms = new java.util.HashMap<>();
    private final long seed;
    private final ServerLevel level;
    private final Thread thread;
    private final long startTime;
    private final RandomSource chainRandom;
    private final boolean kick;
    private int depth;
    private int entitiesConstructed;
    private int chainWaits;
    /** Index for the next tagged entity; not the map size, since entities that leave the dimension leave the map. */
    private int nextEntityIndex;

    private final List<ChainEvent> events = new ArrayList<>();
    /** Where in the level tick we are (step 3): set by hooks in ServerLevel.tick, stored on each event. */
    private String phase = PHASE_TRIGGER;
    /** Step 3: record every chain entity's full state after each of its ticks ({@link #entityTrace()}). */
    private boolean traceEntities;
    private final List<EntityTrace> trace = new ArrayList<>();
    /** Step 5: every chain entity's position and velocity per tick as numbers (and what ended it), for the flight preview. */
    private final List<FlightPoint> flightPoints = new ArrayList<>();
    private final List<FlightNote> flightNotes = new ArrayList<>();
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
    /** Per chain arrow: consecutive ticks it has been stuck in a block, and the recording tick the current stretch began. */
    private final Map<Entity, Integer> arrowStuckTicks = new IdentityHashMap<>();
    private final Map<Entity, Long> arrowStuckSince = new IdentityHashMap<>();
    /** Item ticks whose position changed, but by less than {@link #ITEM_STILL_EPSILON}, and so counted as unchanged. */
    private int itemDriftTicks;
    /** Relative tick of each chain entity's last own tick (frozen entities: see {@link #onLevelTickEnd}). */
    private final Map<Entity, Long> lastTicked = new IdentityHashMap<>();
    /** Chain entities that got a frozen point at the end of the last tick, with its kind. */
    private Map<Entity, String> frozenLastTick = new IdentityHashMap<>();
    /** Chain explosions so far ({@code ServerExplosion.explode} in chain context), and the one running now (0: none). */
    private int explosions;
    private int currentExplosion;
    /** Frozen ticks seen (chain entities of the drawn kinds that did not tick in a tick because their chunk does not tick entities). */
    private int frozenTicks;
    /** Chain entities whose tick is running now, innermost last (passengers tick inside their vehicle's tick). */
    private final Deque<Entity> ticking = new ArrayDeque<>();

    private CauseTracker(ServerLevel level, long seed, boolean kick) {
        this.kick = kick;
        this.level = level;
        this.thread = Thread.currentThread();
        this.startTime = level.getGameTime();
        this.seed = seed;
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

    /**
     * Takes this tracker out of the hooks between slices of a ghost run that spans several server ticks (live rerun,
     * step 4): while the real world ticks, no hook may see it. State is kept; {@link #resume} puts it back.
     */
    public void pause() {
        if (active == this) {
            active = null;
        }
    }

    public void resume() {
        if (active != null && active != this) {
            throw new IllegalStateException("cause tracking is already active");
        }
        active = this;
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
    /**
     * Stuck-arrow rule (ruling after step 5): an arrow ({@code AbstractArrow}) that has been stuck in a block
     * ({@code isInGround()}, AbstractArrow.java:354) for this many consecutive ticks of its own counts as settled, like an
     * item: it no longer holds the recording open (it would otherwise stay until it despawns, 1200 ticks,
     * {@code tickDespawn} :375), and its path ends where it hit, reason "stuck".
     */
    public static final int ARROW_SETTLE_TICKS = 8;
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
            if (frozenKind(this.level, e) != null) {
                continue; // frozen (its chunk does not tick entities): never holds a run open (ruling, session "frozen entities")
            }
            if (e instanceof ItemEntity && e.onGround() && this.itemRestTicks.getOrDefault(e, 0) >= ITEM_SETTLE_TICKS) {
                continue;
            }
            if (e instanceof AbstractArrow && this.arrowStuckTicks.getOrDefault(e, 0) >= ARROW_SETTLE_TICKS) {
                continue; // stuck in a block: settled, like an item
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

    /** End reason / kind of a frozen entity in a chunk that is loaded but does not tick entities. */
    public static final String FROZEN_LAZY = "lazy chunk";
    /** ... in a chunk that is not loaded (FULL) at all. */
    public static final String FROZEN_UNLOADED = "unloaded";

    /**
     * Why {@code e} does not tick where it is, or null if it does (DESIGN "Lazy chunks"): the vanilla entity loop ticks an entity only
     * if its section ticks and its chunk is in entity ticking range, which is exactly {@code ServerLevel.isPositionEntityTicking}
     * (26.2 :1800-1802; the loop :420-447). A passenger is ticked by its vehicle (:836), so it is never frozen by itself here.
     * {@link #FROZEN_UNLOADED} when the chunk is not a loaded full chunk, else {@link #FROZEN_LAZY}.
     */
    public static @Nullable String frozenKind(ServerLevel level, Entity e) {
        if (e.isPassenger() || e instanceof Player) {
            return null;
        }
        BlockPos pos = e.blockPosition();
        if (level.isPositionEntityTicking(pos)) {
            return null;
        }
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null ? FROZEN_UNLOADED : FROZEN_LAZY;
    }

    /** Ticks in which a chain entity was frozen (summed over entities). */
    public int frozenTicks() {
        return this.frozenTicks;
    }

    /** Chain explosions so far. */
    public int explosions() {
        return this.explosions;
    }

    /**
     * {@code ServerExplosion.explode} is starting (26.2 :234): a chain explosion gets the next index (from 1); the pushes it gives
     * frozen chain entities are noted with it ({@link #onExplosionPush}).
     */
    public void onExplosionStart(Level level) {
        this.currentExplosion = this.inChain(level) ? ++this.explosions : 0;
    }

    /**
     * A chain explosion pushed {@code e} ({@code ServerExplosion.hurtEntities}, the {@code entity.push(knockback)} at 26.2 :199; the push
     * itself tagged it, through setDeltaMovement). If the entity is frozen, a flight note {@code "explosion #k"} at this tick: the
     * inspector lists which explosions built up a frozen entity's velocity (lazy acceleration).
     */
    public void onExplosionPush(Level level, Entity e) {
        if (this.currentExplosion == 0 || !this.inChain(level) || !this.traceEntities) {
            return;
        }
        Integer index = this.chainEntities.get(e);
        if (index != null && frozenKind(this.level, e) != null) {
            this.flightNotes.add(new FlightNote(this.level.getGameTime() - this.startTime, index, EXPLOSION_NOTE + this.currentExplosion));
        }
    }

    /** Prefix of the flight note an explosion leaves on a frozen entity it pushed. */
    public static final String EXPLOSION_NOTE = "explosion #";

    /**
     * End of the tracked level's tick ({@code ServerLevel.tick} TAIL, 26.2 :355): every chain entity of a drawn kind that did not tick in
     * this tick and is frozen ({@link #frozenKind}) gets a flight point marked with the kind (position and velocity as they are now: a
     * piston may have moved it, an explosion changed its velocity) and, with the trace on, an entity-trace entry in phase "frozen", so the
     * ghost check compares the build-up too. In order of the chain entity index (the map is by identity).
     */
    public void onLevelTickEnd(Level level) {
        if (level != this.level) {
            return;
        }
        long now = this.level.getGameTime() - this.startTime;
        Map<Entity, String> frozenNow = new IdentityHashMap<>();
        List<Map.Entry<Entity, Integer>> entries = new ArrayList<>(this.chainEntities.entrySet());
        entries.sort(Map.Entry.comparingByValue());
        for (Map.Entry<Entity, Integer> en : entries) {
            Entity e = en.getKey();
            if (e.isRemoved() || this.level.getEntity(e.getId()) != e || FlightKind.of(e) == FlightKind.OTHER) {
                continue;
            }
            Long last = this.lastTicked.get(e);
            if (last != null && last == now) {
                continue;
            }
            String kind = frozenKind(this.level, e);
            if (kind == null) {
                // frozen at the end of the last tick and not ticked in this one: something moved it into a ticking chunk during this
                // tick (a piston: block entities tick after entities), it ticks from the next tick on. This tick still counts as frozen,
                // so the point of this tick is where the push left it (the end of the dashed move)
                kind = this.frozenLastTick.get(e);
                if (kind == null) {
                    continue;
                }
            } else {
                frozenNow.put(e, kind);
            }
            this.frozenTicks++;
            if (this.traceEntities) {
                this.addFlightPoint(e, en.getValue(), kind);
                this.trace.add(new EntityTrace(this.events.size(), now, "frozen", en.getValue(), EntityType.getKey(e.getType()).toString(), exactState(e)));
            }
        }
        this.frozenLastTick = frozenNow;
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
        if (this.traceEntities) {
            this.flightNotes.add(new FlightNote(this.level.getGameTime() - this.startTime, index, "removed " + reason.name()));
        }
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            this.log(ChainEvent.Kind.CHAIN_UNLOADED, entity.blockPosition(), "chain entity unloaded " + what + " pos=" + entity.position());
        } else if (reason == Entity.RemovalReason.CHANGED_DIMENSION) {
            this.log(ChainEvent.Kind.LEFT_DIMENSION, entity.blockPosition(), "left dimension " + what + " pos=" + entity.position());
            this.chainEntities.remove(entity);
            this.lastItemPos.remove(entity);
            this.itemRestTicks.remove(entity);
            this.arrowStuckTicks.remove(entity);
            this.arrowStuckSince.remove(entity);
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

    /** The text of the note when a chain ender pearl belongs to a player (see {@link #notSimulated()}). */
    public static final String PLAYER_PEARL = "player-owned pearl: chunk loading not simulated";

    /** Things in this run the ghost cannot imitate, once each, as text (summary and rerun line). */
    private final Set<String> notSimulated = new java.util.LinkedHashSet<>();

    public List<String> notSimulated() {
        return List.copyOf(this.notSimulated);
    }

    /**
     * 26.2 {@code ThrownEnderpearl.tick} (:155-182): a pearl whose owner is a {@code ServerPlayer} calls
     * {@code registerAndUpdateEnderPearlTicket} (ServerPlayer.java:2237-2246) every 40 ticks and whenever it enters another chunk: a
     * ticket {@code TicketType.ENDER_PEARL} (radius 2, 40 ticks) that keeps the chunks around it loaded and ticking. A pearl with another
     * owner or none places nothing (the machine case, which the ghost does follow). The ghost has no players, so the owner is not found
     * there, and the chunks the ticket would load are not in its copy: not simulated. The owner counts as a player if it is found as one
     * in this level or its UUID belongs to an online player (the ghost's pearl has the saved owner UUID but no owner entity).
     */
    private boolean ownedByAPlayer(net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl) {
        if (pearl.getOwner() instanceof net.minecraft.server.level.ServerPlayer) {
            return true;
        }
        var ref = ((obsidivision.ghostpath.mixin.GhostProjectileAccessor) pearl).ghostpath$owner();
        var server = pearl.level().getServer();
        return ref != null && server != null && server.getPlayerList().getPlayer(ref.getUUID()) != null;
    }

    private void notePearl(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl && this.ownedByAPlayer(pearl)) {
            this.notSimulated.add(PLAYER_PEARL);
        }
    }

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
        this.notePearl(entity);
        // no position: an entity is often tagged inside its constructor, before its position is set
        this.log(ChainEvent.Kind.ENTITY_TAGGED, null, "#" + index + " " + EntityType.getKey(entity.getType()) + " " + why);
    }

    /** After {@code ServerLevel.addEntity} accepted {@code entity} (the call that tags it comes first). */
    public void onEntityAdded(Entity entity) {
        if (this.chainEntities.containsKey(entity)) {
            this.notePearl(entity); // tagged in its constructor, before the owner was set
            this.addedChainEntities.add(entity);
            if (this.traceEntities) {
                this.addFlightPoint(entity, this.chainEntities.get(entity)); // where it starts, before its first tick
            }
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
        this.lastTicked.put(entity, this.level.getGameTime() - this.startTime);
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
        if (entity instanceof AbstractArrow arrow) {
            if (!entity.isRemoved() && ((AbstractArrowAccessor) arrow).ghostpath$isInGround()) {
                long now = this.level.getGameTime() - this.startTime;
                this.arrowStuckSince.putIfAbsent(entity, now);
                int stuck = this.arrowStuckTicks.merge(entity, 1, Integer::sum);
                if (stuck == ARROW_SETTLE_TICKS && this.traceEntities) {
                    // the path ends where the arrow stuck (the tick it began), not 8 ticks later
                    this.flightNotes.add(new FlightNote(this.arrowStuckSince.get(entity), index, "stuck"));
                }
            } else {
                this.arrowStuckTicks.remove(entity);
                this.arrowStuckSince.remove(entity);
            }
        }
        String item = entity instanceof ItemEntity ie ? " item=" + ie.getItem().getCount() + " " + BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()) : "";
        this.log(ChainEvent.Kind.ENTITY_STATE, null, "#" + index + " " + EntityType.getKey(entity.getType()) + item
            + " pos=(" + p.x + ", " + p.y + ", " + p.z + ") v=(" + v.x + ", " + v.y + ", " + v.z + ")"
            + (entity.isRemoved() ? " removed" : ""));
        if (this.traceEntities) {
            this.addFlightPoint(entity, index);
            this.trace.add(new EntityTrace(this.events.size(), this.level.getGameTime() - this.startTime, this.phase, index,
                EntityType.getKey(entity.getType()).toString(), exactState(entity)));
        }
    }

    // ---- step 5: flight points ----

    /** What kind of chain entity a path belongs to (the flight preview draws these; everything else is {@code OTHER}). */
    public enum FlightKind {
        TNT, PROJECTILE, ITEM, FALLING_BLOCK, VEHICLE, OTHER;

        public static FlightKind of(Entity e) {
            if (e instanceof PrimedTnt) {
                return TNT;
            } else if (e instanceof Projectile) {
                return PROJECTILE;
            } else if (e instanceof ItemEntity) {
                return ITEM;
            } else if (e instanceof FallingBlockEntity) {
                return FALLING_BLOCK;
            } else if (e instanceof VehicleEntity) {
                return VEHICLE;
            }
            return OTHER;
        }
    }

    /**
     * One chain entity at one tick: after its own tick (or, for the first point of an entity, where it was added). Exact
     * doubles; {@code box} is its bounding box then (step 6, the inspector), {@code fuse} a primed TNT's remaining fuse (else -1). {@code portal}: it was in a portal ({@code Entity.portalProcess}, :284) after this tick.
     */
    public record FlightPoint(long tick, int index, String type, FlightKind kind, double x, double y, double z, double vx, double vy, double vz,
                              boolean onGround, boolean removed, boolean portal, @Nullable AABB box, int fuse, @Nullable String frozen) {
        /** A point after a tick of the entity's own (not frozen). */
        public FlightPoint(long tick, int index, String type, FlightKind kind, double x, double y, double z, double vx, double vy, double vz,
                           boolean onGround, boolean removed, boolean portal, @Nullable AABB box, int fuse) {
            this(tick, index, type, kind, x, y, z, vx, vy, vz, onGround, removed, portal, box, fuse, null);
        }

        /** Without the bounding box and the fuse (tests; the builder then uses a box of no size at the position and fuse -1). */
        public FlightPoint(long tick, int index, String type, FlightKind kind, double x, double y, double z, double vx, double vy, double vz,
                           boolean onGround, boolean removed, boolean portal) {
            this(tick, index, type, kind, x, y, z, vx, vy, vz, onGround, removed, portal, null, -1, null);
        }
    }

    /** Why a chain entity's path ended or what it hit: "hit block", "hit entity" (projectiles), "removed KILLED", "removed UNLOADED_TO_CHUNK", ... */
    public record FlightNote(long tick, int index, String what) {
    }

    private void addFlightPoint(Entity e, int index) {
        this.addFlightPoint(e, index, null);
    }

    /** {@code frozen}: null after an own tick or where it was added; else the frozen kind (a point at the end of a tick it did not tick in). */
    private void addFlightPoint(Entity e, int index, @Nullable String frozen) {
        Vec3 p = e.position();
        Vec3 v = e.getDeltaMovement();
        this.flightPoints.add(new FlightPoint(this.level.getGameTime() - this.startTime, index, EntityType.getKey(e.getType()).toString(), FlightKind.of(e),
            p.x, p.y, p.z, v.x, v.y, v.z, e.onGround(), e.isRemoved(), e.portalProcess != null, e.getBoundingBox(), e instanceof PrimedTnt tnt ? tnt.getFuse() : -1, frozen));
    }

    /** {@code Projectile.hitTargetOrDeflectSelf}, before it runs: a chain projectile hit a block or an entity. */
    public void onProjectileHit(Projectile projectile, net.minecraft.world.phys.HitResult.Type type) {
        Integer index = this.chainEntities.get(projectile);
        if (this.traceEntities && index != null && projectile.level() == this.level) {
            this.flightNotes.add(new FlightNote(this.level.getGameTime() - this.startTime, index, type == net.minecraft.world.phys.HitResult.Type.BLOCK ? "hit block" : "hit entity"));
        }
    }

    /** A chain projectile still in the level: its saved data and what the save does not hold, for the client to continue its flight. */
    public record LiveEntity(int index, String type, CompoundTag nbt, int tickCount, double xo, double yo, double zo, boolean onGround) {
    }

    /**
     * The chain projectiles that are still in the level now (run stopped at the tick limit with them flying): index, saved
     * data, previous position, tick count. Call before the tracker ends and before the restore or the ghost's discard.
     */
    public List<LiveEntity> captureLiveProjectiles() {
        List<LiveEntity> out = new ArrayList<>();
        for (Map.Entry<Entity, Integer> en : this.chainEntities.entrySet()) {
            Entity e = en.getKey();
            if (e instanceof Projectile && !e.isRemoved() && this.level.getEntity(e.getId()) == e) {
                TagValueOutput tag = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, this.level.registryAccess());
                e.saveWithoutId(tag);
                out.add(new LiveEntity(en.getValue(), EntityType.getKey(e.getType()).toString(), tag.buildResult(), e.tickCount, e.xo, e.yo, e.zo, e.onGround()));
            }
        }
        out.sort(java.util.Comparator.comparingInt(LiveEntity::index));
        return out;
    }

    /** Every chain entity's position per tick (empty unless {@link #enableEntityTrace()} was called). */
    public List<FlightPoint> flightPoints() {
        return Collections.unmodifiableList(this.flightPoints);
    }

    public List<FlightNote> flightNotes() {
        return Collections.unmodifiableList(this.flightNotes);
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
     * (Mth, Util), its caller is added. Key: "full.class.Name.method" of the first frame outside those helpers (allowlist key; the line is only in the display).
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
                    key = cls + "." + f.getMethodName(); // no line number: it differs between builds of the jar (a second checkout, a Minecraft update)
                    break;
                }
            }
            return new String[] {parts.isEmpty() ? "unknown" : String.join(" <- ", parts), key == null ? "unknown" : key};
        });
    }
}
