package obsidivision.ghostpath.record;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * What the real world did with the chunks around a machine when it was recorded (session "frozen entities"): for each chunk of a square
 * around the trigger, the class its loading and simulation levels give (DESIGN "Lazy chunks"; {@code ChunkLevel.java:57-63}): entity
 * ticking (both levels 31 or less), block ticking (both 32 or less), loaded (loading level 33 or less), or unloaded. A rerun compares the
 * chunks it copies with this ({@link #changed}): if a player moved or the simulation distance changed, entities that froze in the
 * recording may tick in the rerun or the other way round, and the chat line says "chunk loading changed since recording".
 * Classes, not raw levels: a level that moves within one class (30 to 29) changes nothing a machine can see.
 */
public record LoadingLevels(int centerX, int centerZ, int radius, byte[] classes) {
    /** Chunks around the trigger captured at the start of a recording: a square of (2r+1)^2. */
    public static final int RADIUS = 24;

    public static final byte ENTITY_TICKING = 0;
    public static final byte BLOCK_TICKING = 1;
    public static final byte LOADED = 2;
    public static final byte UNLOADED = 3;

    private static final String[] NAMES = {"entity ticking", "block ticking", "loaded, not ticking", "unloaded"};

    public static String name(byte c) {
        return NAMES[c];
    }

    /** The class of chunk {@code key} in {@code level} now. */
    public static byte classOf(ServerLevel level, long key) {
        DistanceManager d = level.getChunkSource().chunkMap.getDistanceManager();
        int loading = d.getChunkLevel(key, false);
        int simulation = d.getChunkLevel(key, true);
        if (ChunkLevel.isEntityTicking(loading) && ChunkLevel.isEntityTicking(simulation)) {
            return ENTITY_TICKING;
        } else if (ChunkLevel.isBlockTicking(loading) && ChunkLevel.isBlockTicking(simulation)) {
            return BLOCK_TICKING;
        } else if (loading <= ChunkLevel.byStatus(net.minecraft.server.level.FullChunkStatus.FULL)) {
            return LOADED;
        }
        return UNLOADED;
    }

    public static LoadingLevels capture(ServerLevel level, int chunkX, int chunkZ) {
        int side = 2 * RADIUS + 1;
        byte[] out = new byte[side * side];
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                out[(dz + RADIUS) * side + dx + RADIUS] = classOf(level, ChunkPos.pack(chunkX + dx, chunkZ + dz));
            }
        }
        return new LoadingLevels(chunkX, chunkZ, RADIUS, out);
    }

    /** The class at the recording, or -1 outside the captured square. */
    public byte at(int x, int z) {
        int dx = x - this.centerX;
        int dz = z - this.centerZ;
        if (Math.abs(dx) > this.radius || Math.abs(dz) > this.radius) {
            return -1;
        }
        int side = 2 * this.radius + 1;
        return this.classes[(dz + this.radius) * side + dx + this.radius];
    }

    /**
     * The chunks of the rectangle whose class differs now from the recording's, as text ("12 -3: block ticking -> entity ticking"),
     * at most {@code max} of them; chunks outside the captured square are not compared.
     */
    public List<String> changed(ServerLevel level, ChunkPos min, ChunkPos max, int most) {
        List<String> out = new ArrayList<>();
        for (int z = min.z(); z <= max.z(); z++) {
            for (int x = min.x(); x <= max.x(); x++) {
                byte then = this.at(x, z);
                if (then < 0) {
                    continue;
                }
                byte now = classOf(level, ChunkPos.pack(x, z));
                if (now != then) {
                    if (out.size() < most) {
                        out.add("chunk " + x + " " + z + ": " + name(then) + " -> " + name(now));
                    } else {
                        out.add("...");
                        return out;
                    }
                }
            }
        }
        return out;
    }
}
