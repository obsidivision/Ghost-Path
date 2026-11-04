package obsidivision.ghostpath.watch;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.CauseTracker.Column;
import obsidivision.ghostpath.record.Recording;

/**
 * What a watched machine depends on (build step 4): block positions the chain read or wrote, heightmap columns it read,
 * and boxes it asked for entities. A real block change {@linkplain #hit hits} it when the changed position is one of the
 * positions, is in a heightmap column, or is inside a box (a block entering or leaving a box can change what an entity
 * collides with or what a hopper reaches). Immutable; a rerun's new footprint replaces the old one.
 */
public final class Footprint {
    private final Set<BlockPos> positions;
    private final Set<Column> columns;
    private final Set<AABB> areas;
    private final LongOpenHashSet positionKeys = new LongOpenHashSet();
    private final LongOpenHashSet columnKeys = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<List<AABB>> areasByChunk = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet chunks = new LongOpenHashSet();

    public Footprint(Collection<BlockPos> positions, Collection<Column> columns, Collection<AABB> areas) {
        this.positions = Set.copyOf(positions);
        this.columns = Set.copyOf(columns);
        this.areas = Set.copyOf(areas);
        for (BlockPos p : this.positions) {
            this.positionKeys.add(p.asLong());
            this.chunks.add(ChunkPos.pack(p.getX() >> 4, p.getZ() >> 4));
        }
        for (Column c : this.columns) {
            this.columnKeys.add(columnKey(c.x(), c.z()));
            this.chunks.add(ChunkPos.pack(c.x() >> 4, c.z() >> 4));
        }
        for (AABB a : this.areas) {
            int x0 = (int) Math.floor(a.minX) >> 4;
            int x1 = (int) Math.floor(a.maxX) >> 4;
            int z0 = (int) Math.floor(a.minZ) >> 4;
            int z1 = (int) Math.floor(a.maxZ) >> 4;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    long key = ChunkPos.pack(x, z);
                    this.areasByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
                    this.chunks.add(key);
                }
            }
        }
    }

    public static Footprint of(Recording rec) {
        return new Footprint(rec.footprint(), rec.heightmapColumns(), rec.entityAreas());
    }

    public static Footprint of(CauseTracker tracker) {
        return new Footprint(tracker.footprint(), tracker.heightmapColumns(), tracker.entityAreas());
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Block positions read or written. */
    public Set<BlockPos> positions() {
        return this.positions;
    }

    public Set<Column> columns() {
        return this.columns;
    }

    public Set<AABB> areas() {
        return this.areas;
    }

    /** Chunks (packed) that hold any part of the footprint. */
    public LongOpenHashSet chunks() {
        return this.chunks;
    }

    /** Whether {@code pos} is one of the read or written positions. */
    public boolean containsPosition(BlockPos pos) {
        return this.positionKeys.contains(pos.asLong());
    }

    /** Whether a real block change at {@code pos} touches the footprint: a position, a heightmap column, or inside a box. */
    public boolean hit(BlockPos pos) {
        long chunk = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (!this.chunks.contains(chunk)) {
            return false;
        }
        if (this.positionKeys.contains(pos.asLong()) || this.columnKeys.contains(columnKey(pos.getX(), pos.getZ()))) {
            return true;
        }
        List<AABB> boxes = this.areasByChunk.get(chunk);
        if (boxes != null) {
            for (AABB a : boxes) {
                if (a.intersects(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether an entity with this bounding box is "in" the footprint (step 4, ruling 3): its box intersects an entity area, or
     * overlaps a footprint block position (boxes of more than 512 blocks only count by the block at their centre).
     */
    public boolean touchesEntity(AABB bb) {
        int x0 = (int) Math.floor(bb.minX);
        int x1 = (int) Math.floor(bb.maxX);
        int y0 = (int) Math.floor(bb.minY);
        int y1 = (int) Math.floor(bb.maxY);
        int z0 = (int) Math.floor(bb.minZ);
        int z1 = (int) Math.floor(bb.maxZ);
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                List<AABB> boxes = this.areasByChunk.get(ChunkPos.pack(cx, cz));
                if (boxes != null) {
                    for (AABB a : boxes) {
                        if (a.intersects(bb)) {
                            return true;
                        }
                    }
                }
            }
        }
        long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (volume > 512) {
            BlockPos c = BlockPos.containing(bb.getCenter());
            return this.positionKeys.contains(c.asLong());
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (this.positionKeys.contains(m.set(x, y, z).asLong())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return this.positions.size() + " positions, " + this.columns.size() + " columns, " + this.areas.size() + " areas in " + this.chunks.size() + " chunks";
    }
}
