package obsidivision.ghostpath.preview;

import org.jspecify.annotations.Nullable;

/**
 * The state behind the animation (session D, item 4): sees the live snapshots in the order the renderer sees them and says what to draw
 * now. A new snapshot of a rerun of the same machine starts a {@link PathMorph} from what is drawn at that moment (the old snapshot, or
 * the running morph's current frame: no jump); anything else (a recording, another machine, another dimension, animation off, too many
 * paths) is shown as it is. The clock is passed in (the renderer's real time, so it also runs while the game is paused). Client thread.
 */
public final class MorphTracker {
    private long serial = -1;
    private @Nullable PathSnapshot previous;
    private @Nullable PathMorph morph;
    private long startNanos;
    private double seconds;

    /** Whether a morph is running now (as of the last call of {@link #frame}). */
    public boolean active() {
        return this.morph != null;
    }

    public void reset() {
        this.serial = -1;
        this.previous = null;
        this.morph = null;
    }

    /**
     * The frame to draw for {@code s} at time {@code nowNanos}, or null to draw {@code s} as it is. {@code seconds}: length of the morph (0
     * switches it off), {@code segmentCap}: the renderer's cap on segments per frame.
     */
    public PathMorph.@Nullable Frame frame(@Nullable PathSnapshot s, long nowNanos, double seconds, int segmentCap) {
        if (s == null) {
            this.reset();
            return null;
        }
        if (s.serial() != this.serial) {
            PathSnapshot old = this.previous;
            PathMorph.Frame from = null;
            if (this.morph != null && progress(nowNanos) < 1.0) {
                from = this.morph.at(progress(nowNanos));
            } else if (old != null) {
                from = PathMorph.Frame.of(old);
            }
            this.morph = null;
            if (from != null && old != null && seconds > 0.0 && s.source().equals("rerun") && old.dimension().equals(s.dimension()) && sameMachine(old, s)
                && PathMorph.shouldAnimate(from, s, segmentCap)) {
                this.morph = new PathMorph(from, s);
                this.startNanos = nowNanos;
                this.seconds = seconds;
            }
            this.serial = s.serial();
            this.previous = s;
        }
        PathMorph m = this.morph;
        if (m == null) {
            return null;
        }
        double p = progress(nowNanos);
        if (p >= 1.0) {
            this.morph = null;
            return null;
        }
        return m.at(p);
    }

    private double progress(long nowNanos) {
        return (nowNanos - this.startNanos) / 1.0E9 / this.seconds;
    }

    private static boolean sameMachine(PathSnapshot a, PathSnapshot b) {
        PathSnapshot.Origin x = a.origin();
        PathSnapshot.Origin y = b.origin();
        return x == null || y == null || x.x() == y.x() && x.y() == y.y() && x.z() == y.z();
    }
}
