package obsidivision.ghostpath.record;

import java.util.ArrayList;
import java.util.HashSet;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import org.jspecify.annotations.Nullable;

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
 * <p>Known gaps, tracked in docs/DESIGN.md: pending scheduled ticks, queued block events, the level random and
 * non-positional entity changes (health, equipment) of an entity that neither moved nor was removed are not restored.
 * Players are never touched.
 */
public final class UndoLog {
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
        return active;
    }

    private record BlockSnapshot(BlockState state, @Nullable CompoundTag blockEntity) {}

    private record EntitySnapshot(CompoundTag tag) {}

    private final ServerLevel level;
    private final Map<BlockPos, BlockSnapshot> blocks = new LinkedHashMap<>();
    private final Map<UUID, EntitySnapshot> entities = new LinkedHashMap<>();
    private final List<Entity> spawned = new ArrayList<>();
    private final Set<UUID> spawnedIds = new HashSet<>();
    private boolean busy; // true while we read or write the world ourselves, so our own calls are not logged
    private boolean finished;

    private UndoLog(ServerLevel level) {
        this.level = level;
    }

    public int blockCount() {
        return this.blocks.size();
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

    /** Before an existing entity moves, changes velocity or is removed. */
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
                this.entities.put(id, new EntitySnapshot(out.buildResult()));
            }
        } finally {
            this.busy = false;
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
            // 1. entities that appeared during the recording
            for (Entity e : this.spawned) {
                if (!e.isRemoved()) {
                    e.discard();
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
                this.restoreEntity(entry.getKey(), entry.getValue());
            }
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
            return;
        }
        Entity recreated = EntityType.loadEntityRecursive(in, this.level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
        if (recreated != null) {
            this.level.addWithUUID(recreated);
        }
    }
}
