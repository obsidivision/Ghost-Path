package obsidivision.ghostpath.saved;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;

/**
 * File format of a saved path: compressed NBT, root compound with {@code version} (int, {@link SavedPath#VERSION}). Doubles are stored as
 * their raw bits in long arrays, so every value comes back bit-identical (NaN payloads and -0.0 included). An unknown version or any missing
 * or malformed field throws {@link IOException}: the store skips such a file and warns, it never deletes it.
 */
public final class SavedCodec {
    private SavedCodec() {
    }

    private static long[] bits(double[] d) {
        long[] r = new long[d.length];
        for (int i = 0; i < d.length; i++) {
            r[i] = Double.doubleToRawLongBits(d[i]);
        }
        return r;
    }

    private static double[] unbits(long[] l) {
        double[] r = new double[l.length];
        for (int i = 0; i < l.length; i++) {
            r[i] = Double.longBitsToDouble(l[i]);
        }
        return r;
    }

    public static CompoundTag toNbt(SavedPath p) {
        CompoundTag t = new CompoundTag();
        t.putInt("version", SavedPath.VERSION);
        t.putString("name", p.name());
        t.putString("dimension", p.dimension());
        t.putIntArray("trigger", p.trigger());
        t.putInt("ticks", p.ticks());
        t.putBoolean("limitHit", p.limitHit());
        t.putLong("gameTime", p.gameTime());
        t.putString("date", p.date());
        t.putBoolean("shown", p.shown());
        t.putString("source", p.source());
        ListTag lines = new ListTag();
        for (PathSnapshot.Line l : p.lines()) {
            lines.add(line(l));
        }
        t.put("lines", lines);
        ListTag conts = new ListTag();
        for (Map.Entry<Integer, SavedPath.Cont> e : p.continuations().entrySet()) {
            CompoundTag c = new CompoundTag();
            SavedPath.Cont k = e.getValue();
            c.putInt("line", e.getKey());
            c.putLongArray("ticks", k.ticks());
            c.putLongArray("xyz", bits(k.xyz()));
            c.putLongArray("vel", bits(k.vel()));
            c.putLongArray("box", bits(k.box()));
            c.putString("end", k.endReason());
            c.putBoolean("done", k.done());
            conts.add(c);
        }
        t.put("continuations", conts);
        return t;
    }

    private static CompoundTag line(PathSnapshot.Line l) {
        CompoundTag c = new CompoundTag();
        c.putString("type", l.type());
        c.putString("kind", l.kind().name());
        c.putInt("count", l.count());
        c.putLong("startTick", l.startTick());
        c.putLongArray("ticks", l.ticks());
        c.putLongArray("xyz", bits(l.xyz()));
        c.putLongArray("vel", bits(l.vel()));
        c.putLongArray("box", bits(l.box()));
        c.putIntArray("fuse", l.fuse());
        c.putIntArray("members", l.members());
        c.put("end", end(l.end()));
        if (l.tail() != null) {
            CompoundTag t = new CompoundTag();
            PathSnapshot.Tail tl = l.tail();
            t.putString("type", tl.type());
            t.put("nbt", tl.nbt().copy());
            t.putInt("tickCount", tl.tickCount());
            t.putLongArray("old", bits(new double[] {tl.xo(), tl.yo(), tl.zo()}));
            t.putBoolean("onGround", tl.onGround());
            c.put("tail", t);
        }
        if (l.freeze() != null) {
            PathSnapshot.Freeze f = l.freeze();
            CompoundTag t = new CompoundTag();
            t.putInt("point", f.point());
            t.putLong("tick", f.tick());
            t.putString("kind", f.kind());
            t.putLongArray("pos", bits(new double[] {f.x(), f.y(), f.z()}));
            c.put("freeze", t);
        }
        ListTag st = new ListTag();
        for (PathSnapshot.Stasis s : l.stasis()) {
            CompoundTag t = new CompoundTag();
            t.putInt("point", s.point());
            t.putInt("resume", s.resume());
            t.putLong("fromTick", s.fromTick());
            t.putLong("toTick", s.toTick());
            t.putString("kind", s.kind());
            t.putBoolean("resumed", s.resumed());
            t.putLongArray("ticks", s.ticks());
            t.putLongArray("vel", bits(s.vel()));
            ListTag pushes = new ListTag();
            for (String x : s.pushes()) {
                pushes.add(net.minecraft.nbt.StringTag.valueOf(x));
            }
            t.put("pushes", pushes);
            st.add(t);
        }
        c.put("stasis", st);
        return c;
    }

    private static CompoundTag end(PathSnapshot.End e) {
        CompoundTag t = new CompoundTag();
        t.putString("reason", e.reason());
        t.putLong("tick", e.tick());
        t.putLongArray("pos", bits(new double[] {e.x(), e.y(), e.z()}));
        return t;
    }

    private static <T> T need(java.util.Optional<T> o, String what) throws IOException {
        return o.orElseThrow(() -> new IOException("missing or malformed: " + what));
    }

    public static SavedPath fromNbt(CompoundTag t) throws IOException {
        int version = need(t.getInt("version"), "version");
        if (version != SavedPath.VERSION) {
            throw new IOException("unknown format version " + version + " (this mod reads " + SavedPath.VERSION + ")");
        }
        try {
            String name = need(t.getString("name"), "name");
            int[] trigger = need(t.getIntArray("trigger"), "trigger");
            if (trigger.length != 3) {
                throw new IOException("trigger is not 3 numbers");
            }
            List<PathSnapshot.Line> lines = new ArrayList<>();
            for (Tag x : need(t.getList("lines"), "lines")) {
                if (!(x instanceof CompoundTag c)) {
                    throw new IOException("a line is not a compound");
                }
                lines.add(readLine(c));
            }
            Map<Integer, SavedPath.Cont> conts = new HashMap<>();
            for (Tag x : need(t.getList("continuations"), "continuations")) {
                if (!(x instanceof CompoundTag c)) {
                    throw new IOException("a continuation is not a compound");
                }
                int idx = need(c.getInt("line"), "continuation line");
                if (idx < 0 || idx >= lines.size()) {
                    throw new IOException("continuation of a line that does not exist: " + idx);
                }
                long[] ticks = need(c.getLongArray("ticks"), "continuation ticks");
                double[] xyz = unbits(need(c.getLongArray("xyz"), "continuation xyz"));
                double[] vel = unbits(need(c.getLongArray("vel"), "continuation vel"));
                double[] box = unbits(need(c.getLongArray("box"), "continuation box"));
                if (xyz.length != ticks.length * 3 || vel.length != ticks.length * 3 || box.length != ticks.length * 6) {
                    throw new IOException("continuation arrays differ in length");
                }
                conts.put(idx, new SavedPath.Cont(ticks, xyz, vel, box, need(c.getString("end"), "continuation end"), need(c.getBoolean("done"), "continuation done")));
            }
            SavedPath p = new SavedPath(name, need(t.getString("dimension"), "dimension"), trigger, need(t.getInt("ticks"), "ticks"), need(t.getBoolean("limitHit"), "limitHit"),
                need(t.getLong("gameTime"), "gameTime"), need(t.getString("date"), "date"), need(t.getBoolean("shown"), "shown"), need(t.getString("source"), "source"), List.copyOf(lines), Map.copyOf(conts));
            return p;
        } catch (RuntimeException e) {
            throw new IOException("malformed: " + e, e);
        }
    }

    private static PathSnapshot.Line readLine(CompoundTag c) throws IOException {
        long[] ticks = need(c.getLongArray("ticks"), "line ticks");
        double[] xyz = unbits(need(c.getLongArray("xyz"), "line xyz"));
        double[] vel = unbits(need(c.getLongArray("vel"), "line vel"));
        double[] box = unbits(need(c.getLongArray("box"), "line box"));
        int[] fuse = need(c.getIntArray("fuse"), "line fuse");
        int n = ticks.length;
        if (xyz.length != n * 3 || vel.length != n * 3 || box.length != n * 6 || fuse.length != n || n == 0) {
            throw new IOException("line arrays differ in length");
        }
        FlightKind kind;
        try {
            kind = FlightKind.valueOf(need(c.getString("kind"), "line kind"));
        } catch (IllegalArgumentException e) {
            throw new IOException("unknown kind");
        }
        CompoundTag e = need(c.getCompound("end"), "line end");
        double[] ep = unbits(need(e.getLongArray("pos"), "end pos"));
        PathSnapshot.End end = new PathSnapshot.End(need(e.getString("reason"), "end reason"), need(e.getLong("tick"), "end tick"), ep[0], ep[1], ep[2]);
        PathSnapshot.Tail tail = null;
        if (c.contains("tail")) {
            CompoundTag t = need(c.getCompound("tail"), "tail");
            double[] old = unbits(need(t.getLongArray("old"), "tail old"));
            tail = new PathSnapshot.Tail(need(t.getString("type"), "tail type"), need(t.getCompound("nbt"), "tail nbt").copy(), need(t.getInt("tickCount"), "tail tickCount"),
                old[0], old[1], old[2], need(t.getBoolean("onGround"), "tail onGround"));
        }
        PathSnapshot.Freeze freeze = null;
        if (c.contains("freeze")) {
            CompoundTag t = need(c.getCompound("freeze"), "freeze");
            double[] pos = unbits(need(t.getLongArray("pos"), "freeze pos"));
            freeze = new PathSnapshot.Freeze(need(t.getInt("point"), "freeze point"), need(t.getLong("tick"), "freeze tick"), need(t.getString("kind"), "freeze kind"), pos[0], pos[1], pos[2]);
        }
        List<PathSnapshot.Stasis> stasis = new ArrayList<>();
        for (Tag x : need(c.getList("stasis"), "stasis")) {
            if (!(x instanceof CompoundTag t)) {
                throw new IOException("a stasis is not a compound");
            }
            List<String> pushes = new ArrayList<>();
            for (Tag s : need(t.getList("pushes"), "pushes")) {
                pushes.add(need(s.asString(), "push"));
            }
            stasis.add(new PathSnapshot.Stasis(need(t.getInt("point"), "stasis point"), need(t.getInt("resume"), "stasis resume"), need(t.getLong("fromTick"), "stasis from"),
                need(t.getLong("toTick"), "stasis to"), need(t.getString("kind"), "stasis kind"), need(t.getBoolean("resumed"), "stasis resumed"),
                need(t.getLongArray("ticks"), "stasis ticks"), unbits(need(t.getLongArray("vel"), "stasis vel")), List.copyOf(pushes)));
        }
        return new PathSnapshot.Line(need(c.getString("type"), "line type"), kind, need(c.getInt("count"), "line count"), need(c.getLong("startTick"), "line startTick"), ticks, xyz, vel, box, fuse,
            need(c.getIntArray("members"), "line members"), end, tail, freeze, List.copyOf(stasis));
    }
}
