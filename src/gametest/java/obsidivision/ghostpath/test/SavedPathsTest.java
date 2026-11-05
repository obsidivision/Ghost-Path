package obsidivision.ghostpath.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;
import obsidivision.ghostpath.saved.SavedCodec;
import obsidivision.ghostpath.saved.SavedPath;
import obsidivision.ghostpath.saved.SavedStore;

/** Session D, item 1 and 2: saved paths (file format, store, archive, delete confirmation). No client needed. */
public class SavedPathsTest {
    private static Path tmp() {
        try {
            return Files.createTempDirectory("ghostpath-saved");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** A snapshot with awkward doubles, a count, a freeze, a stasis and a tail, so the round trip has something to lose. */
    static PathSnapshot snapshot() {
        double[] xyz = {0.1, -0.0, 1.0 / 3.0, Math.nextUp(5.0), 2.5e-300, -7.75, Double.MIN_VALUE, 1.0E22, Math.PI};
        double[] vel = {0.0, -0.04, 0.0, 0.01, -0.0, 0.2, Math.E, 0.0, -1.0 / 7.0};
        double[] box = new double[18];
        for (int i = 0; i < box.length; i++) {
            box[i] = Math.sqrt(i + 2);
        }
        CompoundTag nbt = new CompoundTag();
        nbt.putString("note", "tail");
        nbt.putInt("Fuse", 12);
        PathSnapshot.Line tnt = new PathSnapshot.Line("minecraft:tnt", FlightKind.TNT, 10, 4L, new long[] {4, 5, 6}, xyz, vel, box, new int[] {80, 79, 78}, new int[] {0, 3, 5, 6, 7, 8, 9, 11, 12, 13},
            new PathSnapshot.End("exploded", 6, xyz[6], xyz[7], xyz[8]), null, new PathSnapshot.Freeze(1, 5, "lazy", 1.5, 2.5, 3.5),
            List.of(new PathSnapshot.Stasis(1, 2, 5, 5, "lazy chunk", true, new long[] {5}, new double[] {0.1, 0.2, 0.3}, List.of("explosion #1 t+5"))));
        PathSnapshot.Line arrow = new PathSnapshot.Line("minecraft:arrow", FlightKind.PROJECTILE, 1, 3L, new long[] {3, 4, 5}, xyz.clone(), vel.clone(), box.clone(), new int[] {-1, -1, -1}, new int[] {2},
            new PathSnapshot.End("limit", 5, 0.0, 0.0, 0.0), new PathSnapshot.Tail("minecraft:arrow", nbt, 17, 0.25, 0.5, 0.75, true), null);
        return new PathSnapshot(7, "rerun", "minecraft:the_nether", 125, true, List.of(tnt, arrow)).withOrigin(new PathSnapshot.Origin(-12, 64, 3, 987654321L));
    }

    static Map<Integer, SavedPath.Cont> conts() {
        return Map.of(1, new SavedPath.Cont(new long[] {6, 7}, new double[] {1.1, 2.2, 3.3, 4.4, 5.5, 6.6}, new double[] {0.1, 0.2, 0.3, -0.0, 0.5, 0.6}, new double[12], "hit block", true));
    }

    private static void sameLine(GameTestHelper h, PathSnapshot.Line a, PathSnapshot.Line b) {
        h.assertTrue(a.type().equals(b.type()) && a.kind() == b.kind() && a.count() == b.count() && a.startTick() == b.startTick(), "line header differs");
        h.assertTrue(Arrays.equals(a.ticks(), b.ticks()) && Arrays.equals(a.xyz(), b.xyz()) && Arrays.equals(a.vel(), b.vel()) && Arrays.equals(a.box(), b.box())
            && Arrays.equals(a.fuse(), b.fuse()) && Arrays.equals(a.members(), b.members()), "arrays differ");
        h.assertTrue(a.end().equals(b.end()), "end differs: " + a.end() + " vs " + b.end());
        h.assertTrue(java.util.Objects.equals(a.freeze(), b.freeze()), "freeze differs");
        h.assertTrue((a.tail() == null) == (b.tail() == null) && (a.tail() == null || (a.tail().nbt().equals(b.tail().nbt()) && a.tail().tickCount() == b.tail().tickCount()
            && a.tail().xo() == b.tail().xo() && a.tail().yo() == b.tail().yo() && a.tail().zo() == b.tail().zo() && a.tail().onGround() == b.tail().onGround()
            && a.tail().type().equals(b.tail().type()))), "tail differs");
        h.assertTrue(a.stasis().size() == b.stasis().size(), "stasis count");
        for (int i = 0; i < a.stasis().size(); i++) {
            h.assertTrue(a.stasis().get(i).signature().equals(b.stasis().get(i).signature()), "stasis differs");
        }
    }

    /** Every double comes back bit for bit (also -0.0 and the smallest values), ×N, end reasons, freeze, stasis, tail, continuation, metadata. */
    @GameTest
    public void roundTripIsBitIdentical(GameTestHelper helper) throws IOException {
        Path dir = tmp();
        PathSnapshot snap = snapshot();
        SavedStore st = new SavedStore(dir, System::currentTimeMillis);
        SavedStore.Result r = st.save("duper-1", false, snap, conts());
        helper.assertTrue(r.ok(), r.message());
        SavedStore again = new SavedStore(dir, System::currentTimeMillis); // read back from the file
        SavedPath p = again.get("duper-1");
        helper.assertTrue(p != null && again.warnings().isEmpty(), "not read back: " + again.warnings());
        helper.assertTrue(p.dimension().equals("minecraft:the_nether") && Arrays.equals(p.trigger(), new int[] {-12, 64, 3}) && p.ticks() == 125 && p.limitHit() && p.gameTime() == 987654321L
            && p.shown() && p.tnt() == 10 && !p.date().isEmpty(), "metadata differs: " + p);
        helper.assertTrue(p.lines().size() == 2, "lines");
        for (int i = 0; i < 2; i++) {
            sameLine(helper, snap.lines().get(i), p.lines().get(i));
        }
        SavedPath.Cont c = p.continuations().get(1);
        SavedPath.Cont want = conts().get(1);
        helper.assertTrue(c != null && p.continuations().size() == 1 && Arrays.equals(c.ticks(), want.ticks()) && Arrays.equals(c.xyz(), want.xyz()) && Arrays.equals(c.vel(), want.vel())
            && Arrays.equals(c.box(), want.box()) && c.endReason().equals("hit block") && c.done(), "continuation differs");
        // raw bits, not only ==: -0.0 stays -0.0
        helper.assertTrue(Double.doubleToRawLongBits(p.lines().getFirst().xyz()[1]) == Double.doubleToRawLongBits(-0.0), "-0.0 lost");
        helper.succeed();
    }

    @GameTest
    public void namesAndReplace(GameTestHelper helper) {
        SavedStore st = new SavedStore(tmp(), System::currentTimeMillis);
        PathSnapshot snap = snapshot();
        for (String bad : new String[] {"", "A", "a b", "../x", "a/b", "a.b", "x".repeat(33), "é", "a\\b", "a:b", "CON "}) {
            SavedStore.Result r = st.save(bad, false, snap, Map.of());
            helper.assertTrue(!r.ok() && st.list(false).isEmpty(), "name '" + bad + "' was accepted");
        }
        helper.assertTrue(st.save("x".repeat(32), false, snap, Map.of()).ok() && st.save("a_b-9", false, snap, Map.of()).ok(), "valid names refused");
        SavedStore.Result none = st.save("fresh", false, null, Map.of());
        helper.assertTrue(!none.ok() && none.message().contains("No prediction"), "missing prediction: " + none);
        helper.assertTrue(!st.save("a_b-9", false, snap, Map.of()).ok(), "a taken name was overwritten without replace");
        helper.assertTrue(st.setShown("a_b-9", false).ok(), "hide");
        helper.assertTrue(st.save("a_b-9", true, snap.withOrigin(new PathSnapshot.Origin(1, 2, 3, 4)), Map.of()).ok(), "replace");
        SavedPath p = st.get("a_b-9");
        helper.assertTrue(p.trigger()[0] == 1 && !p.shown(), "replace did not overwrite, or lost the hidden state: " + p.triggerText() + " shown " + p.shown());
        helper.succeed();
    }

    @GameTest
    public void shownStateSurvivesRejoin(GameTestHelper helper) {
        Path dir = tmp();
        SavedStore st = new SavedStore(dir, System::currentTimeMillis);
        PathSnapshot snap = snapshot();
        st.save("a", false, snap, Map.of());
        st.save("b", false, snap, Map.of());
        st.save("c", false, snap, Map.of());
        st.setShown("a", false);
        st.setShown("c", false);
        st.setShown("c", true);
        SavedStore again = new SavedStore(dir, System::currentTimeMillis);
        helper.assertTrue(!again.get("a").shown() && again.get("b").shown() && again.get("c").shown(), "shown state changed over a reload");
        helper.assertTrue(st.setShown("all", false).ok(), "hide all");
        SavedStore third = new SavedStore(dir, System::currentTimeMillis);
        helper.assertTrue(third.list(false).stream().noneMatch(SavedPath::shown), "hide all not saved");
        helper.assertTrue(!st.setShown("nope", true).ok(), "unknown name");
        // only the ones in the dimension of the level are drawn
        SavedStore.Result r = st.save("ow", false, new PathSnapshot(1, "recorded", "minecraft:overworld", 3, false, snap.lines()).withOrigin(snap.origin()), Map.of());
        List<SavedPath> all = st.list(false);
        helper.assertTrue(r.ok() && SavedStore.inDimension(all, "minecraft:overworld").size() == 1 && SavedStore.inDimension(all, "minecraft:the_nether").size() == 3
            && SavedStore.inDimension(all, "minecraft:the_end").isEmpty(), "dimension filter");
        helper.succeed();
    }

    @GameTest
    public void unknownVersionAndBrokenFilesAreSkippedAndKept(GameTestHelper helper) throws IOException {
        Path dir = tmp();
        SavedStore st = new SavedStore(dir, System::currentTimeMillis);
        st.save("good", false, snapshot(), Map.of());
        // unknown version
        CompoundTag t = SavedCodec.toNbt(st.get("good"));
        t.putInt("version", 99);
        NbtIo.writeCompressed(t, dir.resolve("future.nbt"));
        t.putInt("version", 1);
        t.putString("name", "other");
        NbtIo.writeCompressed(t, dir.resolve("mismatch.nbt")); // name inside differs from the file name
        // broken content: not NBT at all, truncated, and a field missing
        Files.writeString(dir.resolve("garbage.nbt"), "this is not nbt");
        byte[] whole = Files.readAllBytes(dir.resolve("good.nbt"));
        Files.write(dir.resolve("cut.nbt"), Arrays.copyOf(whole, whole.length / 2));
        CompoundTag missing = SavedCodec.toNbt(st.get("good"));
        missing.remove("lines");
        NbtIo.writeCompressed(missing, dir.resolve("nolines.nbt"));
        Files.writeString(dir.resolve("Bad Name.nbt"), "x");
        Files.writeString(dir.resolve("half.nbt.tmp"), "x"); // a leftover temp file is not a path
        Map<String, byte[]> before = new java.util.HashMap<>();
        for (String f : List.of("future", "mismatch", "garbage", "cut", "nolines")) {
            before.put(f, Files.readAllBytes(dir.resolve(f + ".nbt")));
        }
        SavedStore again = new SavedStore(dir, System::currentTimeMillis);
        helper.assertTrue(again.list(false).size() == 1 && again.get("good") != null, "bad files were listed: " + again.list(false).stream().map(SavedPath::name).toList());
        helper.assertTrue(again.warnings().size() == 6, "warnings: " + again.warnings());
        helper.assertTrue(again.warnings().stream().anyMatch(w -> w.contains("future.nbt") && w.contains("version 99")), "version warning: " + again.warnings());
        for (Map.Entry<String, byte[]> e : before.entrySet()) {
            helper.assertTrue(Arrays.equals(Files.readAllBytes(dir.resolve(e.getKey() + ".nbt")), e.getValue()), e.getKey() + " was changed or deleted");
        }
        helper.assertTrue(Files.exists(dir.resolve("Bad Name.nbt")) && Files.exists(dir.resolve("half.nbt.tmp")), "files were deleted");
        helper.succeed();
    }

    @GameTest
    public void archiveUnarchiveAndLists(GameTestHelper helper) {
        Path dir = tmp();
        SavedStore st = new SavedStore(dir, System::currentTimeMillis);
        PathSnapshot snap = snapshot();
        st.save("one", false, snap, Map.of());
        st.save("two", false, snap, Map.of());
        SavedStore.Result r = st.archive("one");
        helper.assertTrue(r.ok() && st.get("one") == null && st.getArchived("one") != null && !st.getArchived("one").shown(), "archive: " + r.message());
        helper.assertTrue(Files.exists(dir.resolve("archive").resolve("one.nbt")) && !Files.exists(dir.resolve("one.nbt")), "files after archive");
        helper.assertTrue(st.list(false).stream().map(SavedPath::name).toList().equals(List.of("two")) && st.list(true).stream().map(SavedPath::name).toList().equals(List.of("one")), "lists");
        // clashes are refused
        helper.assertTrue(!st.save("one", false, snap, Map.of()).ok() && !st.save("one", true, snap, Map.of()).ok(), "saved over an archived name");
        helper.assertTrue(!st.setShown("one", true).ok(), "an archived path was shown");
        st.save("one2", false, snap, Map.of());
        helper.assertTrue(!st.archive("nope").ok() && !st.unarchive("two").ok() && !st.unarchive("nope").ok(), "bad archive/unarchive accepted");
        // a name in both folders (made by hand): unarchive and archive refuse, nothing is lost
        try {
            Files.copy(dir.resolve("archive").resolve("one.nbt"), dir.resolve("one.nbt"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        SavedStore clash = new SavedStore(dir, System::currentTimeMillis);
        helper.assertTrue(clash.get("one") != null && clash.getArchived("one") == null && !clash.warnings().isEmpty(), "clash on load: " + clash.warnings());
        try {
            Files.delete(dir.resolve("one.nbt"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        r = st.unarchive("one");
        helper.assertTrue(r.ok() && st.get("one") != null && !st.get("one").shown() && st.getArchived("one") == null && Files.exists(dir.resolve("one.nbt"))
            && !Files.exists(dir.resolve("archive").resolve("one.nbt")), "unarchive: " + r.message());
        helper.succeed();
    }

    @GameTest
    public void deleteNeedsConfirmationWithin30Seconds(GameTestHelper helper) {
        Path dir = tmp();
        AtomicLong now = new AtomicLong(1_000_000L);
        SavedStore st = new SavedStore(dir, now::get);
        st.save("gone", false, snapshot(), Map.of());
        st.save("old", false, snapshot(), Map.of());
        st.archive("old");
        // confirm without asking, asking alone: nothing happens
        helper.assertTrue(!st.delete("gone", true).ok() && Files.exists(dir.resolve("gone.nbt")), "confirm without a question deleted");
        helper.assertTrue(st.delete("gone", false).ok() && Files.exists(dir.resolve("gone.nbt")) && st.get("gone") != null, "the question deleted");
        // confirm too late
        now.addAndGet(SavedStore.CONFIRM_MILLIS + 1);
        SavedStore.Result late = st.delete("gone", true);
        helper.assertTrue(!late.ok() && late.message().contains("expired") && Files.exists(dir.resolve("gone.nbt")), "a late confirm deleted: " + late.message());
        helper.assertTrue(!st.delete("gone", true).ok(), "the expired question stayed open");
        // in time: normal and archived
        st.delete("gone", false);
        now.addAndGet(SavedStore.CONFIRM_MILLIS - 1);
        helper.assertTrue(st.delete("gone", true).ok() && !Files.exists(dir.resolve("gone.nbt")) && st.get("gone") == null, "confirm in time");
        st.delete("old", false);
        helper.assertTrue(st.delete("old", true).ok() && !Files.exists(dir.resolve("archive").resolve("old.nbt")) && st.getArchived("old") == null, "delete of an archived path");
        helper.assertTrue(!st.delete("nope", false).ok(), "delete of nothing");
        // a question for one name does not confirm another
        st.save("p", false, snapshot(), Map.of());
        st.save("q", false, snapshot(), Map.of());
        st.delete("p", false);
        helper.assertTrue(!st.delete("q", true).ok() && Files.exists(dir.resolve("q.nbt")), "confirm of another name");
        helper.succeed();
    }

    /** The files are written through a temp file and renamed: after a save there is no temp file, and the format is versioned. */
    @GameTest
    public void writesAtomicallyWithAVersion(GameTestHelper helper) throws IOException {
        Path dir = tmp();
        SavedStore st = new SavedStore(dir, System::currentTimeMillis);
        st.save("v", false, snapshot(), Map.of());
        try (var files = Files.list(dir)) {
            helper.assertTrue(files.map(f -> f.getFileName().toString()).sorted().toList().equals(List.of("v.nbt")), "leftover files");
        }
        CompoundTag t = NbtIo.readCompressed(dir.resolve("v.nbt"), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        helper.assertTrue(t.getInt("version").orElse(-1) == SavedPath.VERSION, "no version in the file");
        helper.succeed();
    }

    /** The commands, on the world's own folder (the files are removed at the end). */
    @GameTest
    public void commands(GameTestHelper helper) throws IOException {
        var level = helper.getLevel();
        SavedStore store = SavedStore.of(level.getServer());
        String a = "cmdtest-a", b = "cmdtest-b";
        LiveRerunTest.Capture out = new LiveRerunTest.Capture();
        obsidivision.ghostpath.preview.PathStore.clear();
        LiveRerunTest.command(level, out, "path save " + a);
        helper.assertTrue(out.has("No prediction") && store.get(a) == null, "save without a prediction: " + out.lines);
        obsidivision.ghostpath.preview.PathStore.publish(serial -> snapshot());
        out.lines.clear();
        LiveRerunTest.command(level, out, "path save " + a);
        helper.assertTrue(store.get(a) != null && Files.exists(store.file(a, false)) && out.has("Saved"), "save: " + out.lines);
        out.lines.clear();
        LiveRerunTest.command(level, out, "path save " + a);
        helper.assertTrue(out.has("already exists"), "taken: " + out.lines);
        out.lines.clear();
        LiveRerunTest.command(level, out, "path save " + a + " replace");
        helper.assertTrue(out.has("Replaced"), "replace: " + out.lines);
        out.lines.clear();
        LiveRerunTest.command(level, out, "path save Bad.Name");
        helper.assertTrue(out.has("Bad name") && !Files.exists(store.dir().resolve("Bad.Name.nbt")), "bad name: " + out.lines);
        LiveRerunTest.command(level, out, "path saved hide " + a);
        helper.assertTrue(!store.get(a).shown(), "hide");
        out.lines.clear();
        LiveRerunTest.command(level, out, "path saved list");
        helper.assertTrue(out.lines.stream().anyMatch(l -> l.startsWith(a + ": minecraft:the_nether -12 64 3, 10 TNT, ") && l.endsWith(", hidden")), "list: " + out.lines);
        LiveRerunTest.command(level, out, "path saved show " + a);
        LiveRerunTest.command(level, out, "path saved archive " + a);
        helper.assertTrue(store.getArchived(a) != null && SavedStore.visible().stream().noneMatch(p -> p.name().equals(a)), "archive");
        out.lines.clear();
        LiveRerunTest.command(level, out, "path saved list archived");
        helper.assertTrue(out.lines.stream().anyMatch(l -> l.startsWith(a + ":") && l.endsWith(", archived")), "list archived: " + out.lines);
        LiveRerunTest.command(level, out, "path saved unarchive " + a);
        LiveRerunTest.command(level, out, "path saved delete " + a);
        helper.assertTrue(store.get(a) != null, "delete without confirm");
        LiveRerunTest.command(level, out, "path saved delete " + a + " confirm");
        helper.assertTrue(store.get(a) == null && !Files.exists(store.file(a, false)), "delete with confirm");
        helper.succeed();
    }
}
