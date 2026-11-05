package obsidivision.ghostpath.saved;

import java.util.List;
import java.util.Map;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;

/**
 * A saved prediction (session D): the latest {@link PathSnapshot} frozen under a name, plus where it came from. A snapshot, never rerun:
 * it is drawn and inspected from this data only. Immutable; {@link #withShown} makes the changed copy.
 *
 * @param name         {@code [a-z0-9_-]{1,32}}
 * @param dimension    dimension id; drawn only there
 * @param trigger      the machine's trigger block (x, y, z)
 * @param ticks        recording tick count of the saved run
 * @param gameTime     the level's game time when the trigger was pressed
 * @param date         ISO-8601 instant of the save
 * @param shown        drawn (saved too: survives leaving the world)
 * @param lines        every path (exact doubles, ×N count, end reasons, freeze and frozen marks)
 * @param continuations the client's continuation points of lines that were still flying at the tick limit, by line index
 */
public record SavedPath(String name, String dimension, int[] trigger, int ticks, boolean limitHit, long gameTime, String date, boolean shown, String source,
                        List<PathSnapshot.Line> lines, Map<Integer, Cont> continuations) {
    public static final int VERSION = 1;
    public static final int MAX_NAME = 32;

    /** Valid names: lower case letters, digits, underscore, hyphen, 1 to 32 characters (no path tricks). */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    /**
     * The client's continuation of one line (the part after the tick limit), as it was when saved. {@code done} false: it was still running.
     * {@code vel} three doubles and {@code box} six per point, like the live continuation.
     */
    public record Cont(long[] ticks, double[] xyz, double[] vel, double[] box, String endReason, boolean done) {
        public int points() {
            return this.ticks.length;
        }
    }

    public SavedPath withShown(boolean shown) {
        return new SavedPath(this.name, this.dimension, this.trigger, this.ticks, this.limitHit, this.gameTime, this.date, shown, this.source, this.lines, this.continuations);
    }

    /** TNT in the saved prediction (the sum of the ×N counts of TNT lines). */
    public int tnt() {
        int n = 0;
        for (PathSnapshot.Line l : this.lines) {
            if (l.kind() == FlightKind.TNT) {
                n += l.count();
            }
        }
        return n;
    }

    public String triggerText() {
        return this.trigger[0] + " " + this.trigger[1] + " " + this.trigger[2];
    }

    /** A snapshot view of the saved lines, for code that takes a {@link PathSnapshot} (serial -1: not a live one). */
    public PathSnapshot asSnapshot() {
        return new PathSnapshot(-1, "saved", this.dimension, this.ticks, this.limitHit, this.lines);
    }
}
