package obsidivision.ghostpath.watch;

import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.record.CauseTracker;
import org.jspecify.annotations.Nullable;

/**
 * What real entities do at a watched machine's footprint (step 4, ruling 3). A real, non-chain entity counts as a change
 * when it
 * <ul>
 * <li>is <b>added</b> to the footprint (appears in it, or moves into an entity area or onto a footprint block position),</li>
 * <li>is <b>removed</b> from it (dies, despawns, leaves it),</li>
 * <li>or <b>settles at a new position</b>: its position unchanged (1e-6 on every axis; an item also on the ground) for
 *     {@link CauseTracker#ITEM_SETTLE_TICKS} scans in a row (the item rule of the recorder), at a place other than where it
 *     was first seen or last settled.</li>
 * </ul>
 * Ordinary movement of an entity that stays inside does not count: it would rerun constantly. Players are left out: the
 * ghost has none, so a rerun could not predict anything different. Scanned once per server tick. Entities in chunks that
 * are not loaded are not "removed" (they are kept until the chunk is loaded again). The first scan, and the first after
 * {@link #invalidate()} (a recording ran, or the footprint was replaced), only takes the baseline: nothing counts.
 */
final class EntityWatch {
    private static final class Track {
        Vec3 last;
        Vec3 anchor;
        int still;
        long chunk;
    }

    private final Map<UUID, Track> tracks = new HashMap<>();
    private boolean baselined;
    private int added;
    private int removed;
    private int settled;

    void invalidate() {
        this.baselined = false;
        this.tracks.clear();
    }

    int added() {
        return this.added;
    }

    int removed() {
        return this.removed;
    }

    int settled() {
        return this.settled;
    }

    /** One scan; true if something counted as a change. */
    boolean scan(ServerLevel level, Footprint fp) {
        boolean first = !this.baselined;
        boolean changed = false;
        Map<UUID, Entity> present = new HashMap<>();
        Set<Long> scanned = new HashSet<>();
        LongIterator chunks = fp.chunks().iterator();
        while (chunks.hasNext()) {
            long key = chunks.nextLong();
            ChunkPos c = ChunkPos.unpack(key);
            if (level.getChunkSource().getChunkNow(c.x(), c.z()) == null) {
                continue;
            }
            scanned.add(key);
            AABB box = new AABB(c.getMinBlockX(), level.getMinY(), c.getMinBlockZ(), c.getMaxBlockX() + 1.0, level.getMaxY() + 1.0, c.getMaxBlockZ() + 1.0);
            for (Entity e : level.getEntities((Entity) null, box, x -> !(x instanceof Player) && !x.isRemoved())) {
                if (fp.touchesEntity(e.getBoundingBox())) {
                    present.put(e.getUUID(), e);
                }
            }
        }
        for (Map.Entry<UUID, Entity> en : present.entrySet()) {
            Entity e = en.getValue();
            Vec3 p = e.position();
            Track t = this.tracks.get(en.getKey());
            if (t == null) {
                t = new Track();
                t.last = p;
                t.anchor = p;
                this.tracks.put(en.getKey(), t);
                if (!first) {
                    this.added++;
                    changed = true;
                }
            } else {
                boolean still = near(p, t.last) && (!(e instanceof ItemEntity) || e.onGround());
                t.still = still ? t.still + 1 : 0;
                t.last = p;
                if (t.still == CauseTracker.ITEM_SETTLE_TICKS) {
                    if (!near(p, t.anchor)) {
                        this.settled++;
                        changed = true;
                    }
                    t.anchor = p;
                }
            }
            t.chunk = ChunkPos.pack(e.chunkPosition().x(), e.chunkPosition().z());
        }
        var it = this.tracks.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (present.containsKey(en.getKey())) {
                continue;
            }
            ChunkPos c = ChunkPos.unpack(en.getValue().chunk);
            if (level.getChunkSource().getChunkNow(c.x(), c.z()) == null) {
                continue; // its chunk is not loaded: not removed, just not there to see
            }
            it.remove();
            if (!first) {
                this.removed++;
                changed = true;
            }
        }
        this.baselined = true;
        return changed;
    }

    private static boolean near(Vec3 a, @Nullable Vec3 b) {
        return b != null && Math.abs(a.x - b.x) < CauseTracker.ITEM_STILL_EPSILON && Math.abs(a.y - b.y) < CauseTracker.ITEM_STILL_EPSILON
            && Math.abs(a.z - b.z) < CauseTracker.ITEM_STILL_EPSILON;
    }
}
