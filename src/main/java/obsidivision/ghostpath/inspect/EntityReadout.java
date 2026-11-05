package obsidivision.ghostpath.inspect;

import java.util.UUID;
import net.minecraft.world.phys.AABB;

/**
 * What the server holds for one real entity right now (build step 6): exact doubles, copied on the server thread. {@code fuse}
 * is a primed TNT's remaining fuse (else -1).
 */
public record EntityReadout(int id, UUID uuid, String type, double x, double y, double z, double vx, double vy, double vz, AABB box, int fuse, int tickCount, boolean onGround) {
}
