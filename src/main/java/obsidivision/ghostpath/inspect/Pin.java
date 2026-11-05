package obsidivision.ghostpath.inspect;

import java.util.List;
import obsidivision.ghostpath.preview.PathSnapshot;
import org.jspecify.annotations.Nullable;

/**
 * A pinned path point (build step 6). It names the entity, not a place in one snapshot: the entity type, the chain entity index
 * (the order the chain created entities in, the same in a rerun) and the tick. A new snapshot (a rerun, a new recording) is
 * searched for the line that has this entity among its members and the point at this tick, so the pin sticks to its tick
 * number across reruns although the path itself changed; it stays (greyed) when the new prediction has no such point.
 */
public record Pin(String type, int member, long tick) {
    /** Where a pin points in a snapshot: the line and point, or {@code problem} says why it points nowhere. */
    public record Resolution(int line, int point, @Nullable String problem) {
        public boolean found() {
            return this.problem == null;
        }
    }

    public Resolution resolve(PathSnapshot snapshot) {
        List<PathSnapshot.Line> lines = snapshot.lines();
        for (int li = 0; li < lines.size(); li++) {
            PathSnapshot.Line l = lines.get(li);
            if (!l.type().equals(this.type)) {
                continue;
            }
            for (int m : l.members()) {
                if (m == this.member) {
                    int p = l.indexOfTick(this.tick);
                    return p < 0 ? new Resolution(li, -1, "no point at t+" + this.tick + " (the path runs t+" + l.startTick() + "..t+" + l.end().tick() + ")") : new Resolution(li, p, null);
                }
            }
        }
        return new Resolution(-1, -1, "the latest prediction has no such path");
    }

    /** Adds {@code pin} to {@code pins}, or removes it when it is already there. Returns whether it is now pinned. */
    public static boolean toggle(List<Pin> pins, Pin pin) {
        return !pins.remove(pin) && pins.add(pin);
    }
}
