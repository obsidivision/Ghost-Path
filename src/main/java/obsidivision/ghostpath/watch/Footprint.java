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
    /** The positions chain work wrote (changed, moved or destroyed): the "machine blocks" the neighbour rule and the margin are measured from. */
    private final Set<BlockPos> writes;
    private final LongOpenHashSet writeKeys = new LongOpenHashSet();
    /** Chunks within one chunk of a written position (the margin is at most {@link #MAX_MARGIN} blocks, so one chunk is enough). */
    private final LongOpenHashSet writeNearChunks = new LongOpenHashSet();
    private final LongOpenHashSet positionKeys = new LongOpenHashSet();
    private final LongOpenHashSet columnKeys = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<List<AABB>> areasByChunk = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet chunks = new LongOpenHashSet();

    /** Largest margin {@code /path watch margin} accepts. */
    public static final int MAX_MARGIN = 8;

    /** Why a block change counts for a watch (see {@link #cause}). */
    public enum Cause {
        /** A position the chain read or wrote, a heightmap column it read, or inside a box it asked for entities. */
        FOOTPRINT,
        /** One of the 6 neighbours of a block the chain wrote. */
        NEIGHBOR,
        /** Within the margin of a block the chain wrote. */
        MARGIN
    }

    public Footprint(Collection<BlockPos> positions, Collection<Column> columns, Collection<AABB> areas) {
        this(positions, columns, areas, List.of());
    }

    public Footprint(Collection<BlockPos> positions, Collection<Column> columns, Collection<AABB> areas, Collection<BlockPos> writes) {
        this.writes = Set.copyOf(writes);
        for (BlockPos w : this.writes) {
            this.writeKeys.add(w.asLong());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    this.writeNearChunks.add(ChunkPos.pack((w.getX() >> 4) + dx, (w.getZ() >> 4) + dz));
                }
            }
        }
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
        return new Footprint(rec.footprint(), rec.heightmapColumns(), rec.entityAreas(), rec.writes());
    }

    public static Footprint of(CauseTracker tracker) {
        return new Footprint(tracker.footprint(), tracker.heightmapColumns(), tracker.entityAreas(), tracker.writes());
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Block positions read or written. */
    public Set<BlockPos> positions() {
        return this.positions;
    }

    /** Positions chain work wrote (changed, moved or destroyed). */
    public Set<BlockPos> writes() {
        return this.writes;
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

    /** Whether {@code pos} is a position chain work wrote. */
    public boolean isWritten(BlockPos pos) {
        return this.writeKeys.contains(pos.asLong());
    }

    /** Whether {@code pos} is one of the 6 neighbours of a position chain work wrote. */
    public boolean nextToWrite(BlockPos pos) {
        if (this.writeKeys.isEmpty() || !this.writeNearChunks.contains(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4))) {
            return false;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (this.writeKeys.contains(m.setWithOffset(pos, d).asLong())) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code pos} is within {@code margin} blocks (each axis, so a cube around it) of a position chain work wrote. */
    public boolean nearWrite(BlockPos pos, int margin) {
        if (margin <= 0 || this.writeKeys.isEmpty() || !this.writeNearChunks.contains(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4))) {
            return false;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -margin; dx <= margin; dx++) {
            for (int dy = -margin; dy <= margin; dy++) {
                for (int dz = -margin; dz <= margin; dz++) {
                    if ((dx != 0 || dy != 0 || dz != 0) && this.writeKeys.contains(m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz).asLong())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Why a real block change at {@code pos} counts, or null if it does not: the footprint ({@link #hit}), the neighbour rule
     * (next to a block the chain wrote), or the margin (within {@code margin} blocks of one; 0 turns the margin off).
     */
    public @org.jspecify.annotations.Nullable Cause cause(BlockPos pos, int margin) {
        if (this.hit(pos)) {
            return Cause.FOOTPRINT;
        }
        if (this.nextToWrite(pos)) {
            return Cause.NEIGHBOR;
        }
        if (this.nearWrite(pos, margin)) {
            return Cause.MARGIN;
        }
        return null;
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
