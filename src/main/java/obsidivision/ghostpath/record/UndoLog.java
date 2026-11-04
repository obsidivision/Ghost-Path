package obsidivision.ghostpath.record;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import obsidivision.ghostpath.mixin.LevelTicksAccessor;
import obsidivision.ghostpath.mixin.LivingEntityAccessor;
import obsidivision.ghostpath.mixin.ServerLevelAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Undo log for one recording (build step 1a).
 *
 * <p>The first time something is touched while a log is active, its original state is saved:
 * <ul>
 *   <li>block position: block state and block entity NBT (first write via {@code LevelChunk.setBlockState},
 *       or first block-entity access / tick, whichever comes first);</li>
 *   <li>existing entity: full NBT, on first move ({@code setPosRaw}), first velocity change
 *       ({@code setDeltaMovement}) or removal ({@code setRemoved});</li>
 *   <li>entities added while recording are remembered and removed on restore.</li>
 * </ul>
 * {@link #restore()} puts everything back with neighbour updates, shape updates, onPlace and block-entity
 * side effects (such as a chest dropping its items) suppressed.
 *
 * <p>Also restored (R4): every pending block and fluid tick of the level, as it was at {@link #begin} (each with its
 * remaining delay kept, so the world resumes as if paused), and the queued block events. Entities are also snapshotted
 * when hurt; container block entities before setItem/removeItem. A block entity first seen at {@code setChanged()}
 * is already changed (vanilla calls it afterwards): it is snapshotted anyway and reported in {@link #lateBlockEntities()}.
 * Not restored: game time and the level random (R4). Players are never touched. Remaining gaps: docs/DESIGN.md.
 *
 * <p>Chunks unload normally during a recording (ruling). A chunk that unloaded with changes in it must be loaded again
 * before {@link #restore()}: {@link #chunksToLoad()} lists them; the caller (MachineRecorder) loads them with a ticket and
 * waits until their entities are loaded. Spawned entities saved into such a chunk are then found again by UUID and removed.
 */
public final class UndoLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("ghostpath");
    /** No neighbour update (1 off), shape updates skipped (16), drops suppressed (32), onPlace skipped (512),
     *  block-entity side effects skipped (256); clients are told (2). Block.java:92-101. */
    public static final int RESTORE_FLAGS = Block.UPDATE_CLIENTS
        | Block.UPDATE_KNOWN_SHAPE
        | Block.UPDATE_SUPPRESS_DROPS
        | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS
        | Block.UPDATE_SKIP_ON_PLACE;

    private static @Nullable UndoLog active;

    /** The running log, or null. Hooks call this first so the cost outside a recording is one static read. */
    public static @Nullable UndoLog active() {
        return active;
    }

    public static UndoLog begin(ServerLevel level) {
        if (active != null) {
            throw new IllegalStateException("a recording is already active");
        }
        active = new UndoLog(level);
        active.snapshotQueues();
        return active;
    }

    private record BlockSnapshot(BlockState state, @Nullable CompoundTag blockEntity) {}

    /**
     * Full NBT plus the hurt state that NBT does not hold but that decides the next hit: Entity.invulnerableTime
     * (26.2 Entity.java:260), LivingEntity.lastHurt (LivingEntity.java:250, used at :1210-1220) and, for boats and
     * minecarts, VehicleEntity damage / hurt time / hurt direction (synced data, VehicleEntity.java:94-107; damage over
     * 40 breaks it, :50).
     */
    private record EntitySnapshot(CompoundTag tag, long chunk, int invulnerableTime, float lastHurt, float vehicleDamage, int vehicleHurtTime, int vehicleHurtDir) {
        static EntitySnapshot of(CompoundTag tag, Entity e) {
            float lastHurt = e instanceof LivingEntity living ? ((LivingEntityAccessor) living).ghostpath$lastHurt() : 0.0F;
            long chunk = e.chunkPosition().pack();
            if (e instanceof VehicleEntity v) {
                return new EntitySnapshot(tag, chunk, e.invulnerableTime, lastHurt, v.getDamage(), v.getHurtTime(), v.getHurtDir());
            }
            return new EntitySnapshot(tag, chunk, e.invulnerableTime, lastHurt, 0.0F, 0, 0);
        }

        void applyTransient(Entity e) {
            e.invulnerableTime = this.invulnerableTime;
            if (e instanceof LivingEntity living) {
                ((LivingEntityAccessor) living).ghostpath$setLastHurt(this.lastHurt);
            }
            if (e instanceof VehicleEntity v) {
                v.setDamage(this.vehicleDamage);
                v.setHurtTime(this.vehicleHurtTime);
                v.setHurtDir(this.vehicleHurtDir);
            }
        }
    }

    /** One chunk's tick container at begin: the container object (to detect unload/reload) and its queued ticks. */
    private record TickSnapshot(LevelChunkTicks<?> container, List<ScheduledTick<?>> ticks) {}

    private final ServerLevel level;
    private final Map<BlockPos, BlockSnapshot> blocks = new LinkedHashMap<>();
    private final Map<UUID, EntitySnapshot> entities = new LinkedHashMap<>();
    private final List<Entity> spawned = new ArrayList<>();
    private final Set<UUID> spawnedIds = new HashSet<>();
    private final Map<Long, TickSnapshot> blockTicks = new LinkedHashMap<>();
    private final Map<Long, TickSnapshot> fluidTicks = new LinkedHashMap<>();
    private final List<BlockEventData> blockEvents = new ArrayList<>();
    private final Set<ScheduledTick<?>> createdTicks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BlockPos> late = new LinkedHashSet<>();
    /** Chunks unloaded during the log ({@code ServerLevel.unload}). */
    private final LongSet unloadedChunks = new LongLinkedOpenHashSet();
    /** Entities (spawned or snapshotted) unloaded with their chunk during the log: UUID -> chunk they were saved into. */
    private final Map<UUID, Long> unloadedEntities = new LinkedHashMap<>();
    private int entitiesNotRestored;
    private long startTime;
    private int ticksNotRestored;
    private boolean busy; // true while we read or write the world ourselves, so our own calls are not logged
    private boolean finished;

    private UndoLog(ServerLevel level) {
        this.level = level;
    }

    /** Pending ticks and queued block events at begin. Called outside the level's tick, so the drain sets are empty. */
    private void snapshotQueues() {
        this.startTime = this.level.getGameTime();
        snapshotTicks(this.level.getBlockTicks(), this.blockTicks);
        snapshotTicks(this.level.getFluidTicks(), this.fluidTicks);
        this.blockEvents.addAll(((ServerLevelAccessor) this.level).ghostpath$blockEvents());
    }

    @SuppressWarnings("unchecked")
    private static <T> void snapshotTicks(LevelTicks<T> ticks, Map<Long, TickSnapshot> into) {
        for (Long2ObjectMap.Entry<LevelChunkTicks<T>> e : ((LevelTicksAccessor<T>) ticks).ghostpath$allContainers().long2ObjectEntrySet()) {
            // empty containers too: a tick the recording creates there must be removed on restore
            into.put(e.getLongKey(), new TickSnapshot(e.getValue(), new ArrayList<>(e.getValue().getAll().toList())));
        }
    }

    /** Block entities first seen at setChanged(), i.e. already changed: their snapshot is not the original. */
    public Set<BlockPos> lateBlockEntities() {
        return Collections.unmodifiableSet(this.late);
    }

    /** Ticks from the begin snapshot that could not be put back (their chunk was unloaded or reloaded meanwhile). */
    public int ticksNotRestored() {
        return this.ticksNotRestored;
    }

    /** Spawned or changed entities that could not be removed or restored because their chunk was not loaded at restore. */
    public int entitiesNotRestored() {
        return this.entitiesNotRestored;
    }

    /** Chunks that unloaded while the log was active. */
    public LongSet unloadedChunks() {
        return this.unloadedChunks;
    }

    /**
     * Chunks {@link #restore()} needs that are not loaded with their entities now: chunks holding a saved block position,
     * the original position of a changed entity, a spawned or changed entity that was unloaded with its chunk, or the
     * begin ticks of a chunk that unloaded. Load them (and wait for their entities) before restoring.
     */
    public LongSet chunksToLoad() {
        LongSet need = new LongLinkedOpenHashSet();
        for (BlockPos pos : this.blocks.keySet()) {
            need.add(ChunkPos.containing(pos).pack());
        }
        for (EntitySnapshot snap : this.entities.values()) {
            need.add(snap.chunk());
        }
        for (Long chunk : this.unloadedEntities.values()) {
            need.add(chunk.longValue());
        }
        // a chunk that unloaded matters for ticks only if it had ticks at begin or got a tick from the recording
        LongSet createdIn = new LongLinkedOpenHashSet();
        for (ScheduledTick<?> t : this.createdTicks) {
            createdIn.add(ChunkPos.containing(t.pos()).pack());
        }
        for (long key : this.unloadedChunks) {
            TickSnapshot b = this.blockTicks.get(key);
            TickSnapshot f = this.fluidTicks.get(key);
            if (b != null && !b.ticks().isEmpty() || f != null && !f.ticks().isEmpty() || createdIn.contains(key)) {
                need.add(key);
            }
        }
        need.removeIf((long key) -> this.loadedWithEntities(key));
        return need;
    }

    public boolean loadedWithEntities(long key) {
        ChunkPos pos = ChunkPos.unpack(key);
        return this.level.getChunkSource().getChunkNow(pos.x(), pos.z()) != null && this.level.areEntitiesLoaded(key);
    }

    public int blockCount() {
        return this.blocks.size();
    }

    /** True if the original state at {@code pos} was saved (something wrote to it during the recording). */
    public boolean hasBlock(BlockPos pos) {
        return this.blocks.containsKey(pos);
    }

    public int entitySnapshotCount() {
        return this.entities.size();
    }

    public int spawnedCount() {
        return this.spawned.size();
    }

    // ---- hooks (called from mixins; server thread only) ----

    /** Before a block write at {@code pos} in {@code chunk}. */
    public void onBlockChange(LevelChunk chunk, BlockPos pos) {
        if (this.busy || chunk.getLevel() != this.level) {
            return;
        }
        this.captureBlock(pos);
    }

    /** A block entity was handed out or is about to tick. This is its first access if the position is not logged yet. */
    public void onBlockEntityAccess(BlockEntity blockEntity) {
        if (this.busy || blockEntity.getLevel() != this.level) {
            return;
        }
        this.captureBlock(blockEntity.getBlockPos());
    }

    /** A tick was accepted into a chunk's queue during the recording (any level; only this level's are removed). */
    public void onTickScheduled(ScheduledTick<?> tick) {
        if (!this.busy) {
            this.createdTicks.add(tick);
        }
    }

    /** {@code BlockEntity.setChanged()}: the change is already done. If this is the first touch, the snapshot is late. */
    public void onBlockEntityChanged(BlockEntity blockEntity) {
        if (this.busy || blockEntity.getLevel() != this.level || this.blocks.containsKey(blockEntity.getBlockPos())) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos().immutable();
        this.late.add(pos);
        LOGGER.warn("[ghostpath] undo log: block entity at {} changed before it was snapshotted (seen first at setChanged); it will not be restored exactly", pos);
        this.captureBlock(pos);
    }

    /** Before an existing entity moves, changes velocity, is hurt or is removed. */
    public void onEntityTouched(Entity entity) {
        if (this.busy || entity instanceof Player || entity.level() != this.level) {
            return;
        }
        UUID id = entity.getUUID();
        if (this.entities.containsKey(id) || this.spawnedIds.contains(id)) {
            return;
        }
        // An entity that is not registered in the level yet (still being constructed or about to be added) is not "existing".
        if (this.level.getEntity(id) != entity) {
            return;
        }
        this.busy = true;
        try {
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, this.level.registryAccess());
            if (entity.saveAsPassenger(out)) {
                this.entities.put(id, EntitySnapshot.of(out.buildResult(), entity));
            }
        } finally {
            this.busy = false;
        }
    }

    /** {@code ServerLevel.unload(LevelChunk)}: the chunk is being unloaded (and saved) during the recording. */
    public void onChunkUnload(LevelChunk chunk) {
        if (chunk.getLevel() == this.level && !this.finished) {
            this.unloadedChunks.add(chunk.getPos().pack());
        }
    }

    /** Before {@code Entity.setRemoved(UNLOADED_TO_CHUNK)}: remember where a spawned or changed entity was saved. */
    public void onEntityUnloaded(Entity entity) {
        if (this.finished || entity.level() != this.level) {
            return;
        }
        UUID id = entity.getUUID();
        if (this.spawnedIds.contains(id) || this.entities.containsKey(id)) {
            this.unloadedEntities.put(id, entity.chunkPosition().pack());
        }
    }

    /** After {@code ServerLevel.addEntity} accepted a new entity. */
    public void onEntityAdded(Entity entity) {
        if (this.busy || entity instanceof Player || entity.level() != this.level) {
            return;
        }
        if (this.spawnedIds.add(entity.getUUID())) {
            this.spawned.add(entity);
        }
    }

    // ---- capture / restore ----

    private void captureBlock(BlockPos pos) {
        if (this.blocks.containsKey(pos)) {
            return;
        }
        BlockPos key = pos.immutable();
        this.busy = true;
        try {
            BlockState state = this.level.getBlockState(key);
            BlockEntity be = this.level.getChunkAt(key).getBlockEntity(key, LevelChunk.EntityCreationType.CHECK);
            CompoundTag tag = be == null ? null : be.saveWithFullMetadata(this.level.registryAccess());
            this.blocks.put(key, new BlockSnapshot(state, tag));
        } finally {
            this.busy = false;
        }
    }

    /** Puts the world back and ends the recording. */
    public void restore() {
        if (this.finished) {
            throw new IllegalStateException("already restored");
        }
        this.busy = true;
        try {
            // 1. entities that appeared during the recording; one that was unloaded and loaded again is a new object
            for (Entity e : this.spawned) {
                if (!e.isRemoved()) {
                    e.discard();
                }
                Entity reloaded = this.level.getEntity(e.getUUID());
                if (reloaded != null && reloaded != e && !reloaded.isRemoved()) {
                    reloaded.discard();
                } else if (reloaded == null && this.unloadedEntities.containsKey(e.getUUID())
                    && !this.loadedWithEntities(this.unloadedEntities.get(e.getUUID()))) {
                    this.entitiesNotRestored++;
                    LOGGER.warn("[ghostpath] undo log: spawned entity {} is saved in unloaded chunk {} and was not removed", e.getUUID(),
                        ChunkPos.unpack(this.unloadedEntities.get(e.getUUID())));
                }
            }
            // 2. blocks, then their block entities
            for (Map.Entry<BlockPos, BlockSnapshot> entry : this.blocks.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockSnapshot snap = entry.getValue();
                if (this.level.getBlockState(pos) != snap.state()) {
                    this.level.setBlock(pos, snap.state(), RESTORE_FLAGS);
                }
                this.restoreBlockEntity(pos, snap);
            }
            // 3. entities that existed before
            for (Map.Entry<UUID, EntitySnapshot> entry : this.entities.entrySet()) {
                Long savedIn = this.unloadedEntities.get(entry.getKey());
                if (this.level.getEntity(entry.getKey()) == null && savedIn != null && !this.loadedWithEntities(savedIn)) {
                    // the changed copy is in an unloaded chunk: a recreated one would be a duplicate UUID
                    this.entitiesNotRestored++;
                    LOGGER.warn("[ghostpath] undo log: entity {} is saved in unloaded chunk {} and was not restored", entry.getKey(), ChunkPos.unpack(savedIn));
                    continue;
                }
                this.restoreEntity(entry.getKey(), entry.getValue());
            }
            // 4. pending ticks and queued block events, as at begin
            long now = this.level.getGameTime();
            this.restoreTicks(this.level.getBlockTicks(), this.blockTicks, now);
            this.restoreTicks(this.level.getFluidTicks(), this.fluidTicks, now);
            ObjectLinkedOpenHashSet<BlockEventData> queue = ((ServerLevelAccessor) this.level).ghostpath$blockEvents();
            queue.clear();
            queue.addAll(this.blockEvents);
        } finally {
            this.busy = false;
            this.finished = true;
            if (active == this) {
                active = null;
            }
        }
    }

    /** Ends the recording without restoring (for error paths). */
    public void abandon() {
        this.finished = true;
        if (active == this) {
            active = null;
        }
    }

    /**
     * For every chunk that was loaded at begin and is loaded now (the same container, or a new one if the chunk unloaded
     * and was loaded again: its saved ticks are the half-run ones): drop all its ticks (LevelTicks.clearArea, 26.2
     * LevelTicks.java:236-252, which also fixes the container scheduling) and re-add the begin ticks with the same
     * remaining delay, priority and sub-tick order (so their order among themselves and before any later tick holds).
     * Chunks not loaded at begin keep their ticks except the ones the recording created; ticks of a begin chunk that is
     * not loaded now are counted as not restored.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void restoreTicks(LevelTicks<T> ticks, Map<Long, TickSnapshot> snapshot, long now) {
        Long2ObjectMap<LevelChunkTicks<T>> containers = ((LevelTicksAccessor<T>) ticks).ghostpath$allContainers();
        for (Map.Entry<Long, TickSnapshot> e : snapshot.entrySet()) {
            if (containers.get(e.getKey().longValue()) == null) {
                this.ticksNotRestored += e.getValue().ticks().size();
                continue;
            }
            ticks.clearArea(this.chunkBox(e.getKey()));
        }
        // chunks loaded during the recording: keep their (saved) ticks, drop only the ones the recording created
        List<Long> newKeys = new ArrayList<>();
        for (Long2ObjectMap.Entry<LevelChunkTicks<T>> e : containers.long2ObjectEntrySet()) {
            if (!snapshot.containsKey(e.getLongKey())) {
                newKeys.add(e.getLongKey());
            }
        }
        for (long key : newKeys) {
            List<ScheduledTick<T>> keep = containers.get(key).getAll().filter(t -> !this.createdTicks.contains(t)).toList();
            if (keep.size() != containers.get(key).getAll().count()) {
                ticks.clearArea(this.chunkBox(key));
                keep.forEach(ticks::schedule);
            }
        }
        for (Map.Entry<Long, TickSnapshot> e : snapshot.entrySet()) {
            if (containers.get(e.getKey().longValue()) == null) {
                continue;
            }
            for (ScheduledTick tick : e.getValue().ticks()) {
                ticks.schedule(new ScheduledTick<>((T) tick.type(), tick.pos(), now + (tick.triggerTick() - this.startTime),
                    tick.priority(), tick.subTickOrder()));
            }
        }
        if (this.ticksNotRestored > 0) {
            LOGGER.warn("[ghostpath] undo log: {} pending ticks not restored (their chunk is not loaded)", this.ticksNotRestored);
        }
    }

    private BoundingBox chunkBox(long key) {
        ChunkPos chunk = ChunkPos.unpack(key);
        return new BoundingBox(chunk.getMinBlockX(), this.level.getMinY(), chunk.getMinBlockZ(), chunk.getMaxBlockX(), this.level.getMaxY(), chunk.getMaxBlockZ());
    }

    private void restoreBlockEntity(BlockPos pos, BlockSnapshot snap) {
        BlockEntity current = this.level.getBlockEntity(pos);
        if (snap.blockEntity() == null) {
            return; // block changed back; a block entity that should not exist went away with the block state
        }
        if (current == null) {
            BlockEntity created = BlockEntity.loadStatic(pos, snap.state(), snap.blockEntity(), this.level.registryAccess());
            if (created != null) {
                this.level.setBlockEntity(created);
            }
            return;
        }
        if (snap.blockEntity().equals(current.saveWithFullMetadata(this.level.registryAccess()))) {
            return;
        }
        if (current instanceof Clearable clearable) {
            clearable.clearContent(); // loading only sets the slots that are present in the tag
        }
        ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, this.level.registryAccess(), snap.blockEntity());
        current.loadWithComponents(in);
    }

    private void restoreEntity(UUID id, EntitySnapshot snap) {
        ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, this.level.registryAccess(), snap.tag());
        Entity current = this.level.getEntity(id);
        if (current != null && !current.isRemoved()) {
            current.load(in);
            snap.applyTransient(current);
            return;
        }
        Entity recreated = EntityType.loadEntityRecursive(in, this.level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
        if (recreated != null) {
            snap.applyTransient(recreated);
            this.level.addWithUUID(recreated);
        }
    }
}
