package obsidivision.ghostpath.ghost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelStorageSource;
import obsidivision.ghostpath.mixin.GhostServerLevelAccessor;
import obsidivision.ghostpath.record.SaveGuard;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ghost (DESIGN step 2, option b): a mod-owned {@code ServerLevel} that the server never sees.
 * <ul>
 * <li>Not in {@code MinecraftServer.levels} (MinecraftServer.java:438-478), so the server loop never ticks or saves it;
 * only {@link #tickGhost()} ticks it.</li>
 * <li>Own game time ({@link GhostLevelData}); {@link #tickTime()} advances only that and runs no scheduled functions.</li>
 * <li>Own weather data ({@link #getWeatherData()}; vanilla returns the server's, ServerLevel.java:1691-1692).</li>
 * <li>Own throwaway storage in a temp directory (chunk, entity and data files never touch the world save); never
 * saves ({@link #save}, {@code noSave}, which also stops chunk unloading, ChunkMap.java:448).</li>
 * <li>Empty flat generator (no layers, no structures); every chunk that matters is copied in.</li>
 * <li>Random ticks, spawning, thunder: off (GhostChunkCacheMixin). Weather packets to real players: off (GhostWeatherMixin).</li>
 * <li>Autosave of the real world is held from creation to {@link #discard()} (SaveGuard, as in recordings).</li>
 * </ul>
 * One ghost per copy: {@link GhostCopy} only copies into an empty, fresh ghost.
 */
public final class GhostLevel extends ServerLevel {
    private static final Logger LOGGER = LoggerFactory.getLogger("ghostpath");
    public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("ghostpath", "ghost"));
    /** Real-time limit for waiting on the ghost's own chunk, entity and light work. */
    static final long WAIT_NANOS = 20_000_000_000L;

    private final GhostLevelData ghostData;
    private final Path tempDir;
    private final LevelStorageSource.LevelStorageAccess storage;
    private final SaveGuard saveGuard;
    private @Nullable WeatherData weather;
    private List<ChunkPos> chunks = List.of();
    private boolean flushLight = true;
    private boolean copied;
    private boolean closed;
    private int ticks;

    private GhostLevel(MinecraftServer server, LevelStorageSource.LevelStorageAccess storage, Path tempDir, GhostLevelData data, LevelStem stem, long biomeZoomSeed, SaveGuard guard) {
        super(server, Util.backgroundExecutor(), storage, data, KEY, stem, false, biomeZoomSeed, List.of(), false);
        this.ghostData = data;
        this.tempDir = tempDir;
        this.storage = storage;
        this.saveGuard = guard;
        this.noSave = true;
    }

    /** A new, empty ghost for copies from {@code real} (same dimension type and biome zoom seed). */
    public static GhostLevel create(ServerLevel real) throws IOException {
        MinecraftServer server = real.getServer();
        SaveGuard guard = SaveGuard.begin(server); // throws if a recording (or another ghost) holds saves
        Path dir = null;
        LevelStorageSource.LevelStorageAccess access = null;
        try {
            dir = Files.createTempDirectory("ghostpath-ghost");
            access = LevelStorageSource.createDefault(dir).createAccess("ghost");
            var biomes = real.registryAccess().lookupOrThrow(Registries.BIOME);
            FlatLevelGeneratorSettings settings = new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()), biomes.getOrThrow(Biomes.PLAINS), List.of());
            settings.updateLayers(); // no layers: void
            LevelStem stem = new LevelStem(real.dimensionTypeRegistration(), new FlatLevelSource(settings));
            long zoom = BiomeManager.obfuscateSeed(server.getWorldGenSettings().options().seed()); // as MinecraftServer.java:432
            GhostLevelData data = new GhostLevelData(server.getWorldData(), server.getWorldData().overworldData());
            return new GhostLevel(server, access, dir, data, stem, zoom, guard);
        } catch (IOException | RuntimeException e) {
            guard.end(false);
            if (access != null) {
                access.close();
            }
            if (dir != null) {
                deleteTree(dir);
            }
            throw e;
        }
    }

    // ---- what makes it a ghost ----

    /** Replaces vanilla's (ServerLevel.java:475-482) completely: own counter only, no scheduled functions. */
    @Override
    protected void tickTime() {
        this.ghostData.setGameTime(this.ghostData.getGameTime() + 1L);
    }

    @Override
    public void save(@Nullable ProgressListener progressListener, boolean flush, boolean noSave) {
    }

    @Override
    public WeatherData getWeatherData() {
        // null only while the super constructor runs
        return this.weather != null ? this.weather : super.getWeatherData();
    }

    // ---- used by GhostCopy ----

    GhostLevelData ghostData() {
        return this.ghostData;
    }

    void setWeather(WeatherData weather) {
        this.weather = weather;
    }

    boolean markCopied(List<ChunkPos> chunks) {
        if (this.copied) {
            return false;
        }
        this.copied = true;
        this.chunks = List.copyOf(chunks);
        return true;
    }

    /**
     * Runs the ghost's own pending work (its chunk main-thread tasks, light scheduling, entity-section loads; entity
     * deserialization runs on the server executor, EntityStorage.java:41) until {@code done} or the time limit.
     */
    void pump(BooleanSupplier done, String what) {
        long deadline = System.nanoTime() + WAIT_NANOS;
        this.getServer().managedBlock(() -> {
            this.getChunkSource().pollTask();
            ((GhostServerLevelAccessor) (Object) this).ghostpath$entityManager().tick();
            return done.getAsBoolean() || System.nanoTime() > deadline;
        });
        if (!done.getAsBoolean()) {
            throw new IllegalStateException("ghost: timed out waiting for " + what);
        }
    }

    /** Waits until every light task queued so far for the copied chunks has run (ThreadedLevelLightEngine.java:210). */
    public void flushLight() {
        List<CompletableFuture<?>> pending = new ArrayList<>();
        for (ChunkPos c : this.chunks) {
            pending.add(this.getChunkSource().getLightEngine().waitForPendingTasks(c.x(), c.z()));
        }
        this.pump(() -> pending.stream().allMatch(CompletableFuture::isDone), "light updates");
    }

    // ---- public API ----

    /** The chunks copied in (empty before the copy). */
    public List<ChunkPos> chunks() {
        return this.chunks;
    }

    /** Ghost ticks run since the copy. */
    public int ticksRun() {
        return this.ticks;
    }

    /** Whether {@link #tickGhost()} flushes the light engine after each tick (default on; see DESIGN step 2, light). */
    public void setFlushLight(boolean flush) {
        this.flushLight = flush;
    }

    /**
     * One on-demand tick: {@code emptyTime} reset first (ruling R2; ServerLevel.java:411-420 skips entities and block
     * entities once it reaches 300), then vanilla's {@code tick}, then (by default) the light engine flushed.
     */
    public void tickGhost() {
        if (this.closed || !this.copied) {
            throw new IllegalStateException(this.closed ? "ghost discarded" : "ghost not copied yet");
        }
        this.resetEmptyTime();
        this.tick(() -> true);
        if (this.flushLight) {
            this.flushLight();
        }
        this.ticks++;
    }

    public boolean discarded() {
        return this.closed;
    }

    /** Closes the ghost, deletes its temp storage and ends the autosave hold (doing a held save, if any). */
    public void discard() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            this.close();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[ghostpath] ghost: error while closing", e);
        }
        try {
            this.storage.close();
        } catch (IOException e) {
            LOGGER.warn("[ghostpath] ghost: error while closing its storage", e);
        }
        deleteTree(this.tempDir);
        this.saveGuard.end(true);
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOGGER.warn("[ghostpath] ghost: could not delete {}", p);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[ghostpath] ghost: could not delete {}", dir);
        }
    }

    @Override
    public String toString() {
        return "GhostLevel[" + this.chunks.size() + " chunks, time " + this.getGameTime() + "]";
    }
}
