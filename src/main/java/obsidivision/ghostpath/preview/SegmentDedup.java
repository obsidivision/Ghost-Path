package obsidivision.ghostpath.preview;

import java.util.HashSet;
import java.util.Set;

public final class SegmentDedup {
    private final Set<Segment> seen = new HashSet<>();

    public void reset() {
        seen.clear();
    }

    public boolean firstTime(int type, long tick, double x0, double y0, double z0, double x1, double y1, double z1) {
        return seen.add(new Segment(type, tick, bits(x0), bits(y0), bits(z0), bits(x1), bits(y1), bits(z1)));
    }

    private static long bits(double value) {
        return Double.doubleToLongBits(value == 0.0 ? 0.0 : value);
    }

    private record Segment(int type, long tick, long x0, long y0, long z0, long x1, long y1, long z1) {}
}
