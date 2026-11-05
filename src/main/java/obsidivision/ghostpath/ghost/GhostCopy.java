package obsidivision.ghostpath.ghost;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import obsidivision.ghostpath.GhostPath;
import obsidivision.ghostpath.mixin.ChunkMapAccessor;
import obsidivision.ghostpath.mixin.EntitySectionManagerAccessor;
import obsidivision.ghostpath.mixin.EntityStorageAccessor;
import obsidivision.ghostpath.mixin.GhostEntityAccessor;
import obsidivision.ghostpath.mixin.GhostGaussianAccessor;
import obsidivision.ghostpath.mixin.GhostLevelAccessor;
import obsidivision.ghostpath.mixin.GhostPistonAccessor;
import obsidivision.ghostpath.mixin.GhostProjectileAccessor;
import obsidivision.ghostpath.mixin.GhostRandomAccessor;
import obsidivision.ghostpath.mixin.GhostServerLevelAccessor;
import obsidivision.ghostpath.mixin.HopperBlockEntityAccessor;
import obsidivision.ghostpath.mixin.LevelTicksAccessor;
import obsidivision.ghostpath.mixin.LevelWeatherAccessor;
import obsidivision.ghostpath.mixin.LivingEntityAccessor;
import obsidivision.ghostpath.mixin.ServerLevelAccessor;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.Weather;
import net.minecraft.world.clock.PackedClockStates;
import obsidivision.ghostpath.record.WatchedRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies a rectangle of real chunks into an empty ghost, at the same coordinates (DESIGN step 2). Whole chunks, every
 * section, so reads anywhere in a copied chunk match. Run between ticks (a command or a game-test callback runs after
 * all levels ticked, MinecraftServer.java:1108-1134), so the tick drain sets are empty.
 * <p>Order of the copy:
 * <ol>
 * <li>Ghost tickets at the real chunks' loading and simulation levels; ghost chunks loaded to FULL and waited for until
 * their status, block ticking, entity ticking and entity loading match the real chunks (fails loudly otherwise).</li>
 * <li>Game time set to the real overworld's.</li>
 * <li>Sections (block states and biomes, no updates), heightmaps, sky light sources, inhabited time; light data.</li>
 * <li>Block entities, in the real block-entity ticker order; transient hopper and moving-piston state.</li>
 * <li>Scheduled ticks via {@code copyAreaFrom} (count asserted), then the ghost's sub-tick counter past them.</li>
 * <li>Block events, in queue order.</li>
 * <li>Entities in the real entity tick order, with the same ids, exact positions, transient state and own random.</li>
 * <li>Weather.</li>
 * </ol>
 */
public final class GhostCopy {
    private static final Logger LOGGER = LoggerFactory.getLogger("ghostpath");

    /** What was copied. */
    public record Report(int chunks, int sections, int blockEntities, int blockTicks, int fluidTicks, int blockEvents, int entities,
                         int entitiesSkipped, int randomsNotCopied, long gameTime, Map<Long, int[]> levels) {
        public List<String> lines() {
            return List.of(
                "Ghost copy at game time " + this.gameTime + ": " + this.chunks + " chunks (" + this.sections + " sections), "
                    + this.blockEntities + " block entities, " + this.blockTicks + " block ticks, " + this.fluidTicks + " fluid ticks, "
                    + this.blockEvents + " block events, " + this.entities + " entities",
                "Not copied: " + this.entitiesSkipped + " entities (players or not saveable), " + this.randomsNotCopied + " entity randoms");
        }
    }

    private GhostCopy() {
    }

    /**
     * Copies the chunks {@code min..max} (inclusive) of {@code real} into the fresh {@code ghost}. If the copy fails, the
     * ghost is discarded (which ends its autosave hold) and the error is rethrown.
     */
    public static Report copy(ServerLevel real, GhostLevel ghost, ChunkPos min, ChunkPos max) {
        return copy(real, ghost, min, max, Start.NOW);
    }

    /**
     * What the ghost starts with that is not in the copied chunks (step 3): the weather and the world clocks. A recording
     * restores the world but not these (they move on while it runs), so the ghost check starts the ghost from the state at
     * the recording's start. {@link #NOW} copies the real level's current state (step 2).
     *
     * <p>The game time too (step 3 ruling): the ghost's game time is the real game time at the recording's start, not at
     * the copy, because some blocks act on {@code gameTime % n} (the daylight detector, {@code DaylightDetectorBlock.java:111}).
     * Everything the copy takes from the real level that is an absolute game time is moved by the same difference
     * ({@code gameTime - real game time}): the pending scheduled ticks' trigger ticks (so every remaining delay is kept),
     * the hopper's {@code tickedGameTime}, a moving piston's {@code lastTicked}, an entity's {@code pistonDeltasGameTime}.
     *
     * @param weather  the weather to start with, or null for the real level's weather now
     * @param clocks   the world clocks to start with, or null for the real level's clocks now
     * @param gameTime the game time to start with, or null for the real level's game time now
     */
    public record Start(@org.jspecify.annotations.Nullable Weather weather, @org.jspecify.annotations.Nullable PackedClockStates clocks,
                        @org.jspecify.annotations.Nullable Long gameTime) {
        public static final Start NOW = new Start(null, null, null);

        public Start(@org.jspecify.annotations.Nullable Weather weather, @org.jspecify.annotations.Nullable PackedClockStates clocks) {
            this(weather, clocks, null);
        }
    }

    public static Report copy(ServerLevel real, GhostLevel ghost, ChunkPos min, ChunkPos max, Start start) {
        try {
            Prepared prepared = prepare(real, ghost, min, max, start);
            prepared.waitBlocking();
            return prepared.finish();
        } catch (RuntimeException e) {
            ghost.discard();
            throw e;
        }
    }

    /**
     * A copy in two steps, so that a caller can wait for the ghost's chunks without blocking the server (the live rerun,
     * step 4): {@link #prepare} adds the tickets, {@link Prepared#poll} (once per server tick) runs the ghost's own chunk
     * work and says when the ghost chunks are at the real chunks' levels, {@link Prepared#finish} copies the contents in
     * one go (so the copy is one consistent moment of the real world). {@link #copy} is both, with a blocking wait. If a
     * step throws, the caller discards the ghost.
     */
    public static final class Prepared {
        private final ServerLevel real;
        private final GhostLevel ghost;
        private final List<ChunkPos> chunks;
        private Map<Long, int[]> levels = new LinkedHashMap<>();
        private final Start start;
        private final Map<Long, String> upgradeStatus;
        private boolean started;
        private final Set<Long> disk;
        private final Map<Long, String> realStatus;
        /** Chunks sit at levels other than the real ones (from the save, or lazy ones made ticking): only "loaded in the ghost" can be checked. */
        private final boolean relaxed;

        private Prepared(ServerLevel real, GhostLevel ghost, List<ChunkPos> chunks, Start start, Set<Long> disk, Map<Long, String> status, boolean upgraded, Map<Long, String> upgradeStatus) {
            this.real = real;
            this.ghost = ghost;
            this.chunks = chunks;
            this.upgradeStatus = upgradeStatus;
            this.start = start;
            this.disk = disk;
            this.realStatus = status;
            this.relaxed = !disk.isEmpty() || upgraded;
        }

        /** A disk chunk whose unload in the real world is still under way: its latest state is not in the region storage yet (see ChunkMapAccessor). */
        private boolean unloadInFlight() {
            ChunkMapAccessor map = (ChunkMapAccessor) this.real.getChunkSource().chunkMap;
            for (long k : this.disk) {
                if (map.ghostpath$pendingUnloads().containsKey(k) || map.ghostpath$toDrop().contains(k)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The second half of {@link #prepare}: the saved data of the disk chunks goes into the ghost's storage, then the tickets start the
         * ghost loading them (in that order). Reading the real storage while a chunk's unload-save is still to be handed to its IOWorker
         * would give the state from before the unload (a test saw a chunk full of air), so with {@code block == false} this waits (returns
         * false, the caller tries again next server tick) until none of the disk chunks is being unloaded. A blocking caller cannot wait
         * for the real server and goes ahead (copies with save chunks are not blocking).
         */
        private boolean ensureStarted(boolean block) {
            if (this.started) {
                return true;
            }
            if (!block && this.unloadInFlight()) {
                return false;
            }
            if (!this.disk.isEmpty()) {
                writeSavedChunks(this.real, this.ghost, this.chunks, this.disk);
            }
            this.levels = addTickets(this.real, this.ghost, this.chunks, this.disk, this.upgradeStatus);
            this.started = true;
            return true;
        }

        /** Blocks (server thread, managed) until the ghost chunks have the real chunks' levels and status; at most 20 s. */
        void waitBlocking() {
            this.ensureStarted(true);
            for (ChunkPos c : this.chunks) {
                if (this.ghost.getChunkSource().getChunk(c.x(), c.z(), ChunkStatus.FULL, true) == null) {
                    throw new IllegalStateException("ghost copy: ghost chunk " + c + " did not load");
                }
            }
            this.ghost.pump(this::reached, "ghost chunks to reach the real chunks' levels and status");
        }

        /** Runs the ghost's own pending chunk and entity-section work once; true when the ghost chunks match the real ones. */
        public boolean poll() {
            if (!this.ensureStarted(false)) {
                return false;
            }
            this.ghost.pollSlice(POLL_SLICE_NANOS);
            return this.reached();
        }

        private boolean reached() {
            DistanceManager ghostLevels = this.ghost.getChunkSource().chunkMap.getDistanceManager();
            for (ChunkPos c : this.chunks) {
                long k = c.pack();
                int[] want = this.levels.get(k);
                if (this.disk.contains(k)) { // taken from the save: only the ghost's own state matters
                    if (this.ghost.getChunkSource().getChunkNow(c.x(), c.z()) == null || !this.ghost.areEntitiesLoaded(k)) {
                        return false;
                    }
                    continue;
                }
                if (this.relaxed) {
                    // chunks taken from the save sit at an entity-ticking level and lift the levels of their neighbours (a rim chunk the real
                    // level has at 33 is 32 here): with those, only "loaded in the ghost" can be checked
                    if (this.ghost.getChunkSource().getChunkNow(c.x(), c.z()) == null || !this.ghost.areEntitiesLoaded(k)) {
                        return false;
                    }
                    continue;
                }
                if (this.ghost.getChunkSource().getChunkNow(c.x(), c.z()) == null
                    || ghostLevels.getChunkLevel(k, false) != want[0] || ghostLevels.getChunkLevel(k, true) != want[1]
                    || !this.ghost.areEntitiesLoaded(k)
                    || this.ghost.getChunkSource().isPositionTicking(k) != this.real.getChunkSource().isPositionTicking(k)
                    || this.ghost.areEntitiesActuallyLoadedAndTicking(c) != this.real.areEntitiesActuallyLoadedAndTicking(c)) {
                    return false;
                }
            }
            return true;
        }

        /** For a timeout message: the chunks that are not ready, with why. */
        public String describeNotReady() {
            if (!this.started) {
                return "the save is not read yet: a disk chunk is still being unloaded in the real world";
            }
            StringBuilder out = new StringBuilder();
            DistanceManager ghostLevels = this.ghost.getChunkSource().chunkMap.getDistanceManager();
            for (ChunkPos c : this.chunks) {
                long k = c.pack();
                boolean chunk = this.ghost.getChunkSource().getChunkNow(c.x(), c.z()) != null;
                boolean entities = this.ghost.areEntitiesLoaded(k);
                int[] want = this.levels.get(k);
                int l0 = ghostLevels.getChunkLevel(k, false);
                int l1 = ghostLevels.getChunkLevel(k, true);
                if (!chunk || !entities || !this.relaxed && (l0 != want[0] || l1 != want[1])) {
                    out.append(c).append(this.disk.contains(k) ? " (save)" : "").append(": chunk ").append(chunk).append(", entities ").append(entities).append(", levels ").append(l0).append('/').append(l1).append(" want ").append(want[0]).append('/').append(want[1]).append("; ");
                }
            }
            return out.toString();
        }

        /** Copies the contents. The real chunks must still be loaded (FULL, with entities), else it throws. */
        public Report finish() {
            if (!this.started) {
                throw new IllegalStateException("ghost copy: finish() before poll() reported ready");
            }
            return copyContents(this.real, this.ghost, this.chunks, this.levels, this.start, this.disk);
        }

        /**
         * The copied chunks that are <b>not entity ticking in the real world</b>, by the real levels at the moment of the copy: chunk key
         * to {@code "unloaded"} (not loaded in the real world: its entities are not there at all) or {@code "lazy"} (loaded, but its
         * entities do not tick: block-ticking or border level, or its simulation level is too far). A chunk that ticks is not in the map.
         * Chunks taken from the save are in it, though the ghost ticks them: that is the point of the flag.
         */
        public Map<Long, String> realNotTicking() {
            return this.realStatus;
        }
    }

    /**
     * How long one {@link Prepared#poll} keeps running the ghost's own pending tasks. Loading a ghost chunk is a chain of main-thread
     * tasks (each chunk status, the light, the entity section); one task per server tick is fast enough in the game tests, which
     * tick as fast as they can, but at the real 20 ticks a second the chain of a few chunks would take longer than the wait limit.
     */
    static final long POLL_SLICE_NANOS = 4_000_000L;

    /** The level of an entity-ticking chunk (ChunkLevel.java:12, private): what a chunk taken from the save gets in the ghost. */
    private static final int ENTITY_TICKING_LEVEL = 31;

    /** Checks the rectangle, marks the ghost as copied into and adds the tickets (no waiting). Every chunk must be loaded in the real level. */
    public static Prepared prepare(ServerLevel real, GhostLevel ghost, ChunkPos min, ChunkPos max, Start start) {
        return prepare(real, ghost, min, max, start, false, Set.of());
    }

    /**
     * As above; with {@code allowDisk} a chunk that is not loaded in the real level (not FULL with entities) is taken from the real
     * level's <b>save</b> instead, without loading it there (R9): its saved chunk data is read ({@code ChunkMap.read}, which also sees
     * writes still queued) and written into the ghost's own throwaway storage, and the ghost loads it from there with its own chunk
     * code (blocks, light, heightmaps, block entities, scheduled ticks as saved). It gets the levels of an entity-ticking chunk
     * ("as if it were loaded": the preview shows where things would go, not that the real chunk would not tick). No entities (they are
     * in another file), no block events, and nothing newer than the save. {@code forceDisk} (tests) treats those chunks as saved
     * ones even if they are loaded.
     */
    public static Prepared prepare(ServerLevel real, GhostLevel ghost, ChunkPos min, ChunkPos max, Start start, boolean allowDisk, Set<ChunkPos> forceDisk) {
        return prepare(real, ghost, min, max, start, allowDisk, forceDisk, null);
    }

    /**
     * {@code core} ({@code {minX, minZ, maxX, maxZ}} in chunks, or null): the machine's own copied area, the rectangle of the first,
     * unwidened copy. Chunks inside it keep the loading levels the real world has (ruling: a lazy chunk there stays lazy, its entities stay
     * frozen, as in the recording and in the ghost check); only chunks that widening added are made "as if loaded" (save chunks always,
     * lazy loaded chunks too). Known limit: a core chunk next to a chunk that is made ticking is lifted by the ticket spreading
     * (level 33 becomes 32: block ticks run there, entities still do not).
     */
    public static Prepared prepare(ServerLevel real, GhostLevel ghost, ChunkPos min, ChunkPos max, Start start, boolean allowDisk, Set<ChunkPos> forceDisk, int @org.jspecify.annotations.Nullable [] core) {
        if (CauseTracker.active() != null) {
            throw new IllegalStateException("ghost copy during a recording");
        }
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = Math.min(min.x(), max.x()); x <= Math.max(min.x(), max.x()); x++) {
            for (int z = Math.min(min.z(), max.z()); z <= Math.max(min.z(), max.z()); z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        Set<Long> disk = new java.util.HashSet<>();
        List<ChunkPos> loaded = new ArrayList<>();
        for (ChunkPos c : chunks) {
            boolean isLoaded = real.getChunkSource().getChunkNow(c.x(), c.z()) != null && real.areEntitiesLoaded(c.pack());
            if (forceDisk.contains(c) || allowDisk && !isLoaded) {
                disk.add(c.pack());
            } else {
                loaded.add(c);
            }
        }
        checkRealLoaded(real, loaded);
        if (!ghost.markCopied(chunks)) {
            throw new IllegalStateException("ghost copy: the ghost is not empty (one copy per ghost)");
        }
        if (!disk.isEmpty()) {
            // A saved scheduled tick stores its delay relative to the save (SavedTick.java:12 "delay", ScheduledTick.toSavedTick), and
            // the chunk turns it into a trigger tick with the level's game time at the moment it loads (ServerLevel.java:1751
            // levelChunk.unpackTicks(getGameTime())). A real chunk loaded now would get "now + delay"; so the ghost's game time is the
            // start time before its chunks load, and the saved ticks come out as start + delay: the same remaining delay, no shift.
            ghost.ghostData().setGameTime(start.gameTime() != null ? start.gameTime() : real.getGameTime());
        }
        // a widened run (allowDisk) predicts where things would go "as if loaded": a loaded chunk whose entities do not tick in the real world
        // (border levels 32/33, "lazy") is given the levels of a ticking one too, like a chunk from the save; the flag keeps the difference
        Map<Long, String> status = realNotTicking(real, chunks);
        Map<Long, String> upgradeStatus = new LinkedHashMap<>();
        if (allowDisk) {
            for (ChunkPos c : chunks) {
                boolean inCore = core != null && c.x() >= core[0] && c.x() <= core[2] && c.z() >= core[1] && c.z() <= core[3];
                if (!inCore && status.containsKey(c.pack())) {
                    upgradeStatus.put(c.pack(), status.get(c.pack()));
                }
            }
        }
        boolean upgrade = upgradeStatus.containsValue("lazy");
        Prepared prepared = new Prepared(real, ghost, chunks, start, disk, status, upgrade, upgradeStatus);
        if (disk.isEmpty()) {
            prepared.ensureStarted(true); // nothing to wait for: tickets now, as before
        } // else: the first poll() starts it, once no disk chunk is being unloaded
        return prepared;
    }

    /**
     * A chunk below FULL that the real level still holds as a complete chunk with changes nobody has saved: a chunk three or more chunks
     * outside the nearest ticket is not accessible any more (getChunkNow is null) but stays in its holder until it drops out of the loaded
     * range altogether, and is written then (ChunkMap.scheduleUnload :508-531); ChunkMap.save never writes a downgraded chunk over a full one
     * (:748-752), so until then the region storage holds an older state. Serialized the way the server would save it
     * ({@code SerializableChunkData.copyOf(...).write()}, ChunkMap.java:763-764), or null if there is nothing newer than the storage.
     */
    private static net.minecraft.nbt.@org.jspecify.annotations.Nullable CompoundTag unsavedInMemory(ServerLevel real, ChunkPos c) {
        net.minecraft.server.level.ChunkHolder holder = real.getChunkSource().chunkMap.getUpdatingChunkIfPresent(c.pack());
        if (holder != null && holder.getLatestChunk() instanceof LevelChunk chunk && chunk.isUnsaved()) {
            return net.minecraft.world.level.chunk.storage.SerializableChunkData.copyOf(real, chunk).write();
        }
        return null;
    }

    /** A level's "entities" region storage (EntityStorage.simpleRegionStorage), the one its own entity manager reads and writes through. */
    private static net.minecraft.world.level.chunk.storage.SimpleRegionStorage entityRegions(ServerLevel level) {
        Object storage = ((EntitySectionManagerAccessor) ((GhostServerLevelAccessor) level).ghostpath$entityManager()).ghostpath$permanentStorage();
        return ((EntityStorageAccessor) storage).ghostpath$simpleRegionStorage();
    }

    /**
     * Reads the saved data of the disk chunks from the real level's chunk storage and writes it into the ghost's; the same for the
     * entities, which are in another region file ("entities"). Both reads go through the real level's own storage objects, i.e. their
     * {@code IOWorker}s (SimpleRegionStorage.java:38 read -> IOWorker.java:130 loadAsync): a write still queued is answered from the queue
     * (:132), the file is read on the worker's own thread through the server's region cache (:138), so it cannot race with a save.
     * The ghost then loads the entities itself when the chunk becomes accessible (PersistentEntitySectionManager.updateChunkStatus ->
     * requestChunkLoad -> EntityStorage.loadEntities), with its own entity ids.
     */
    private static void writeSavedChunks(ServerLevel real, GhostLevel ghost, List<ChunkPos> chunks, Set<Long> disk) {
        List<ChunkPos> wanted = chunks.stream().filter(c -> disk.contains(c.pack())).toList();
        List<java.util.concurrent.CompletableFuture<java.util.Optional<net.minecraft.nbt.CompoundTag>>> reads = new ArrayList<>();
        List<java.util.concurrent.CompletableFuture<java.util.Optional<net.minecraft.nbt.CompoundTag>>> entityReads = new ArrayList<>();
        var realEntities = entityRegions(real);
        var ghostEntities = entityRegions(ghost);
        for (ChunkPos c : wanted) {
            reads.add(real.getChunkSource().chunkMap.read(c));
            entityReads.add(realEntities.read(c));
        }
        List<java.util.concurrent.CompletableFuture<Void>> writes = new ArrayList<>();
        int entityChunks = 0;
        int chunkData = 0;
        int fromMemory = 0;
        List<ChunkPos> neverSaved = new ArrayList<>();
        try {
            for (int i = 0; i < wanted.size(); i++) {
                java.util.Optional<net.minecraft.nbt.CompoundTag> tag = reads.get(i).get(20, java.util.concurrent.TimeUnit.SECONDS);
                net.minecraft.nbt.CompoundTag newer = unsavedInMemory(real, wanted.get(i));
                if (newer != null) {
                    tag = java.util.Optional.of(newer);
                    fromMemory++;
                }
                if (tag.isPresent()) { // never saved: the ghost generates it like the real level would (a void world)
                    writes.add(ghost.getChunkSource().chunkMap.write(wanted.get(i), tag.get()));
                    chunkData++;
                } else {
                    neverSaved.add(wanted.get(i));
                }
                java.util.Optional<net.minecraft.nbt.CompoundTag> entities = entityReads.get(i).get(20, java.util.concurrent.TimeUnit.SECONDS);
                if (entities.isPresent()) {
                    writes.add(ghostEntities.write(wanted.get(i), entities.get()));
                    entityChunks++;
                }
            }
            for (java.util.concurrent.CompletableFuture<Void> w : writes) {
                w.get(20, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("ghost copy: could not read saved chunks: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ghost copy: interrupted while reading saved chunks", e);
        }
        LOGGER.info("[ghostpath] ghost copy: {} chunks taken from the save ({} had chunk data, {} of them the real level's unsaved in-memory state; {} saved entity data){}", wanted.size(), chunkData, fromMemory, entityChunks,
            neverSaved.isEmpty() ? "" : "; NOT SAVED (the ghost generates them as void): " + neverSaved);
    }

    private static void checkRealLoaded(ServerLevel real, List<ChunkPos> chunks) {
        for (ChunkPos c : chunks) {
            if (real.getChunkSource().getChunkNow(c.x(), c.z()) == null || !real.areEntitiesLoaded(c.pack())) {
                throw new IllegalStateException("ghost copy: real chunk " + c + " is not loaded (FULL, with entities)");
            }
        }
    }

    private static Report copyContents(ServerLevel real, GhostLevel ghost, List<ChunkPos> allChunks, Map<Long, int[]> levels, Start start, Set<Long> disk) {
        List<ChunkPos> chunks = allChunks.stream().filter(c -> !disk.contains(c.pack())).toList();
        if (CauseTracker.active() != null) {
            throw new IllegalStateException("ghost copy during a recording");
        }
        checkRealLoaded(real, chunks);
        // the ghost's game time is the one asked for (the recording's start); every absolute game time taken from the real
        // level moves by the same difference, so remaining delays and "ticked this game tick" comparisons are kept
        long gameTime = start.gameTime() != null ? start.gameTime() : disk.isEmpty() ? real.getGameTime() : ghost.getGameTime(); // with disk chunks the ghost's time was set before they loaded
        long shift = gameTime - real.getGameTime();
        ghost.ghostData().setGameTime(gameTime);
        ghost.setClock(start.clocks() != null ? start.clocks() : real.clockManager().packState());

        int sections = 0;
        for (ChunkPos c : chunks) {
            sections += copyChunk(real, ghost, c);
        }
        ghost.flushLight();
        int blockEntities = copyBlockEntities(real, ghost, chunks, shift);
        BoundingBox box = new BoundingBox(allChunks.getFirst().getMinBlockX(), real.getMinY(), allChunks.getFirst().getMinBlockZ(),
            allChunks.getLast().getMaxBlockX(), real.getMaxY(), allChunks.getLast().getMaxBlockZ());
        int blockTicks = copyTicks(real.getBlockTicks(), ghost.getBlockTicks(), box, "block", shift, !disk.isEmpty(), disk);
        int fluidTicks = copyTicks(real.getFluidTicks(), ghost.getFluidTicks(), box, "fluid", shift, !disk.isEmpty(), disk);
        // copyAreaFrom renumbers sub-tick order above the source's max (LevelTicks.java:264-269), not from the ghost's
        // own counter (Level.java:1088-1089): move the counter past them, so a tick the ghost schedules later sorts after
        // them, as a new tick does in the real level.
        long next = Math.max(maxSubTick(ghost.getBlockTicks()), maxSubTick(ghost.getFluidTicks())) + 1L;
        GhostLevelAccessor ghostCounter = (GhostLevelAccessor) (Object) ghost;
        ghostCounter.ghostpath$setSubTickCount(Math.max(ghostCounter.ghostpath$subTickCount(), next));

        int blockEvents = 0;
        ObjectLinkedOpenHashSet<BlockEventData> into = ((ServerLevelAccessor) (Object) ghost).ghostpath$blockEvents();
        for (BlockEventData e : ((ServerLevelAccessor) real).ghostpath$blockEvents()) {
            if (box.isInside(e.pos()) && !disk.contains(ChunkPos.pack(e.pos().getX() >> 4, e.pos().getZ() >> 4))) {
                into.add(e);
                blockEvents++;
            }
        }
        int[] entityCounts = copyEntities(real, ghost, box, shift, disk);
        copyWeather(real, ghost, start.weather());
        Report report = new Report(allChunks.size(), sections, blockEntities, blockTicks, fluidTicks, blockEvents, entityCounts[0], entityCounts[1],
            entityCounts[2], ghost.getGameTime(), levels);
        report.lines().forEach(l -> LOGGER.info("[ghostpath] {}", l));
        return report;
    }

    // ---- chunk levels ----

    /**
     * What the real world does with each chunk's entities (ChunkLevel.java: entity ticking needs level 31 or less; 32 is block
     * ticking, 33 border, more is not accessible): the loading and the simulation level from the real distance manager, the same
     * numbers {@link #addTickets} copies for loaded chunks. Only chunks that do not tick are returned.
     */
    static Map<Long, String> realNotTicking(ServerLevel real, List<ChunkPos> chunks) {
        DistanceManager realLevels = real.getChunkSource().chunkMap.getDistanceManager();
        Map<Long, String> out = new LinkedHashMap<>();
        for (ChunkPos c : chunks) {
            int loading = realLevels.getChunkLevel(c.pack(), false);
            int simulation = realLevels.getChunkLevel(c.pack(), true);
            if (loading > net.minecraft.server.level.ChunkLevel.byStatus(net.minecraft.server.level.FullChunkStatus.FULL)) {
                out.put(c.pack(), "unloaded");
            } else if (!net.minecraft.server.level.ChunkLevel.isEntityTicking(loading) || !net.minecraft.server.level.ChunkLevel.isEntityTicking(simulation)) {
                out.put(c.pack(), "lazy");
            }
        }
        return out;
    }

    /**
     * Real levels (DistanceManager.java:143) come from tickets spread out by one level per chunk (Chebyshev distance), so
     * a ticket at each copied chunk at its own real level gives every copied chunk exactly its real level in the ghost.
     */
    private static Map<Long, int[]> addTickets(ServerLevel real, GhostLevel ghost, List<ChunkPos> chunks, Set<Long> disk, Map<Long, String> upgradeStatus) {
        DistanceManager realLevels = real.getChunkSource().chunkMap.getDistanceManager();
        Map<Long, int[]> levels = new LinkedHashMap<>();
        for (ChunkPos c : chunks) {
            boolean fromSave = disk.contains(c.pack()) || "lazy".equals(upgradeStatus.get(c.pack()));
            int loading = fromSave ? ENTITY_TICKING_LEVEL : realLevels.getChunkLevel(c.pack(), false);
            int simulation = fromSave ? ENTITY_TICKING_LEVEL : realLevels.getChunkLevel(c.pack(), true);
            levels.put(c.pack(), new int[] {loading, simulation});
            ghost.getChunkSource().addTicket(new Ticket(GhostPath.GHOST_LOADING_TICKET, loading), c);
            if (simulation <= net.minecraft.server.level.ChunkLevel.MAX_LEVEL) {
                ghost.getChunkSource().addTicket(new Ticket(GhostPath.GHOST_SIMULATION_TICKET, simulation), c);
            }
        }
        return levels;
    }

    // ---- blocks ----

    private static int copyChunk(ServerLevel real, GhostLevel ghost, ChunkPos c) {
        LevelChunk from = real.getChunk(c.x(), c.z());
        LevelChunk to = ghost.getChunk(c.x(), c.z());
        LevelChunkSection[] src = from.getSections();
        LevelChunkSection[] dst = to.getSections();
        int nonEmpty = 0;
        for (int i = 0; i < src.length; i++) {
            dst[i] = src[i].copy(); // block states and biomes, no block updates
            if (!src[i].hasOnlyAir()) {
                nonEmpty++;
            }
        }
        for (Map.Entry<Heightmap.Types, Heightmap> e : from.getHeightmaps()) {
            to.setHeightmap(e.getKey(), e.getValue().getRawData().clone());
        }
        to.initializeLightSources(); // sky light sources from the copied blocks (ChunkAccess.java:478-479)
        to.setInhabitedTime(from.getInhabitedTime());
        // light: the section status first (as ThreadedLevelLightEngine.initializeLight, :138-151), then the real data
        LevelLightEngine realLight = real.getLightEngine();
        LevelLightEngine ghostLight = ghost.getChunkSource().getLightEngine();
        for (int i = 0; i < src.length; i++) {
            if (!src[i].hasOnlyAir()) {
                ghostLight.updateSectionStatus(SectionPos.of(c, from.getSectionYFromSectionIndex(i)), false);
            }
        }
        for (int y = realLight.getMinLightSection(); y < realLight.getMaxLightSection(); y++) {
            SectionPos sp = SectionPos.of(c, y);
            for (LightLayer layer : LightLayer.values()) {
                DataLayer data = realLight.getLayerListener(layer).getDataLayerData(sp);
                if (data != null) {
                    ghostLight.queueSectionData(layer, sp, data.copy());
                }
            }
        }
        return nonEmpty;
    }

    /** Block entities in the order they tick in the real level (Level.java:111-113, 533-539), then the non-ticking ones. */
    private static int copyBlockEntities(ServerLevel real, GhostLevel ghost, List<ChunkPos> chunks, long shift) {
        Set<Long> keys = new java.util.HashSet<>();
        chunks.forEach(c -> keys.add(c.pack()));
        Set<BlockPos> order = new LinkedHashSet<>();
        GhostLevelAccessor tickers = (GhostLevelAccessor) real;
        for (List<TickingBlockEntity> list : List.of(tickers.ghostpath$blockEntityTickers(), tickers.ghostpath$pendingBlockEntityTickers())) {
            for (TickingBlockEntity t : list) {
                if (!t.isRemoved() && keys.contains(ChunkPos.pack(t.getPos()))) {
                    order.add(t.getPos().immutable());
                }
            }
        }
        for (ChunkPos c : chunks) {
            order.addAll(real.getChunk(c.x(), c.z()).getBlockEntities().keySet());
        }
        int n = 0;
        for (BlockPos pos : order) {
            BlockEntity from = real.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
            if (from == null) {
                continue;
            }
            CompoundTag tag = from.saveWithFullMetadata(real.registryAccess());
            BlockEntity to = BlockEntity.loadStatic(pos, from.getBlockState(), tag, ghost.registryAccess());
            if (to == null) {
                throw new IllegalStateException("ghost copy: block entity at " + pos + " could not be loaded from its NBT");
            }
            if (from instanceof HopperBlockEntity && to instanceof HopperBlockEntity) {
                ((HopperBlockEntityAccessor) to).ghostpath$setTickedGameTime(((HopperBlockEntityAccessor) from).ghostpath$tickedGameTime() + shift);
            }
            if (from instanceof PistonMovingBlockEntity && to instanceof PistonMovingBlockEntity) {
                GhostPistonAccessor f = (GhostPistonAccessor) from;
                GhostPistonAccessor t = (GhostPistonAccessor) to;
                t.ghostpath$setProgressO(f.ghostpath$progressO());
                t.ghostpath$setLastTicked(f.ghostpath$lastTicked() + shift);
                t.ghostpath$setDeathTicks(f.ghostpath$deathTicks());
            }
            ghost.setBlockEntity(to);
            n++;
        }
        return n;
    }

    // ---- ticks ----

    /**
     * What {@code LevelTicks.copyAreaFrom} does (LevelTicks.java:258-272: sub-tick order renumbered above the source's max,
     * the relative order kept), except that every trigger tick moves by {@code shift}, so each tick keeps its remaining
     * delay when the ghost's game time is not the real one. Drain sets are empty between ticks (the copy runs after all
     * levels ticked), so the containers hold everything; the count is asserted (R3).
     */
    @SuppressWarnings("unchecked")
    private static <T> int copyTicks(LevelTicks<T> from, LevelTicks<T> to, BoundingBox box, String what, long shift, boolean ghostHasSavedTicks, Set<Long> disk) {
        List<ScheduledTick<T>> toAdd = new ArrayList<>();
        for (Long2ObjectMap.Entry<LevelChunkTicks<T>> e : ((LevelTicksAccessor<T>) from).ghostpath$allContainers().long2ObjectEntrySet()) {
            // not the ticks of a chunk the ghost loaded from the save: it brought its own (a chunk that is loaded in the real level but whose
            // entities are not yet counts as a save chunk too; copying its real ticks as well made them duplicates, dropped by the ghost's
            // container, and the copy failed with "ticks dropped": the rare widen failure)
            e.getValue().getAll().filter(t -> box.isInside(t.pos()) && !disk.contains(ChunkPos.pack(t.pos().getX() >> 4, t.pos().getZ() >> 4))).forEach(toAdd::add);
        }
        int before = to.count();
        if (before != 0 && !ghostHasSavedTicks) { // chunks taken from the save arrive with the ticks they were saved with
            throw new IllegalStateException("ghost copy: ghost already has " + before + " " + what + " ticks");
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (ScheduledTick<T> t : toAdd) {
            min = Math.min(min, t.subTickOrder());
            max = Math.max(max, t.subTickOrder());
        }
        for (ScheduledTick<T> t : toAdd) {
            to.schedule(new ScheduledTick<>(t.type(), t.pos(), t.triggerTick() + shift, t.priority(), t.subTickOrder() - min + max + 1L));
        }
        int copied = to.count() - before;
        if (copied != toAdd.size()) {
            // LevelTicks.schedule drops a tick whose chunk container is missing (LevelTicks.java:71-79); ruling R3: fail loudly
            throw new IllegalStateException("ghost copy: " + what + " ticks dropped: " + copied + " of " + toAdd.size() + " copied");
        }
        return copied;
    }

    @SuppressWarnings("unchecked")
    private static <T> long maxSubTick(LevelTicks<T> ticks) {
        long max = -1L;
        for (LevelChunkTicks<T> c : ((LevelTicksAccessor<T>) ticks).ghostpath$allContainers().values()) {
            for (ScheduledTick<T> t : (Iterable<ScheduledTick<T>>) c.getAll()::iterator) {
                max = Math.max(max, t.subTickOrder());
            }
        }
        return max;
    }

    // ---- entities ----

    /**
     * Entities whose chunk is in the box: first the roots of the real entity tick list in its order (ServerLevel.java:425;
     * passengers tick through their vehicle, :434-440), then any other entity in the box (not in the tick list). Each is
     * saved with its passengers and loaded into the ghost, keeps its id (Entity.hashCode is the id, Entity.java:429-435,
     * and the id picks behaviour: ItemEntity.java:141, Mob.java:718, AbstractBoat.java:620, ExperienceOrb.java:225,
     * Warden.java:289, ElderGuardian.java:67, Phantom.java:106), and gets the transient state NBT does not hold.
     * Returns {copied, skipped, randoms not copied}.
     */
    private static int[] copyEntities(ServerLevel real, GhostLevel ghost, BoundingBox box, long shift, Set<Long> disk) {
        List<Entity> roots = new ArrayList<>();
        Set<UUID> seen = new java.util.HashSet<>();
        ((GhostServerLevelAccessor) real).ghostpath$entityTickList().forEach(e -> {
            Entity root = e.getRootVehicle();
            if (!e.isRemoved() && inBox(box, root) && !inDiskChunk(disk, root) && seen.add(root.getUUID())) {
                roots.add(root);
            }
        });
        for (Entity e : real.getAllEntities()) {
            Entity root = e.getRootVehicle();
            if (!e.isRemoved() && inBox(box, root) && !inDiskChunk(disk, root) && seen.add(root.getUUID())) {
                roots.add(root);
            }
        }
        int copied = 0;
        int skipped = 0;
        int[] randoms = {0};
        for (Entity root : roots) {
            if (root instanceof ServerPlayer || root.getSelfAndPassengers().anyMatch(p -> p instanceof ServerPlayer)) {
                skipped += (int) root.getSelfAndPassengers().count();
                continue;
            }
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, real.registryAccess());
            if (!root.saveAsPassenger(out)) {
                skipped += (int) root.getSelfAndPassengers().count();
                continue;
            }
            Entity copy = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, ghost.registryAccess(), out.buildResult()),
                ghost, EntitySpawnReason.LOAD, EntityProcessor.NOP);
            if (copy == null) {
                skipped += (int) root.getSelfAndPassengers().count();
                continue;
            }
            Map<UUID, Entity> byId = new HashMap<>();
            root.getSelfAndPassengers().forEach(e -> byId.put(e.getUUID(), e));
            copy.getSelfAndPassengers().forEach(g -> {
                Entity r = byId.get(g.getUUID());
                if (r != null) {
                    g.setId(r.getId());
                    if (!copyTransient(r, g, shift)) {
                        randoms[0]++;
                    }
                }
            });
            copy.getSelfAndPassengers().forEach(ghost::addFreshEntity);
            copied += (int) copy.getSelfAndPassengers().count();
        }
        return new int[] {copied, skipped, randoms[0]};
    }

    /** A chunk taken from the save has its entities from the save (the ghost loaded them); only a test's forced disk chunk is loaded in the real level too. */
    private static boolean inDiskChunk(Set<Long> disk, Entity e) {
        BlockPos p = e.blockPosition();
        return !disk.isEmpty() && disk.contains(ChunkPos.pack(p.getX() >> 4, p.getZ() >> 4));
    }

    private static boolean inBox(BoundingBox box, Entity e) {
        BlockPos p = e.blockPosition();
        return p.getX() >= box.minX() && p.getX() <= box.maxX() && p.getZ() >= box.minZ() && p.getZ() <= box.maxZ();
    }

    /** Entity state outside NBT (Entity.java:221-305 and subclasses). Returns false if the own random could not be copied. */
    private static boolean copyTransient(Entity r, Entity g, long shift) {
        g.setPosRaw(r.getX(), r.getY(), r.getZ());
        g.setDeltaMovement(r.getDeltaMovement());
        g.setBoundingBox(r.getBoundingBox());
        g.setOnGround(r.onGround());
        g.xo = r.xo;
        g.yo = r.yo;
        g.zo = r.zo;
        g.xOld = r.xOld;
        g.yOld = r.yOld;
        g.zOld = r.zOld;
        g.yRotO = r.yRotO;
        g.xRotO = r.xRotO;
        g.horizontalCollision = r.horizontalCollision;
        g.verticalCollision = r.verticalCollision;
        g.verticalCollisionBelow = r.verticalCollisionBelow;
        g.minorHorizontalCollision = r.minorHorizontalCollision;
        g.hurtMarked = r.hurtMarked;
        g.moveDist = r.moveDist;
        g.flyDist = r.flyDist;
        g.fallDistance = r.fallDistance;
        g.noPhysics = r.noPhysics;
        g.tickCount = r.tickCount;
        g.invulnerableTime = r.invulnerableTime;
        g.isInPowderSnow = r.isInPowderSnow;
        g.wasInPowderSnow = r.wasInPowderSnow;
        g.mainSupportingBlockPos = r.mainSupportingBlockPos;
        GhostEntityAccessor ra = (GhostEntityAccessor) r;
        GhostEntityAccessor ga = (GhostEntityAccessor) g;
        ga.ghostpath$setBoardingCooldown(ra.ghostpath$boardingCooldown());
        ga.ghostpath$setStuckSpeedMultiplier(ra.ghostpath$stuckSpeedMultiplier());
        ga.ghostpath$setNextStep(ra.ghostpath$nextStep());
        ga.ghostpath$setWasTouchingWater(ra.ghostpath$wasTouchingWater());
        ga.ghostpath$setWasEyeInWater(ra.ghostpath$wasEyeInWater());
        ga.ghostpath$setFirstTick(ra.ghostpath$firstTick());
        System.arraycopy(ra.ghostpath$pistonDeltas(), 0, ga.ghostpath$pistonDeltas(), 0, 3);
        ga.ghostpath$setPistonDeltasGameTime(ra.ghostpath$pistonDeltasGameTime() + shift);
        ga.ghostpath$setOnGroundNoBlocks(ra.ghostpath$onGroundNoBlocks());
        ga.ghostpath$setLastKnownSpeed(ra.ghostpath$lastKnownSpeed());
        ga.ghostpath$setLastKnownPosition(ra.ghostpath$lastKnownPosition());
        if (r instanceof LivingEntity rl && g instanceof LivingEntity gl) {
            ((LivingEntityAccessor) gl).ghostpath$setLastHurt(((LivingEntityAccessor) rl).ghostpath$lastHurt());
        }
        if (r instanceof Projectile rp && g instanceof Projectile gp) {
            ((GhostProjectileAccessor) gp).ghostpath$setLeftOwnerChecked(((GhostProjectileAccessor) rp).ghostpath$leftOwnerChecked());
        }
        return copyRandom(ra.ghostpath$random(), ga.ghostpath$random());
    }

    /** Continues the real entity's own random in the copy (LegacyRandomSource.java:14-15, MarsagliaPolarGaussian.java:8-9). */
    private static boolean copyRandom(RandomSource from, RandomSource to) {
        RandomSource f = from instanceof WatchedRandom w ? w.delegate() : from;
        RandomSource t = to instanceof WatchedRandom w ? w.delegate() : to;
        if (!(f instanceof LegacyRandomSource) || !(t instanceof LegacyRandomSource)) {
            return false;
        }
        GhostRandomAccessor fa = (GhostRandomAccessor) f;
        GhostRandomAccessor ta = (GhostRandomAccessor) t;
        ta.ghostpath$seed().set(fa.ghostpath$seed().get());
        GhostGaussianAccessor fg = (GhostGaussianAccessor) fa.ghostpath$gaussian();
        GhostGaussianAccessor tg = (GhostGaussianAccessor) ta.ghostpath$gaussian();
        tg.ghostpath$setNext(fg.ghostpath$next());
        tg.ghostpath$setHave(fg.ghostpath$have());
        return true;
    }

    // ---- weather ----

    /** Server weather data (ServerLevel.java:1691) copied into the ghost's own, plus the four Level fields (Level.java:120-123). */
    private static void copyWeather(ServerLevel real, GhostLevel ghost, @org.jspecify.annotations.Nullable Weather at) {
        Weather w = at != null ? at : Weather.of(real);
        ghost.setWeather(new WeatherData(w.clearWeatherTime(), w.rainTime(), w.thunderTime(), w.raining(), w.thundering()));
        LevelWeatherAccessor g = (LevelWeatherAccessor) (Object) ghost;
        g.ghostpath$setORainLevel(w.oRainLevel());
        g.ghostpath$setRainLevel(w.rainLevel());
        g.ghostpath$setOThunderLevel(w.oThunderLevel());
        g.ghostpath$setThunderLevel(w.thunderLevel());
    }
}
