package obsidivision.ghostpath.preview;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * What the ghost area overlay draws (pure data, part of a {@link PathSnapshot}): the machine's footprint merged into boxes (written
 * blocks apart from read ones), the watched margin, the copied chunk rectangle, and the block change that started the last rerun.
 * Immutable; built on the server thread from the same run as the paths.
 *
 * @param boxes        the machine boxes that are drawn: written blocks first, then read ones (largest first); at most {@link AreaBuilder#MAX_BOXES}
 * @param totalBoxes   how many boxes the footprint merged into before the cap
 * @param writeBlocks  written positions; {@code readBlocks}: positions only read
 * @param watched      the watched area (margin around the written blocks) as boxes, empty when the margin layer is empty or too big to build
 * @param watchedTotal boxes before the cap; {@code watchedSkipped}: the layer was not built (too many blocks)
 * @param margin       the margin the watched layer was built with
 * @param copied       the chunk rectangle the ghost copied (chunk x min, z min, x max, z max), or null (a recording nobody copied)
 * @param base         the machine's own first rectangle when a widened rerun copied more (the rest is "widened"), else null
 * @param minY         lowest and highest block y of the footprint (the chunk layer is drawn between them)
 * @param change       the real block change that started the rerun this snapshot comes from; null for a recording
 */
public record AreaData(List<Box> boxes, int totalBoxes, int writeBlocks, int readBlocks, List<Box> watched, int watchedTotal, boolean watchedSkipped, int margin,
                       int @Nullable [] copied, int @Nullable [] base, int minY, int maxY, @Nullable Change change) {

    /** A box of whole blocks: {@code x0..x1} etc. are block coordinates, the maximum exclusive. */
    public record Box(int x0, int y0, int z0, int x1, int y1, int z1, boolean write) {
        public long volume() {
            return (long) (this.x1 - this.x0) * (this.y1 - this.y0) * (this.z1 - this.z0);
        }
    }

    /**
     * The block change that started a rerun: where (null for {@code /path rerun}), a short label ("piston placed"), and the wall-clock
     * millisecond the snapshot was published (the client highlights it for a few seconds from then).
     */
    public record Change(@Nullable BlockPos pos, String label, long millis) {
    }

    /** One chunk of the copied rectangle; {@code widened}: the machine's own first area did not include it. */
    public record ChunkCell(int x, int z, boolean widened) {
    }

    public boolean capped() {
        return this.boxes.size() < this.totalBoxes;
    }

    public boolean watchedCapped() {
        return this.watched.size() < this.watchedTotal;
    }

    /** Every chunk of the copied rectangle, with the widened ones marked (empty without a rectangle). */
    public List<ChunkCell> chunkCells() {
        List<ChunkCell> out = new ArrayList<>();
        if (this.copied == null) {
            return out;
        }
        for (int x = this.copied[0]; x <= this.copied[2]; x++) {
            for (int z = this.copied[1]; z <= this.copied[3]; z++) {
                boolean inBase = this.base == null || x >= this.base[0] && x <= this.base[2] && z >= this.base[1] && z <= this.base[3];
                out.add(new ChunkCell(x, z, !inBase));
            }
        }
        return out;
    }

    public int widenedChunks() {
        return (int) this.chunkCells().stream().filter(ChunkCell::widened).count();
    }

    /** Blocks covered by the machine boxes that are drawn. */
    public long drawnVolume() {
        return this.boxes.stream().mapToLong(Box::volume).sum();
    }
}
