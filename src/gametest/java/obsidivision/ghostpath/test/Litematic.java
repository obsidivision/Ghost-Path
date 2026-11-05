package obsidivision.ghostpath.test;

import com.mojang.serialization.Dynamic;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Test-only loader for Litematica's {@code .litematic} files (gzipped NBT): {@code MinecraftDataVersion}, regions with a block
 * state palette and bit-packed states (litematica packs tightly across longs, {@code max(2, ceil(log2(palette)))} bits per block,
 * x fastest, then z, then y), tile entities and entities. A file saved by an older game is run through the 26.2 DataFixer
 * ({@code References.BLOCK_STATE}, {@code BLOCK_ENTITY}, {@code ENTITY}) from its own data version; everything the fixer changed is
 * listed in {@link #fixerChanges}.
 *
 * <p>A region whose size is negative on an axis is stored from its position towards smaller coordinates; the block indices still run
 * from the region's lowest corner (nothing is mirrored), so the lowest corner is {@code position + min(0, size + 1)}.
 */
final class Litematic {
    record Block(BlockPos pos, BlockState state) {
    }

    final int version;
    final int dataVersion;
    final int currentDataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
    final String author;
    /** Blocks relative to the lowest corner of the enclosing box (all regions), air included. */
    final List<Block> blocks = new ArrayList<>();
    final List<CompoundTag> tileEntities = new ArrayList<>();
    final List<CompoundTag> entities = new ArrayList<>();
    /** Palette entries and tile entities the DataFixer changed: "before -> after". */
    final List<String> fixerChanges = new ArrayList<>();
    int sizeX;
    int sizeY;
    int sizeZ;

    private Litematic(CompoundTag root) {
        this.version = root.getIntOr("Version", -1);
        this.dataVersion = root.getIntOr("MinecraftDataVersion", -1);
        this.author = root.getCompoundOrEmpty("Metadata").getString("Author").orElse("?");
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        // the enclosing box: lowest corner over all regions
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (String name : regions.keySet()) {
            CompoundTag r = regions.getCompoundOrEmpty(name);
            int[] lo = lowest(r);
            int[] size = abs(r);
            minX = Math.min(minX, lo[0]);
            minY = Math.min(minY, lo[1]);
            minZ = Math.min(minZ, lo[2]);
            maxX = Math.max(maxX, lo[0] + size[0]);
            maxY = Math.max(maxY, lo[1] + size[1]);
            maxZ = Math.max(maxZ, lo[2] + size[2]);
        }
        this.sizeX = maxX - minX;
        this.sizeY = maxY - minY;
        this.sizeZ = maxZ - minZ;
        for (String name : regions.keySet()) {
            CompoundTag r = regions.getCompoundOrEmpty(name);
            int[] lo = lowest(r);
            int[] size = abs(r);
            int dx = lo[0] - minX;
            int dy = lo[1] - minY;
            int dz = lo[2] - minZ;
            List<BlockState> palette = new ArrayList<>();
            for (Tag t : r.getListOrEmpty("BlockStatePalette")) {
                palette.add(this.fix((CompoundTag) t));
            }
            long[] states = r.getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            long total = (long) size[0] * size[1] * size[2];
            for (long i = 0; i < total; i++) {
                int x = (int) (i % size[0]);
                int z = (int) (i / size[0] % size[2]);
                int y = (int) (i / ((long) size[0] * size[2]));
                int index = (int) read(states, i, bits);
                this.blocks.add(new Block(new BlockPos(dx + x, dy + y, dz + z), palette.get(index)));
            }
            for (Tag t : r.getListOrEmpty("TileEntities")) {
                CompoundTag te = (CompoundTag) t;
                CompoundTag fixed = this.fixTag(References.BLOCK_ENTITY, te, "tile entity " + te.getString("id").orElse("?"));
                fixed.putInt("x", dx + te.getIntOr("x", 0));
                fixed.putInt("y", dy + te.getIntOr("y", 0));
                fixed.putInt("z", dz + te.getIntOr("z", 0));
                this.tileEntities.add(fixed);
            }
            for (Tag t : r.getListOrEmpty("Entities")) {
                this.entities.add(this.fixTag(References.ENTITY, (CompoundTag) t, "entity"));
            }
        }
    }

    static Litematic read(InputStream in) throws IOException {
        return new Litematic(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()));
    }

    private static int[] size3(CompoundTag r, String key) {
        CompoundTag c = r.getCompoundOrEmpty(key);
        return new int[] {c.getIntOr("x", 0), c.getIntOr("y", 0), c.getIntOr("z", 0)};
    }

    private static int[] abs(CompoundTag r) {
        int[] s = size3(r, "Size");
        return new int[] {Math.abs(s[0]), Math.abs(s[1]), Math.abs(s[2])};
    }

    private static int[] lowest(CompoundTag r) {
        int[] p = size3(r, "Position");
        int[] s = size3(r, "Size");
        return new int[] {p[0] + Math.min(0, s[0] + 1), p[1] + Math.min(0, s[1] + 1), p[2] + Math.min(0, s[2] + 1)};
    }

    /** {@code bits} bits at bit offset {@code i * bits}, packed across longs. */
    static long read(long[] data, long i, int bits) {
        long start = i * bits;
        int word = (int) (start >>> 6);
        int offset = (int) (start & 63);
        long v = data[word] >>> offset;
        if (offset + bits > 64) {
            v |= data[word + 1] << (64 - offset);
        }
        return v & ((1L << bits) - 1);
    }

    private BlockState fix(CompoundTag entry) {
        CompoundTag fixed = entry;
        if (this.dataVersion < this.currentDataVersion) {
            fixed = (CompoundTag) DataFixers.getDataFixer().update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, entry.copy()), this.dataVersion, this.currentDataVersion).getValue();
            if (!fixed.equals(entry)) {
                this.fixerChanges.add("block state " + entry + " -> " + fixed);
            }
        }
        BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, fixed);
        if (!fixed.getString("Name").orElse("").equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
            this.fixerChanges.add("UNKNOWN block " + fixed.getString("Name").orElse("?") + " became " + state);
        }
        return state;
    }

    private CompoundTag fixTag(com.mojang.datafixers.DSL.TypeReference type, CompoundTag tag, String what) {
        if (this.dataVersion >= this.currentDataVersion) {
            return tag.copy();
        }
        CompoundTag fixed = (CompoundTag) DataFixers.getDataFixer().update(type, new Dynamic<>(NbtOps.INSTANCE, tag.copy()), this.dataVersion, this.currentDataVersion).getValue();
        if (!fixed.equals(tag)) {
            this.fixerChanges.add(what + " " + tag + " -> " + fixed);
        }
        return fixed;
    }
}
