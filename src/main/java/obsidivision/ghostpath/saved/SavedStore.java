package obsidivision.ghostpath.saved;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import obsidivision.ghostpath.preview.PathSnapshot;
import org.jspecify.annotations.Nullable;

/**
 * The saved paths of one world (session D): files {@code <world>/ghostpath/saved/<name>.nbt} and {@code .../saved/archive/<name>.nbt}
 * (format: {@link SavedCodec}), written atomically (temp file in the same folder, then an atomic rename). A file that cannot be read (unknown
 * version, broken content, name not matching the file) is skipped with a warning and left on disk. Server thread only, except
 * {@link #visible()}, which any thread may read. The shown state lives in the file too.
 */
public final class SavedStore {
    public static final String EXT = ".nbt";
    public static final long CONFIRM_MILLIS = 30_000L;
    private static final LevelResource DIR = new LevelResource("ghostpath/saved");

    /** An outcome with the message for the player. */
    public record Result(boolean ok, String message) {
    }

    private static volatile @Nullable SavedStore current;
    private static volatile List<SavedPath> visible = List.of();

    private final Path dir;
    private final Path archiveDir;
    private final LongSupplier clock;
    private final Map<String, SavedPath> normal = new TreeMap<>();
    private final Map<String, SavedPath> archived = new TreeMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Long> pendingDelete = new HashMap<>();

    /** {@code clock}: milliseconds, for the delete confirmation (a test passes its own). Loads what is on disk. */
    public SavedStore(Path dir, LongSupplier clock) {
        this.dir = dir;
        this.archiveDir = dir.resolve("archive");
        this.clock = clock;
        this.load();
    }

    /** The store of this server's world (loaded on first use, dropped by {@link #close()}). */
    public static SavedStore of(MinecraftServer server) {
        SavedStore c = current;
        Path dir = server.getWorldPath(DIR);
        if (c == null || !c.dir.equals(dir)) {
            c = new SavedStore(dir, System::currentTimeMillis);
            use(c);
        }
        return c;
    }

    /** Makes this the store the client draws from. */
    public static void use(@Nullable SavedStore s) {
        current = s;
        if (s != null) {
            s.publish();
        } else {
            visible = List.of();
        }
    }

    public static void close() {
        use(null);
    }

    /** Any thread: the paths that are shown, immutable. */
    public static List<SavedPath> visible() {
        return visible;
    }

    /** The paths of {@code list} that belong to {@code dimension} (the renderer draws only those). */
    public static List<SavedPath> inDimension(List<SavedPath> list, String dimension) {
        return list.stream().filter(p -> p.dimension().equals(dimension)).toList();
    }

    private void publish() {
        if (current == this) {
            visible = this.normal.values().stream().filter(SavedPath::shown).toList();
        }
    }

    public Path dir() {
        return this.dir;
    }

    public Path file(String name, boolean archive) {
        return (archive ? this.archiveDir : this.dir).resolve(name + EXT);
    }

    /** Warnings from loading (files skipped), kept until the store is dropped. */
    public List<String> warnings() {
        return List.copyOf(this.warnings);
    }

    private void load() {
        this.read(this.dir, this.normal, "saved/");
        this.read(this.archiveDir, this.archived, "saved/archive/");
        // a name in both folders: the normal one counts, the archived one is reported
        for (String n : new ArrayList<>(this.archived.keySet())) {
            if (this.normal.containsKey(n)) {
                this.warnings.add("'" + n + "' exists as a saved and an archived path; the archived one is not listed until one is renamed or deleted");
                this.archived.remove(n);
            }
        }
    }

    private void read(Path folder, Map<String, SavedPath> into, String shownFolder) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(folder)) {
            files = s.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(EXT)).sorted().toList();
        } catch (IOException e) {
            this.warnings.add(shownFolder + " could not be listed: " + e.getMessage());
            return;
        }
        for (Path f : files) {
            String fn = f.getFileName().toString();
            String stem = fn.substring(0, fn.length() - EXT.length());
            try {
                if (!SavedPath.validName(stem)) {
                    throw new IOException("the file name is not a valid path name");
                }
                CompoundTag t = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
                SavedPath p = SavedCodec.fromNbt(t);
                if (!p.name().equals(stem)) {
                    throw new IOException("the name inside (" + p.name() + ") does not match the file name");
                }
                into.put(stem, p);
            } catch (IOException | RuntimeException e) {
                this.warnings.add(shownFolder + fn + " skipped (left on disk): " + e.getMessage());
            }
        }
        this.publish();
    }

    private void write(SavedPath p, boolean archive) throws IOException {
        Path folder = archive ? this.archiveDir : this.dir;
        Files.createDirectories(folder);
        Path target = folder.resolve(p.name() + EXT);
        Path tmp = folder.resolve(p.name() + EXT + ".tmp");
        try {
            NbtIo.writeCompressed(SavedCodec.toNbt(p), tmp);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public List<SavedPath> list(boolean archivedOnes) {
        return List.copyOf((archivedOnes ? this.archived : this.normal).values());
    }

    public @Nullable SavedPath get(String name) {
        return this.normal.get(name);
    }

    public @Nullable SavedPath getArchived(String name) {
        return this.archived.get(name);
    }

    /**
     * Saves the latest prediction under {@code name}. {@code continuations}: the client's continuation points by line index (empty when
     * there is no client). A new path is shown. {@code replace} overwrites a saved path of that name (its shown state stays).
     */
    public Result save(String name, boolean replace, @Nullable PathSnapshot snapshot, Map<Integer, SavedPath.Cont> continuations) {
        if (!SavedPath.validName(name)) {
            return new Result(false, "Bad name '" + name + "': use 1 to " + SavedPath.MAX_NAME + " characters of a-z, 0-9, _ and -.");
        }
        if (snapshot == null) {
            return new Result(false, "No prediction to save: run /path machine first.");
        }
        PathSnapshot.Origin o = snapshot.origin();
        if (o == null) {
            return new Result(false, "This prediction has no trigger information; record the machine again.");
        }
        if (this.archived.containsKey(name)) {
            return new Result(false, "'" + name + "' is an archived path; unarchive or delete it first, or pick another name.");
        }
        SavedPath old = this.normal.get(name);
        if (old != null && !replace) {
            return new Result(false, "'" + name + "' already exists: pick another name, or use /path save " + name + " replace.");
        }
        SavedPath p = new SavedPath(name, snapshot.dimension(), new int[] {o.x(), o.y(), o.z()}, snapshot.ticks(), snapshot.limitHit(), o.gameTime(), Instant.now().toString(),
            old == null || old.shown(), snapshot.source(), snapshot.lines(), Map.copyOf(continuations));
        try {
            this.write(p, false);
        } catch (IOException e) {
            return new Result(false, "Could not write '" + name + "': " + e.getMessage());
        }
        this.normal.put(name, p);
        this.publish();
        return new Result(true, (old == null ? "Saved '" : "Replaced '") + name + "': " + p.lines().size() + " paths, " + p.tnt() + " TNT, " + p.ticks() + " ticks.");
    }

    /** Shows or hides one saved path ({@code name}) or all of them ({@code "all"}, hide only). */
    public Result setShown(String name, boolean shown) {
        if (name.equals("all")) {
            if (shown) {
                return new Result(false, "Show needs a name.");
            }
            int n = 0;
            try {
                for (SavedPath p : List.copyOf(this.normal.values())) {
                    if (p.shown()) {
                        SavedPath h = p.withShown(false);
                        this.write(h, false);
                        this.normal.put(h.name(), h);
                        n++;
                    }
                }
            } catch (IOException e) {
                this.publish();
                return new Result(false, "Could not write: " + e.getMessage());
            }
            this.publish();
            return new Result(true, "Hid " + n + " saved path" + (n == 1 ? "" : "s") + ".");
        }
        SavedPath p = this.normal.get(name);
        if (p == null) {
            return new Result(false, this.archived.containsKey(name) ? "'" + name + "' is archived; unarchive it first." : "No saved path '" + name + "'.");
        }
        SavedPath c = p.withShown(shown);
        try {
            this.write(c, false);
        } catch (IOException e) {
            return new Result(false, "Could not write '" + name + "': " + e.getMessage());
        }
        this.normal.put(name, c);
        this.publish();
        return new Result(true, (shown ? "Showing '" : "Hid '") + name + "'.");
    }

    /** Moves a saved path to the archive folder, hidden. */
    public Result archive(String name) {
        SavedPath p = this.normal.get(name);
        if (p == null) {
            return new Result(false, this.archived.containsKey(name) ? "'" + name + "' is already archived." : "No saved path '" + name + "'.");
        }
        if (this.archived.containsKey(name)) {
            return new Result(false, "An archived path '" + name + "' exists already; delete it or archive under another name.");
        }
        SavedPath h = p.withShown(false);
        try {
            this.write(h, true);
            Files.deleteIfExists(this.file(name, false));
        } catch (IOException e) {
            return new Result(false, "Could not archive '" + name + "': " + e.getMessage());
        }
        this.normal.remove(name);
        this.archived.put(name, h);
        this.pendingDelete.remove(name);
        this.publish();
        return new Result(true, "Archived '" + name + "'.");
    }

    /** Brings an archived path back, hidden. */
    public Result unarchive(String name) {
        SavedPath p = this.archived.get(name);
        if (p == null) {
            return new Result(false, this.normal.containsKey(name) ? "'" + name + "' is not archived." : "No archived path '" + name + "'.");
        }
        if (this.normal.containsKey(name)) {
            return new Result(false, "A saved path '" + name + "' exists already; delete or archive it first.");
        }
        SavedPath h = p.withShown(false);
        try {
            this.write(h, false);
            Files.deleteIfExists(this.file(name, true));
        } catch (IOException e) {
            return new Result(false, "Could not unarchive '" + name + "': " + e.getMessage());
        }
        this.archived.remove(name);
        this.normal.put(name, h);
        this.publish();
        return new Result(true, "Unarchived '" + name + "' (hidden).");
    }

    /**
     * Deletes a saved or archived path. Without {@code confirm} nothing is deleted: it asks, and a second call with {@code confirm} within
     * {@link #CONFIRM_MILLIS} deletes the file.
     */
    public Result delete(String name, boolean confirm) {
        boolean arch = this.archived.containsKey(name);
        if (!this.normal.containsKey(name) && !arch) {
            return new Result(false, "No saved or archived path '" + name + "'.");
        }
        long now = this.clock.getAsLong();
        if (!confirm) {
            this.pendingDelete.put(name, now + CONFIRM_MILLIS);
            return new Result(true, "Delete '" + name + "' for good? Run /path saved delete " + name + " confirm within " + CONFIRM_MILLIS / 1000 + " s.");
        }
        Long until = this.pendingDelete.remove(name);
        if (until == null || now > until) {
            return new Result(false, "Nothing to confirm for '" + name + "'" + (until == null ? "" : " (it expired)") + ": run /path saved delete " + name + " first.");
        }
        try {
            Files.deleteIfExists(this.file(name, arch));
        } catch (IOException e) {
            return new Result(false, "Could not delete '" + name + "': " + e.getMessage());
        }
        (arch ? this.archived : this.normal).remove(name);
        this.publish();
        return new Result(true, "Deleted '" + name + "'.");
    }
}
