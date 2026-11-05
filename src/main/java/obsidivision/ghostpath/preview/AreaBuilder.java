package obsidivision.ghostpath.preview;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Builds the {@link AreaData} of the ghost area overlay. The footprint's block positions are merged into as few boxes as a
 * simple greedy pass finds: runs along x, runs of equal rows along z, runs of equal slabs along y. The boxes tile the positions
 * exactly (no overlap, nothing added). Written blocks and read-only blocks are merged separately.
 */
public final class AreaBuilder {
    /** Most machine boxes drawn; the HUD says so when the footprint merged into more. */
    public static final int MAX_BOXES = 2000;
    /** Most boxes of the watched layer. */
    public static final int MAX_WATCHED_BOXES = 1500;
    /** The watched layer is not built above this many blocks (writes times the cube of the margin). */
    public static final long MAX_WATCHED_BLOCKS = 1_500_000L;

    private AreaBuilder() {
    }

    /**
     * @param writes  blocks the chain wrote
     * @param reads   blocks the chain read (those that were also written are drawn as written)
     * @param margin  the watch margin for the watched layer
     * @param copied  copied chunk rectangle (x min, z min, x max, z max) or null; {@code base}: the unwidened rectangle if widened, else null
     */
    public static AreaData build(Collection<BlockPos> writes, Collection<BlockPos> reads, int margin, int @Nullable [] copied, int @Nullable [] base,
                                 AreaData.@Nullable Change change) {
        LongOpenHashSet w = new LongOpenHashSet();
        for (BlockPos p : writes) {
            w.add(p.asLong());
        }
        LongOpenHashSet r = new LongOpenHashSet();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos p : reads) {
            if (!w.contains(p.asLong())) {
                r.add(p.asLong());
            }
        }
        for (long k : w) {
            int y = BlockPos.getY(k);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        for (long k : r) {
            int y = BlockPos.getY(k);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        if (minY == Integer.MAX_VALUE) {
            minY = 0;
            maxY = 0;
        }
        List<AreaData.Box> writeBoxes = merge(w, true);
        List<AreaData.Box> readBoxes = merge(r, false);
        int total = writeBoxes.size() + readBoxes.size();
        List<AreaData.Box> shown = new ArrayList<>(writeBoxes.subList(0, Math.min(writeBoxes.size(), MAX_BOXES)));
        if (shown.size() < MAX_BOXES) {
            readBoxes.sort(Comparator.comparingLong(AreaData.Box::volume).reversed());
            shown.addAll(readBoxes.subList(0, Math.min(readBoxes.size(), MAX_BOXES - shown.size())));
        }
        // the watched layer: a cube of the margin around every written block (the neighbours alone, as a plus, for margin 0)
        List<AreaData.Box> watched = List.of();
        int watchedTotal = 0;
        boolean skipped = false;
        if (!w.isEmpty()) {
            long side = 2L * margin + 1;
            long cost = (long) w.size() * Math.max(7L, side * side * side);
            if (cost > MAX_WATCHED_BLOCKS) {
                skipped = true;
            } else {
                LongOpenHashSet area = new LongOpenHashSet();
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                for (long k : w) {
                    int x = BlockPos.getX(k);
                    int y = BlockPos.getY(k);
                    int z = BlockPos.getZ(k);
                    if (margin == 0) {
                        area.add(k);
                        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                            area.add(m.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()).asLong());
                        }
                    } else {
                        for (int dx = -margin; dx <= margin; dx++) {
                            for (int dy = -margin; dy <= margin; dy++) {
                                for (int dz = -margin; dz <= margin; dz++) {
                                    area.add(m.set(x + dx, y + dy, z + dz).asLong());
                                }
                            }
                        }
                    }
                }
                List<AreaData.Box> all = merge(area, false);
                watchedTotal = all.size();
                all.sort(Comparator.comparingLong(AreaData.Box::volume).reversed());
                watched = List.copyOf(all.subList(0, Math.min(all.size(), MAX_WATCHED_BOXES)));
            }
        }
        return new AreaData(List.copyOf(shown), total, w.size(), r.size(), watched, watchedTotal, skipped, margin,
            copied == null ? null : copied.clone(), base == null ? null : base.clone(), minY, maxY, change);
    }

    /**
     * Greedy merge of block positions (packed {@link BlockPos#asLong}) into boxes that tile them exactly: runs along x per (y, z), then
     * equal runs on consecutive z per y, then equal slabs on consecutive y.
     */
    public static List<AreaData.Box> merge(LongOpenHashSet positions, boolean write) {
        // sort by y, z, x
        long[] sorted = positions.toLongArray();
        long[][] keyed = new long[sorted.length][];
        for (int i = 0; i < sorted.length; i++) {
            keyed[i] = new long[] {BlockPos.getY(sorted[i]), BlockPos.getZ(sorted[i]), BlockPos.getX(sorted[i])};
        }
        java.util.Arrays.sort(keyed, (a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[2], b[2]));
        // rows: (y, z, x0, x1)
        List<int[]> rows = new ArrayList<>();
        int i = 0;
        while (i < keyed.length) {
            int y = (int) keyed[i][0];
            int z = (int) keyed[i][1];
            int x0 = (int) keyed[i][2];
            int x1 = x0 + 1;
            i++;
            while (i < keyed.length && keyed[i][0] == y && keyed[i][1] == z && keyed[i][2] == x1) {
                x1++;
                i++;
            }
            rows.add(new int[] {y, z, x0, x1});
        }
        // slabs: rows with the same (y, x0, x1) on consecutive z; sort by y, x0, x1, z
        rows.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : a[2] != b[2] ? Integer.compare(a[2], b[2]) : a[3] != b[3] ? Integer.compare(a[3], b[3]) : Integer.compare(a[1], b[1]));
        List<int[]> slabs = new ArrayList<>(); // (y, x0, x1, z0, z1)
        int k = 0;
        while (k < rows.size()) {
            int[] row = rows.get(k);
            int z1 = row[1] + 1;
            k++;
            while (k < rows.size() && rows.get(k)[0] == row[0] && rows.get(k)[2] == row[2] && rows.get(k)[3] == row[3] && rows.get(k)[1] == z1) {
                z1++;
                k++;
            }
            slabs.add(new int[] {row[0], row[2], row[3], row[1], z1});
        }
        // boxes: slabs with the same (x0, x1, z0, z1) on consecutive y
        slabs.sort((a, b) -> {
            for (int c : new int[] {1, 2, 3, 4, 0}) {
                if (a[c] != b[c]) {
                    return Integer.compare(a[c], b[c]);
                }
            }
            return 0;
        });
        List<AreaData.Box> out = new ArrayList<>();
        int s = 0;
        while (s < slabs.size()) {
            int[] slab = slabs.get(s);
            int y1 = slab[0] + 1;
            s++;
            while (s < slabs.size() && slabs.get(s)[1] == slab[1] && slabs.get(s)[2] == slab[2] && slabs.get(s)[3] == slab[3] && slabs.get(s)[4] == slab[4] && slabs.get(s)[0] == y1) {
                y1++;
                s++;
            }
            out.add(new AreaData.Box(slab[1], slab[0], slab[3], slab[2], y1, slab[4], write));
        }
        return out;
    }
}
