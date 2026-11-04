package obsidivision.ghostpath.preview;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * State of the client-side continuation of a flight (build step 5): one copy of an entity is ticked at a time, on the client
 * thread, with the vanilla tick code. While it is, {@code Projectile.hitTargetOrDeflectSelf} does not run its hit code (the
 * hit is recorded here and the copy stops) and the client level's particles, sounds and level events are dropped (client
 * mixins; hit effects off). The flag is a plain static: it is set and read on the client thread only, around one
 * {@code tick()} call.
 */
public final class PreviewSim {
    private static @Nullable Entity current;
    private static HitResult.@Nullable Type hit;

    private PreviewSim() {
    }

    /** Starts ticking {@code copy}: effects off until {@link #end()}. */
    public static void begin(Entity copy) {
        current = copy;
        hit = null;
    }

    /** Effects on again. Returns the hit the copy made during the tick, or null. */
    public static HitResult.@Nullable Type end() {
        HitResult.Type h = hit;
        current = null;
        hit = null;
        return h;
    }

    /** Whether a copy is being ticked (the client level drops particles, sounds and level events). */
    public static boolean active() {
        return current != null;
    }

    /** Whether {@code e} is the copy being ticked. */
    public static boolean simulating(Entity e) {
        return current == e;
    }

    /** The copy's {@code hitTargetOrDeflectSelf} was called: remember the first hit. */
    public static void hit(HitResult result) {
        if (hit == null) {
            hit = result.getType();
        }
    }
}
