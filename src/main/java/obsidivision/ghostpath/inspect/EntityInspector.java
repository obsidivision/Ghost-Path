package obsidivision.ghostpath.inspect;

import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The server-side value lookup for hovering a real TNT or projectile (build step 6): the client picks an entity id with its own
 * copy of the world, then asks the built-in server (on the server thread) for the entity with that id and shows the server's
 * exact values and bounding box. Also the stack: the entities of the same type at the same place.
 */
public final class EntityInspector {
    /** Entities of the same type whose position differs by less than this on every axis are one stack. */
    public static final double STACK_EPSILON = 1.0E-3;

    private EntityInspector() {
    }

    /** A stack: every member (sorted by entity id, the hovered one included), and whether all are bit-identical in position, velocity, fuse, age and ground state. */
    public record Stack(List<EntityReadout> members, boolean identical) {
    }

    /** Server thread. The entity with {@code id} in {@code level}, or null. */
    public static @Nullable EntityReadout read(ServerLevel level, int id) {
        Entity e = level.getEntity(id);
        return e == null || e.isRemoved() ? null : readout(e);
    }

    public static EntityReadout readout(Entity e) {
        Vec3 p = e.position();
        Vec3 v = e.getDeltaMovement();
        return new EntityReadout(e.getId(), e.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), p.x, p.y, p.z, v.x, v.y, v.z, e.getBoundingBox(),
            e instanceof PrimedTnt tnt ? tnt.getFuse() : -1, e.tickCount, e.onGround());
    }

    /** Server thread. The stack of the entity with {@code id}, or null when there is no such entity. */
    public static @Nullable Stack stack(ServerLevel level, int id) {
        Entity target = level.getEntity(id);
        if (target == null || target.isRemoved()) {
            return null;
        }
        Vec3 p = target.position();
        AABB area = new AABB(p.x - STACK_EPSILON, p.y - STACK_EPSILON, p.z - STACK_EPSILON, p.x + STACK_EPSILON, p.y + STACK_EPSILON, p.z + STACK_EPSILON).inflate(1.0);
        List<EntityReadout> members = level.getEntities(target.getType(), area, e -> !e.isRemoved() && Math.abs(e.getX() - p.x) < STACK_EPSILON
                && Math.abs(e.getY() - p.y) < STACK_EPSILON && Math.abs(e.getZ() - p.z) < STACK_EPSILON)
            .stream().map(EntityInspector::readout).sorted(Comparator.comparingInt(EntityReadout::id)).toList();
        boolean identical = true;
        EntityReadout first = members.getFirst();
        for (EntityReadout m : members) {
            identical &= Double.doubleToLongBits(m.x()) == Double.doubleToLongBits(first.x()) && Double.doubleToLongBits(m.y()) == Double.doubleToLongBits(first.y())
                && Double.doubleToLongBits(m.z()) == Double.doubleToLongBits(first.z()) && Double.doubleToLongBits(m.vx()) == Double.doubleToLongBits(first.vx())
                && Double.doubleToLongBits(m.vy()) == Double.doubleToLongBits(first.vy()) && Double.doubleToLongBits(m.vz()) == Double.doubleToLongBits(first.vz())
                && m.fuse() == first.fuse() && m.tickCount() == first.tickCount() && m.onGround() == first.onGround();
        }
        return new Stack(members, identical);
    }
}
