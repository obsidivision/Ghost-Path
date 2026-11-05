package obsidivision.ghostpath.inspect;

import java.util.ArrayList;
import java.util.List;
import obsidivision.ghostpath.preview.PathSnapshot;

/**
 * The text of the inspector's panel (build step 6), as plain lines. Doubles are written with {@link Double#toString}, which
 * gives the shortest text that reads back as the same double ({@code Double.parseDouble}); the in-block fraction is {@code v -
 * floor(v)}, exact for doubles. One value per line ({@code pos x ...}, {@code pos y ...}, ...).
 */
public final class PanelText {
    private PanelText() {
    }

    /** One recorded point of a ghost path. {@code member} is the cursor into {@code line.members()} (scrolled through with the inspect key). */
    public static List<String> ghost(PathSnapshot.Line line, int point, int member, String source) {
        List<String> out = new ArrayList<>();
        long tick = line.ticks()[point];
        int count = line.count();
        out.add(line.type() + "  t+" + tick + "  (" + source + ")");
        out.add("entity #" + line.members()[Math.floorMod(member, line.members().length)] + (count > 1 ? "  " + (Math.floorMod(member, count) + 1) + "/" + count : ""));
        position(out, line.xyz()[3 * point], line.xyz()[3 * point + 1], line.xyz()[3 * point + 2]);
        vector(out, "vel", line.vel()[3 * point], line.vel()[3 * point + 1], line.vel()[3 * point + 2]);
        int fuse = line.fuse()[point];
        if (fuse >= 0) {
            out.add("fuse " + fuse);
        }
        PathSnapshot.Freeze f = line.freeze();
        if (f != null) {
            out.add(point >= f.point() ? "BEYOND the real ticking area (" + f.kind() + "): the real one froze at t+" + f.tick()
                : "real: freezes at t+" + f.tick() + " (" + f.kind() + ")");
        }
        PathSnapshot.Stasis st = line.stasisAt(point);
        if (st != null) {
            frozen(out, st);
        }
        for (PathSnapshot.Stasis s : line.stasis()) {
            if (s.resume() == point) {
                out.add("moved while frozen (t+" + s.fromTick() + "..t+" + s.toTick() + "), " + (s.resumed() ? "ticks again at t+" + (s.toTick() + 1) : "still frozen at the end"));
            }
        }
        if (point == line.points() - 1) {
            out.add("ends: " + PathSnapshot.endText(line));
        } else if (fuse < 0) {
            out.add("in flight");
        }
        out.add(count > 1 ? "stack x" + count + ", all identical" : "single");
        return out;
    }

    /** How close (blocks) another path's point at the same tick must be to count as "here" ({@link #here}). */
    public static final double HERE = 0.05;

    /**
     * How many entities (counting merged identical paths) of the same type are within {@link #HERE} blocks of {@code line}'s point {@code point},
     * at the same tick, this one included. Paths are only merged when every tick is equal, so a batch of TNT that meets at one place from
     * different directions is many lines; this tells the panel they are on top of each other.
     */
    public static int here(PathSnapshot s, PathSnapshot.Line line, int point) {
        long tick = line.ticks()[point];
        double x = line.xyz()[3 * point];
        double y = line.xyz()[3 * point + 1];
        double z = line.xyz()[3 * point + 2];
        int n = 0;
        for (PathSnapshot.Line o : s.lines()) {
            if (!o.type().equals(line.type())) {
                continue;
            }
            int i = java.util.Arrays.binarySearch(o.ticks(), tick);
            if (i >= 0 && Math.abs(o.xyz()[3 * i] - x) <= HERE && Math.abs(o.xyz()[3 * i + 1] - y) <= HERE && Math.abs(o.xyz()[3 * i + 2] - z) <= HERE) {
                n += o.count();
            }
        }
        return n;
    }

    /** {@link #ghost} plus the "N entities here" line when other paths are on top of this one (instead of "single"). */
    public static List<String> ghost(PathSnapshot s, PathSnapshot.Line line, int point, int member, String source) {
        List<String> out = ghost(line, point, member, source);
        int here = here(s, line, point);
        if (here > line.count() && out.getLast().equals("single")) {
            out.set(out.size() - 1, here + " entities within " + HERE + " here, on separate paths");
        } else if (here > line.count()) {
            out.add(here + " entities within " + HERE + " here in all");
        }
        return out;
    }

    /** Most velocity rows listed for one frozen stretch (equal velocities on consecutive ticks share a row). */
    public static final int MAX_FROZEN_ROWS = 12;

    /**
     * A frozen stretch at its freeze point: how long, why, and the velocity at the end of each frozen tick, so a lazy-acceleration
     * build-up is visible: consecutive ticks with the same velocity share a row ({@code t+5..t+79}), and a row says which chain explosions
     * pushed it in its first tick ({@code explosion #3}). Five decimals here; the exact doubles are in the trace and the snapshot.
     */
    static void frozen(List<String> out, PathSnapshot.Stasis st) {
        out.add("FROZEN " + st.frozenTicks() + " ticks (" + st.kind() + ") t+" + st.fromTick() + "..t+" + st.toTick()
            + (st.resumed() ? ", then ticks again" : ", until the end"));
        java.util.Map<Long, List<String>> pushedAt = new java.util.LinkedHashMap<>();
        for (String p : st.pushes()) {
            int at = p.lastIndexOf(" t+");
            long tick = Long.parseLong(p.substring(at + 3));
            pushedAt.computeIfAbsent(tick, k -> new ArrayList<>()).add(p.substring(0, at).replace("explosion ", ""));
        }
        int rows = 0;
        int i = 0;
        double[] v = st.vel();
        long[] t = st.ticks();
        while (i < t.length) {
            int j = i;
            while (j + 1 < t.length && v[3 * (j + 1)] == v[3 * i] && v[3 * (j + 1) + 1] == v[3 * i + 1] && v[3 * (j + 1) + 2] == v[3 * i + 2]
                && !pushedAt.containsKey(t[j + 1]) && t[j + 1] == t[j] + 1) {
                j++;
            }
            if (rows == MAX_FROZEN_ROWS) {
                out.add("... " + (t.length - i) + " more frozen ticks");
                break;
            }
            List<String> pushes = pushedAt.get(t[i]);
            out.add((i == j ? "t+" + t[i] : "t+" + t[i] + "..t+" + t[j]) + " vel " + String.format(java.util.Locale.ROOT, "%.5f %.5f %.5f", v[3 * i], v[3 * i + 1], v[3 * i + 2])
                + (pushes == null ? "" : " < explosion " + String.join(" ", pushes)));
            rows++;
            i = j + 1;
        }
    }

    /**
     * One point of a client-side continuation (a projectile still flying when the ghost run stopped at the tick limit). Only the
     * position, velocity and box are known; the client's copy, not the server's, made it.
     */
    public static List<String> continued(String type, long tick, double[] pos, double[] vel, boolean last, String endReason) {
        List<String> out = new ArrayList<>();
        out.add(type + "  t+" + tick + "  (continued, client-side)");
        position(out, pos[0], pos[1], pos[2]);
        vector(out, "vel", vel[0], vel[1], vel[2]);
        out.add(last && endReason != null ? "ends: " + endReason + " t+" + tick : "in flight");
        out.add("client-side copy, not the server's run");
        return out;
    }

    /** A real entity, as the server holds it; {@code index} of {@code count} in its stack. */
    public static List<String> real(EntityReadout e, int index, int count, boolean identical) {
        List<String> out = new ArrayList<>();
        out.add(e.type() + "  (real, server)");
        out.add("entity id " + e.id() + (count > 1 ? "  " + (index + 1) + "/" + count : "") + "  age " + e.tickCount());
        position(out, e.x(), e.y(), e.z());
        vector(out, "vel", e.vx(), e.vy(), e.vz());
        out.add(e.fuse() >= 0 ? "fuse " + e.fuse() : e.onGround() ? "on ground" : "in flight");
        out.add(count > 1 ? "stack x" + count + (identical ? ", all identical" : ", NOT all identical") : "single");
        return out;
    }

    /** The position, the block it is in and where in the block, one value per line: a screen has room for about 60 characters, a double can take 24. */
    private static void position(List<String> out, double x, double y, double z) {
        vector(out, "pos", x, y, z);
        out.add("block " + (int) Math.floor(x) + " " + (int) Math.floor(y) + " " + (int) Math.floor(z));
        vector(out, "in block", x - Math.floor(x), y - Math.floor(y), z - Math.floor(z));
    }

    /**
     * The short form of a panel ({@code /path hover compact}): the header, the position and the velocity on one line each (rounded), and the lines
     * that say what happens (fuse, end, frozen, stack count, real/saved notes). Dropped: the entity id line, the block and in-block rows, "single".
     */
    public static List<String> compact(List<String> lines) {
        List<String> out = new ArrayList<>();
        String[] pos = new String[3];
        String[] vel = new String[3];
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (i == 0 || l.equals("PIN")) {
                out.add(l);
            } else if (l.startsWith("pos ") || l.startsWith("vel ")) {
                String[] t = l.split(" ", 3);
                int axis = t[1].charAt(0) - 'x';
                double v;
                try {
                    v = Double.parseDouble(t[2]);
                } catch (NumberFormatException e) {
                    continue;
                }
                (t[0].equals("pos") ? pos : vel)[axis] = String.format(java.util.Locale.ROOT, t[0].equals("pos") ? "%.2f" : "%.3f", v);
                if (axis == 2) {
                    String[] a = t[0].equals("pos") ? pos : vel;
                    out.add(t[0] + " " + a[0] + ", " + a[1] + ", " + a[2]);
                }
            } else if (l.startsWith("entity ") || l.startsWith("block ") || l.startsWith("in block ") || l.equals("single") || l.startsWith("client-side copy")) {
                continue;
            } else {
                out.add(l);
            }
        }
        return out;
    }

    private static void vector(List<String> out, String name, double x, double y, double z) {
        out.add(name + " x " + x);
        out.add(name + " y " + y);
        out.add(name + " z " + z);
    }
}
