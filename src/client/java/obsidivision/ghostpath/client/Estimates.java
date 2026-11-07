package obsidivision.ghostpath.client;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.HitResult;
import obsidivision.ghostpath.preview.*;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;

/** Bounded estimates from received entity state. Copies never join the level and produce no hit effects. */
public final class Estimates {
    private static final Set<String> TYPES = Set.of("minecraft:tnt", "minecraft:arrow", "minecraft:spectral_arrow", "minecraft:trident",
        "minecraft:ender_pearl", "minecraft:snowball", "minecraft:egg", "minecraft:splash_potion", "minecraft:lingering_potion");
    private static ClientLevel level;
    private static long serial = -1;
    private static int cursor;
    private static final Map<Integer, PathSnapshot.Line> lines = new LinkedHashMap<>();
    static void clear() { level = null; lines.clear(); cursor = 0; }
    static void tick(Minecraft mc) {
        if (mc.getSingleplayerServer() != null || mc.level == null) return;
        if (mc.level != level) { clear(); level = mc.level; }
        if (RemotePaths.connected || !obsidivision.ghostpath.PathSettings.showPaths) return;
        List<Entity> visible = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) {
            if (!e.isRemoved() && TYPES.contains(EntityType.getKey(e.getType()).toString()) && visible.size() < 32) visible.add(e);
        }
        Set<Integer> ids = new HashSet<>();
        visible.forEach(e -> ids.add(e.getId())); lines.keySet().retainAll(ids);
        if (!visible.isEmpty()) {
            Entity e = visible.get(Math.floorMod(cursor++, visible.size()));
            try { lines.put(e.getId(), predict(level, e)); }
            catch (RuntimeException ex) { lines.remove(e.getId()); }
        }
        PathStore.receive(new PathSnapshot(--serial, "client estimate", level.dimension().identifier().toString(), 160, true, List.copyOf(lines.values())));
    }
    /** Official 26.2 PrimedTnt.tick:100-122 is client-safe; ProjectileHitMixin suppresses projectile impact handlers. */
    public static PathSnapshot.Line predict(ClientLevel world, Entity original) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registryAccess());
        original.saveWithoutId(out);
        var nbt = out.buildResult();
        String type = EntityType.getKey(original.getType()).toString();
        nbt.putString("id", type);
        Entity copy = EntityType.create(TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), nbt), world,
            new EntitySpawnRequest(EntitySpawnReason.LOAD, true)).orElseThrow();
        copy.setId(original.getId());
        copy.setPos(original.position()); copy.setDeltaMovement(original.getDeltaMovement()); copy.tickCount = original.tickCount;
        List<Long> ticks = new ArrayList<>(); List<Double> xyz = new ArrayList<>(), vel = new ArrayList<>(), box = new ArrayList<>(); List<Integer> fuse = new ArrayList<>();
        String reason = "horizon";
        long deadline = System.nanoTime() + 2_000_000L;
        for (int t = 0; t <= 160; t++) {
            if (!world.hasChunkAt(copy.blockPosition())) { reason = "unloaded"; break; }
            if (t > 0) {
                HitResult.Type hit;
                PreviewSim.begin(copy);
                try { copy.tick(); } finally { hit = PreviewSim.end(); }
                if (hit != null) reason = hit == HitResult.Type.BLOCK ? "hit block" : "hit entity";
                if (copy.portalProcess != null) reason = "portal: not simulated";
                if (copy.isRemoved()) reason = copy instanceof PrimedTnt ? "fuse expires" : "removed";
            }
            ticks.add((long)t);
            var p = copy.position(); xyz.add(p.x); xyz.add(p.y); xyz.add(p.z);
            var v = copy.getDeltaMovement(); vel.add(v.x); vel.add(v.y); vel.add(v.z);
            var b = copy.getBoundingBox(); box.add(b.minX); box.add(b.minY); box.add(b.minZ); box.add(b.maxX); box.add(b.maxY); box.add(b.maxZ);
            fuse.add(copy instanceof PrimedTnt tnt ? tnt.getFuse() : -1);
            if (!reason.equals("horizon") || t > 0 && System.nanoTime() >= deadline) break;
        }
        if (ticks.isEmpty()) throw new IllegalStateException("entity outside client chunks");
        int n = ticks.size();
        return new PathSnapshot.Line(type, original instanceof PrimedTnt ? FlightKind.TNT : FlightKind.PROJECTILE, 1, 0,
            ticks.stream().mapToLong(Long::longValue).toArray(), doubles(xyz), doubles(vel), doubles(box), fuse.stream().mapToInt(Integer::intValue).toArray(), new int[]{original.getId()},
            new PathSnapshot.End("estimate: " + reason, ticks.get(n-1), xyz.get(3*n-3), xyz.get(3*n-2), xyz.get(3*n-1)), null, null);
    }
    private static double[] doubles(List<Double> values) { return values.stream().mapToDouble(Double::doubleValue).toArray(); }
}
