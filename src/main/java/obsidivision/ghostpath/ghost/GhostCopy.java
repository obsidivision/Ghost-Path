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
     * @param weather the weather to start with, or null for the real level's weather now
     * @param clocks  the world clocks to start with, or null for the real level's clocks now
     */
    public record Start(@org.jspecify.annotations.Nullable Weather weather, @org.jspecify.annotations.Nullable PackedClockStates clocks) {
        public static final Start NOW = new Start(null, null);
    }

    public static Report copy(ServerLevel real, GhostLevel ghost, ChunkPos min, ChunkPos max, Start start) {
        try {
            return copyInto(real, ghost, min, max, start);
        } catch (RuntimeException e) {
            ghost.discard();
            throw e;
        }
    }

    private static Report copyInto(ServerLevel real, GhostLevel ghost, ChunkPos min, ChunkPos max, Start start) {
        if (CauseTracker.active() != null) {
            throw new IllegalStateException("ghost copy during a recording");
        }
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = Math.min(min.x(), max.x()); x <= Math.max(min.x(), max.x()); x++) {
            for (int z = Math.min(min.z(), max.z()); z <= Math.max(min.z(), max.z()); z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        for (ChunkPos c : chunks) {
            if (real.getChunkSource().getChunkNow(c.x(), c.z()) == null || !real.areEntitiesLoaded(c.pack())) {
                throw new IllegalStateException("ghost copy: real chunk " + c + " is not loaded (FULL, with entities)");
            }
        }
        if (!ghost.markCopied(chunks)) {
            throw new IllegalStateException("ghost copy: the ghost is not empty (one copy per ghost)");
        }
        Map<Long, int[]> levels = copyTicketLevels(real, ghost, chunks);
        ghost.ghostData().setGameTime(real.getGameTime());
        ghost.setClock(start.clocks() != null ? start.clocks() : real.clockManager().packState());

        int sections = 0;
        for (ChunkPos c : chunks) {
            sections += copyChunk(real, ghost, c);
        }
        ghost.flushLight();
        int blockEntities = copyBlockEntities(real, ghost, chunks);
        BoundingBox box = new BoundingBox(chunks.getFirst().getMinBlockX(), real.getMinY(), chunks.getFirst().getMinBlockZ(),
            chunks.getLast().getMaxBlockX(), real.getMaxY(), chunks.getLast().getMaxBlockZ());
        int blockTicks = copyTicks(real.getBlockTicks(), ghost.getBlockTicks(), box, "block");
        int fluidTicks = copyTicks(real.getFluidTicks(), ghost.getFluidTicks(), box, "fluid");
        // copyAreaFrom renumbers sub-tick order above the source's max (LevelTicks.java:264-269), not from the ghost's
        // own counter (Level.java:1088-1089): move the counter past them, so a tick the ghost schedules later sorts after
        // them, as a new tick does in the real level.
        long next = Math.max(maxSubTick(ghost.getBlockTicks()), maxSubTick(ghost.getFluidTicks())) + 1L;
        GhostLevelAccessor ghostCounter = (GhostLevelAccessor) (Object) ghost;
        ghostCounter.ghostpath$setSubTickCount(Math.max(ghostCounter.ghostpath$subTickCount(), next));

        int blockEvents = 0;
        ObjectLinkedOpenHashSet<BlockEventData> into = ((ServerLevelAccessor) (Object) ghost).ghostpath$blockEvents();
        for (BlockEventData e : ((ServerLevelAccessor) real).ghostpath$blockEvents()) {
            if (box.isInside(e.pos())) {
                into.add(e);
                blockEvents++;
            }
        }
        int[] entityCounts = copyEntities(real, ghost, box);
        copyWeather(real, ghost, start.weather());
        Report report = new Report(chunks.size(), sections, blockEntities, blockTicks, fluidTicks, blockEvents, entityCounts[0], entityCounts[1],
            entityCounts[2], ghost.getGameTime(), levels);
        report.lines().forEach(l -> LOGGER.info("[ghostpath] {}", l));
        return report;
    }

    // ---- chunk levels ----

    /**
     * Real levels (DistanceManager.java:143) come from tickets spread out by one level per chunk (Chebyshev distance), so
     * a ticket at each copied chunk at its own real level gives every copied chunk exactly its real level in the ghost.
     */
    private static Map<Long, int[]> copyTicketLevels(ServerLevel real, GhostLevel ghost, List<ChunkPos> chunks) {
        DistanceManager realLevels = real.getChunkSource().chunkMap.getDistanceManager();
        Map<Long, int[]> levels = new LinkedHashMap<>();
        for (ChunkPos c : chunks) {
            int loading = realLevels.getChunkLevel(c.pack(), false);
            int simulation = realLevels.getChunkLevel(c.pack(), true);
            levels.put(c.pack(), new int[] {loading, simulation});
            ghost.getChunkSource().addTicket(new Ticket(GhostPath.GHOST_LOADING_TICKET, loading), c);
            if (simulation <= net.minecraft.server.level.ChunkLevel.MAX_LEVEL) {
                ghost.getChunkSource().addTicket(new Ticket(GhostPath.GHOST_SIMULATION_TICKET, simulation), c);
            }
        }
        for (ChunkPos c : chunks) {
            if (ghost.getChunkSource().getChunk(c.x(), c.z(), ChunkStatus.FULL, true) == null) {
                throw new IllegalStateException("ghost copy: ghost chunk " + c + " did not load");
            }
        }
        DistanceManager ghostLevels = ghost.getChunkSource().chunkMap.getDistanceManager();
        ghost.pump(() -> {
            for (ChunkPos c : chunks) {
                long k = c.pack();
                int[] want = levels.get(k);
                if (ghostLevels.getChunkLevel(k, false) != want[0] || ghostLevels.getChunkLevel(k, true) != want[1]
                    || !ghost.areEntitiesLoaded(k)
                    || ghost.getChunkSource().isPositionTicking(k) != real.getChunkSource().isPositionTicking(k)
                    || ghost.areEntitiesActuallyLoadedAndTicking(c) != real.areEntitiesActuallyLoadedAndTicking(c)) {
                    return false;
                }
            }
            return true;
        }, "ghost chunks to reach the real chunks' levels and status");
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
    private static int copyBlockEntities(ServerLevel real, GhostLevel ghost, List<ChunkPos> chunks) {
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
                ((HopperBlockEntityAccessor) to).ghostpath$setTickedGameTime(((HopperBlockEntityAccessor) from).ghostpath$tickedGameTime());
            }
            if (from instanceof PistonMovingBlockEntity && to instanceof PistonMovingBlockEntity) {
                GhostPistonAccessor f = (GhostPistonAccessor) from;
                GhostPistonAccessor t = (GhostPistonAccessor) to;
                t.ghostpath$setProgressO(f.ghostpath$progressO());
                t.ghostpath$setLastTicked(f.ghostpath$lastTicked());
                t.ghostpath$setDeathTicks(f.ghostpath$deathTicks());
            }
            ghost.setBlockEntity(to);
            n++;
        }
        return n;
    }

    // ---- ticks ----

    @SuppressWarnings("unchecked")
    private static <T> int copyTicks(LevelTicks<T> from, LevelTicks<T> to, BoundingBox box, String what) {
        long expected = 0;
        for (Long2ObjectMap.Entry<LevelChunkTicks<T>> e : ((LevelTicksAccessor<T>) from).ghostpath$allContainers().long2ObjectEntrySet()) {
            expected += e.getValue().getAll().filter(t -> box.isInside(t.pos())).count();
        }
        int before = to.count();
        if (before != 0) {
            throw new IllegalStateException("ghost copy: ghost already has " + before + " " + what + " ticks");
        }
        to.copyAreaFrom(from, box, BlockPos.ZERO);
        int copied = to.count();
        if (copied != expected) {
            // LevelTicks.schedule drops a tick whose chunk container is missing (LevelTicks.java:71-79); ruling R3: fail loudly
            throw new IllegalStateException("ghost copy: " + what + " ticks dropped: " + copied + " of " + expected + " copied");
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
    private static int[] copyEntities(ServerLevel real, GhostLevel ghost, BoundingBox box) {
        List<Entity> roots = new ArrayList<>();
        Set<UUID> seen = new java.util.HashSet<>();
        ((GhostServerLevelAccessor) real).ghostpath$entityTickList().forEach(e -> {
            Entity root = e.getRootVehicle();
            if (!e.isRemoved() && inBox(box, root) && seen.add(root.getUUID())) {
                roots.add(root);
            }
        });
        for (Entity e : real.getAllEntities()) {
            Entity root = e.getRootVehicle();
            if (!e.isRemoved() && inBox(box, root) && seen.add(root.getUUID())) {
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
                    if (!copyTransient(r, g)) {
                        randoms[0]++;
                    }
                }
            });
            copy.getSelfAndPassengers().forEach(ghost::addFreshEntity);
            copied += (int) copy.getSelfAndPassengers().count();
        }
        return new int[] {copied, skipped, randoms[0]};
    }

    private static boolean inBox(BoundingBox box, Entity e) {
        BlockPos p = e.blockPosition();
        return p.getX() >= box.minX() && p.getX() <= box.maxX() && p.getZ() >= box.minZ() && p.getZ() <= box.maxZ();
    }

    /** Entity state outside NBT (Entity.java:221-305 and subclasses). Returns false if the own random could not be copied. */
    private static boolean copyTransient(Entity r, Entity g) {
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
        ga.ghostpath$setPistonDeltasGameTime(ra.ghostpath$pistonDeltasGameTime());
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
