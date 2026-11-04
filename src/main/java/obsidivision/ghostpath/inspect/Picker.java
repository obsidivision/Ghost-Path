package obsidivision.ghostpath.inspect;

import java.util.List;
import java.util.function.ToDoubleFunction;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.preview.PathSnapshot;
import org.jspecify.annotations.Nullable;

/**
 * Picks a path point with a ray (build step 6): the nearest to the camera of the recorded points and the path segments between
 * them that the ray passes within a tolerance of. Pure maths on a {@link PathSnapshot}: no world, no client. The caller gives the
 * ray (camera position and look direction), the longest distance to look (64 blocks, or less when a block is in the way and the
 * inspect key is not held) and the lift at which each line is drawn above the entity's feet, so the pick matches what is seen.
 *
 * <p>A hit on a segment returns the nearer of its two points, so every recorded tick is pickable, not only the dots drawn.
 */
public final class Picker {
    /** Longest distance a path point is picked at. */
    public static final double MAX_DISTANCE = 64.0;

    private Picker() {
    }

    /** Where a ray hit: the line and point index, how far along the ray, and how far off the ray the point is. */
    public record Hit(int line, int point, double along, double lateral) {
    }

    /** How far from the ray something still counts as pointed at, at {@code along} blocks: grows with the distance, like a dot's size on screen. */
    public static double tolerance(double along) {
        return 0.1 + 0.01 * along;
    }

    public static @Nullable Hit pick(PathSnapshot snapshot, Vec3 origin, Vec3 direction, double maxDistance, ToDoubleFunction<PathSnapshot.Line> lift) {
        Vec3 d = direction.normalize();
        Hit best = null;
        List<PathSnapshot.Line> lines = snapshot.lines();
        for (int li = 0; li < lines.size(); li++) {
            PathSnapshot.Line l = lines.get(li);
            int n = l.points();
            double up = lift.applyAsDouble(l);
            double[] xyz = l.xyz();
            for (int i = 0; i < n; i++) {
                double ax = xyz[3 * i];
                double ay = xyz[3 * i + 1] + up;
                double az = xyz[3 * i + 2];
                double bx = i == 0 ? ax : xyz[3 * i - 3];
                double by = i == 0 ? ay : xyz[3 * i - 2] + up;
                double bz = i == 0 ? az : xyz[3 * i - 1];
                // the segment from point i-1 (b) to point i (a); for the first point just the point
                double[] r = closest(origin, d, bx, by, bz, ax, ay, az, maxDistance);
                double along = r[0];
                double lateral = r[1];
                double u = r[2];
                if (lateral <= tolerance(along) && (best == null || along < best.along() || along == best.along() && lateral < best.lateral())) {
                    best = new Hit(li, i == 0 ? 0 : u < 0.5 ? i - 1 : i, along, lateral);
                }
            }
        }
        return best;
    }

    /**
     * Closest approach of the ray {@code o + t d} (t in [0, max], d a unit vector) to the segment from b to a: {t, distance,
     * u} with u the position on the segment, 0 at b and 1 at a.
     */
    static double[] closest(Vec3 o, Vec3 d, double bx, double by, double bz, double ax, double ay, double az, double max) {
        double ex = ax - bx;
        double ey = ay - by;
        double ez = az - bz;
        double wx = o.x - bx;
        double wy = o.y - by;
        double wz = o.z - bz;
        double c = ex * ex + ey * ey + ez * ez;
        double bb = d.x * ex + d.y * ey + d.z * ez;
        double dd = d.x * wx + d.y * wy + d.z * wz;
        double ee = ex * wx + ey * wy + ez * wz;
        double denom = c - bb * bb;
        double u;
        if (c < 1.0E-12) {
            u = 0.0;
        } else if (denom < 1.0E-12 * c) { // the segment is parallel to the ray
            u = 0.0;
        } else {
            u = Math.max(0.0, Math.min(1.0, (ee - dd * bb) / denom));
        }
        double t = Math.max(0.0, Math.min(max, u * bb - dd));
        if (c >= 1.0E-12) { // with t fixed, the best u
            u = Math.max(0.0, Math.min(1.0, (ee + t * bb) / c));
            t = Math.max(0.0, Math.min(max, u * bb - dd));
        }
        double px = o.x + t * d.x - (bx + u * ex);
        double py = o.y + t * d.y - (by + u * ey);
        double pz = o.z + t * d.z - (bz + u * ez);
        return new double[] {t, Math.sqrt(px * px + py * py + pz * pz), u};
    }
}
