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
        if (point == line.points() - 1) {
            out.add("ends: " + line.end().reason() + " t+" + line.end().tick());
        } else if (fuse < 0) {
            out.add("in flight");
        }
        out.add(count > 1 ? "stack x" + count + ", all identical" : "single");
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

    private static void vector(List<String> out, String name, double x, double y, double z) {
        out.add(name + " x " + x);
        out.add(name + " y " + y);
        out.add(name + " z " + z);
    }
}
