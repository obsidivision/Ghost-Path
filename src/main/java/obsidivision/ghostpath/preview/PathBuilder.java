package obsidivision.ghostpath.preview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;
import obsidivision.ghostpath.record.CauseTracker.FlightNote;
import obsidivision.ghostpath.record.CauseTracker.FlightPoint;
import obsidivision.ghostpath.record.CauseTracker.LiveEntity;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.watch.Prediction;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Builds a {@link PathSnapshot} from a run's flight points (build step 5): per chain entity of the drawn kinds (TNT,
 * projectiles, items, falling blocks, vehicles) the positions per tick up to the tick its path ends, the end reason, and
 * identical paths merged into one with a count. Pure data in, pure data out; runs on the server thread after the run.
 *
 * <p>End reasons ({@link #REASONS}), by the earliest of these events of the entity:
 * <ul>
 * <li>{@code portal}: it was in a portal ({@code Entity.portalProcess != null}); portals are not simulated;</li>
 * <li>{@code hit block} / {@code hit entity}: a projectile's {@code hitTargetOrDeflectSelf} (an arrow that sticks in a block
 *     is not removed, so the hit ends its path);</li>
 * <li>{@code stuck}: an arrow that stayed in the block it hit for {@code CauseTracker.ARROW_SETTLE_TICKS} ticks (note "stuck" at
 *     the tick it stuck, which is the hit); an arrow that did not stay ends {@code hit block};</li>
 * <li>{@code settled}: an item on the ground (a vehicle: anywhere) whose position did not change (1e-6) for
 *     {@value #SETTLE_TICKS} ticks in a row (the recorder's item rule);</li>
 * <li>removed: TNT {@code exploded}; a falling block {@code landed}; unloaded with its chunk {@code unloaded};
 *     {@code left dimension}; anything else {@code removed};</li>
 * <li>otherwise it was still there when the run stopped: {@code limit}.</li>
 * </ul>
 */
public final class PathBuilder {
    public static final int SETTLE_TICKS = 8;
    public static final double STILL_EPSILON = 1.0E-6;
    public static final List<String> REASONS = List.of("exploded", "hit block", "hit entity", "stuck", "landed", "settled", "left dimension", "unloaded", "portal", "limit", "removed",
        "frozen: lazy chunk", "frozen: unloaded");

    private PathBuilder() {
    }

    /** The real run of {@code /path machine}; {@code copied}: the chunk rectangle the ghost check copies (x min, z min, x max, z max), or null. */
    public static PathSnapshot of(long serial, Recording r, int @Nullable [] copied) {
        return build(serial, "recorded", r.dimension().identifier().toString(), r.ticks(), r.limitHit(), r.flightPoints(), r.flightNotes(), r.liveProjectiles(), Map.of())
            .withArea(AreaBuilder.build(r.writes(), r.footprint(), PathSettings.watchMargin, copied, null, null));
    }

    public static PathSnapshot of(long serial, Recording r) {
        return of(serial, r, null);
    }

    /** A ghost rerun. */
    public static PathSnapshot of(long serial, Prediction p, String dimension) {
        return of(serial, p, dimension, null, null);
    }

    /**
     * A ghost rerun with the area data: {@code base} is the machine's own first rectangle (x min, z min, x max, z max) when the run was
     * widened, {@code change} the block change that started it.
     */
    public static PathSnapshot of(long serial, Prediction p, String dimension, int @Nullable [] base, AreaData.@Nullable Change change) {
        int[] copied = {p.copiedMin().x(), p.copiedMin().z(), p.copiedMax().x(), p.copiedMax().z()};
        return build(serial, "rerun", dimension, p.ticks(), p.limitHit(), p.flightPoints(), p.flightNotes(), p.liveProjectiles(), p.notTicking())
            .withArea(AreaBuilder.build(p.footprint().writes(), p.footprint().positions(), PathSettings.watchMargin, copied, p.widened() ? base : null, change));
    }

    public static PathSnapshot build(long serial, String source, String dimension, int ticks, boolean limitHit, List<FlightPoint> points,
                                     List<FlightNote> notes, List<LiveEntity> live) {
        return build(serial, source, dimension, ticks, limitHit, points, notes, live, Map.of());
    }

    /** {@code notTicking}: chunk key to "unloaded" or "lazy" for the chunks the real world does not tick entities in (reruns). */
    public static PathSnapshot build(long serial, String source, String dimension, int ticks, boolean limitHit, List<FlightPoint> points,
                                     List<FlightNote> notes, List<LiveEntity> live, Map<Long, String> notTicking) {
        Map<Integer, List<FlightPoint>> byEntity = new LinkedHashMap<>();
        for (FlightPoint p : points) {
            if (p.kind() != FlightKind.OTHER) {
                byEntity.computeIfAbsent(p.index(), k -> new ArrayList<>()).add(p);
            }
        }
        Map<Integer, List<FlightNote>> notesBy = new LinkedHashMap<>();
        for (FlightNote n : notes) {
            notesBy.computeIfAbsent(n.index(), k -> new ArrayList<>()).add(n);
        }
        Map<Integer, LiveEntity> liveBy = new LinkedHashMap<>();
        for (LiveEntity l : live) {
            liveBy.put(l.index(), l);
        }
        // merge identical paths: same type, start, end and every position equal
        Map<Key, PathSnapshot.Line> merged = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<FlightPoint>> en : byEntity.entrySet()) {
            PathSnapshot.Line line = line(en.getValue(), notesBy.getOrDefault(en.getKey(), List.of()), liveBy.get(en.getKey()), limitHit, notTicking);
            Key key = new Key(line);
            PathSnapshot.Line old = merged.get(key);
            if (old == null) {
                merged.put(key, line);
            } else {
                int[] members = Arrays.copyOf(old.members(), old.members().length + 1);
                members[members.length - 1] = en.getKey();
                merged.put(key, new PathSnapshot.Line(old.type(), old.kind(), old.count() + 1, old.startTick(), old.ticks(), old.xyz(), old.vel(), old.box(), old.fuse(), members,
                    old.end(), old.tail(), old.freeze(), old.stasis()));
            }
        }
        return new PathSnapshot(serial, source, dimension, ticks, limitHit, List.copyOf(merged.values()));
    }

    private static PathSnapshot.Line line(List<FlightPoint> raw, List<FlightNote> notes, @Nullable LiveEntity live, boolean limitHit, Map<Long, String> notTicking) {
        // one point per tick: the last of the tick (an entity added and ticked in the same game tick has two)
        List<FlightPoint> all = new ArrayList<>();
        for (FlightPoint p : raw) {
            if (!all.isEmpty() && all.getLast().tick() == p.tick()) {
                all.set(all.size() - 1, p);
            } else {
                all.add(p);
            }
        }
        // frozen ticks (session "frozen entities") are not drawn: each stretch becomes a Stasis at its freeze point; where it is at the end
        // of the stretch becomes a point only if it was moved meanwhile (a piston push: drawn dashed)
        List<FlightPoint> pts = new ArrayList<>();
        List<PathSnapshot.Stasis> stasis = new ArrayList<>();
        List<FlightPoint> run = new ArrayList<>();
        for (FlightPoint p : all) {
            if (p.frozen() != null) {
                run.add(p);
                continue;
            }
            if (!run.isEmpty()) {
                stasis.add(stasis(pts, run, true, notes));
                run.clear();
            }
            pts.add(p);
        }
        boolean endedFrozen = !run.isEmpty();
        String frozenKind = endedFrozen ? run.getLast().frozen() : null;
        long frozenEnd = endedFrozen ? run.getLast().tick() : -1;
        if (endedFrozen) {
            stasis.add(stasis(pts, run, false, notes));
        }
        FlightKind kind = pts.getFirst().kind();
        long endTick = Long.MAX_VALUE;
        String reason = null;
        // the earliest ending event wins; on a tie the order below
        for (FlightPoint p : pts) {
            if (p.portal()) {
                endTick = p.tick();
                reason = "portal";
                break;
            }
        }
        long stuckTick = Long.MAX_VALUE;
        for (FlightNote n : notes) {
            if (n.what().startsWith(obsidivision.ghostpath.record.CauseTracker.EXPLOSION_NOTE)) {
                continue; // a push while frozen, not an end
            }
            if (n.what().equals("stuck")) {
                stuckTick = Math.min(stuckTick, n.tick());
                continue;
            }
            String r = n.what().equals("hit block") ? "hit block" : n.what().equals("hit entity") ? "hit entity" : removal(kind, n.what());
            if (n.tick() < endTick) {
                endTick = n.tick();
                reason = r;
            }
        }
        if (stuckTick != Long.MAX_VALUE && stuckTick <= endTick) { // the hit and the stuck are the same tick; an earlier end (portal, removal) wins
            endTick = stuckTick;
            reason = "stuck";
        }
        if (kind == FlightKind.ITEM || kind == FlightKind.VEHICLE) {
            int still = 0;
            for (int i = 1; i < pts.size(); i++) {
                FlightPoint a = pts.get(i - 1);
                FlightPoint b = pts.get(i);
                boolean same = Math.abs(a.x() - b.x()) < STILL_EPSILON && Math.abs(a.y() - b.y()) < STILL_EPSILON && Math.abs(a.z() - b.z()) < STILL_EPSILON;
                still = same && (kind == FlightKind.VEHICLE || b.onGround()) ? still + 1 : 0;
                if (still >= SETTLE_TICKS) {
                    if (b.tick() < endTick) {
                        endTick = b.tick();
                        reason = "settled";
                    }
                    break;
                }
            }
        }
        if (reason == null && endedFrozen) {
            // the run ended while it was frozen (a frozen entity never holds a run open): the path stops where it stopped ticking
            endTick = frozenEnd;
            reason = "frozen: " + frozenKind;
        }
        if (reason == null) {
            FlightPoint last = pts.getLast();
            if (last.removed()) {
                endTick = last.tick();
                reason = removal(kind, "removed DISCARDED");
            } else {
                endTick = last.tick();
                reason = limitHit ? "limit" : "settled";
            }
        }
        // cut after the end tick (a frozen end: everything drawn, the end is the last drawn point; the end tick is the run's last)
        List<FlightPoint> cut = new ArrayList<>();
        for (FlightPoint p : pts) {
            if (p.tick() <= endTick) {
                cut.add(p);
            }
        }
        if (cut.isEmpty()) {
            cut.add(pts.getFirst());
        }
        long[] ticks = new long[cut.size()];
        double[] xyz = new double[cut.size() * 3];
        double[] vel = new double[cut.size() * 3];
        double[] box = new double[cut.size() * 6];
        int[] fuse = new int[cut.size()];
        for (int i = 0; i < cut.size(); i++) {
            FlightPoint p = cut.get(i);
            ticks[i] = p.tick();
            xyz[3 * i] = p.x();
            xyz[3 * i + 1] = p.y();
            xyz[3 * i + 2] = p.z();
            vel[3 * i] = p.vx();
            vel[3 * i + 1] = p.vy();
            vel[3 * i + 2] = p.vz();
            AABB b = p.box();
            box[6 * i] = b == null ? p.x() : b.minX;
            box[6 * i + 1] = b == null ? p.y() : b.minY;
            box[6 * i + 2] = b == null ? p.z() : b.minZ;
            box[6 * i + 3] = b == null ? p.x() : b.maxX;
            box[6 * i + 4] = b == null ? p.y() : b.maxY;
            box[6 * i + 5] = b == null ? p.z() : b.maxZ;
            fuse[i] = p.fuse();
        }
        FlightPoint last = cut.getLast();
        PathSnapshot.End end = new PathSnapshot.End(reason, endedFrozen && reason.startsWith("frozen") ? frozenEnd : last.tick(), last.x(), last.y(), last.z());
        int drawn = cut.size();
        List<PathSnapshot.Stasis> kept = new ArrayList<>();
        for (PathSnapshot.Stasis st : stasis) {
            if (st.point() < drawn && st.fromTick() <= endTick) {
                kept.add(st.resume() < drawn ? st : new PathSnapshot.Stasis(st.point(), -1, st.fromTick(), st.toTick(), st.kind(), st.resumed(), st.ticks(), st.vel(), st.pushes()));
            }
        }
        PathSnapshot.Tail tail = reason.equals("limit") && kind == FlightKind.PROJECTILE && live != null
            ? new PathSnapshot.Tail(live.type(), live.nbt().copy(), live.tickCount(), live.xo(), live.yo(), live.zo(), live.onGround()) : null;
        // the first point in a chunk whose entities do not tick in the real world (rerun only: the map is empty for a real recording)
        PathSnapshot.Freeze freeze = null;
        if (!notTicking.isEmpty()) {
            for (int i = 0; i < cut.size(); i++) {
                FlightPoint p = cut.get(i);
                if (p.frozen() != null || isStasisPoint(kept, i)) {
                    continue; // the ghost froze it here too (same levels as the real world): not a prediction beyond the real one
                }
                String why = notTicking.get(net.minecraft.world.level.ChunkPos.pack(net.minecraft.util.Mth.floor(p.x()) >> 4, net.minecraft.util.Mth.floor(p.z()) >> 4));
                if (why != null) {
                    freeze = new PathSnapshot.Freeze(i, p.tick(), why, p.x(), p.y(), p.z());
                    break;
                }
            }
        }
        return new PathSnapshot.Line(cut.getFirst().type(), kind, 1, cut.getFirst().tick(), ticks, xyz, vel, box, fuse, new int[] {cut.getFirst().index()}, end, tail, freeze,
            List.copyOf(kept));
    }

    /**
     * One frozen stretch {@code run} after the drawn points {@code pts} (which it extends): the freeze point is the last drawn point, or the
     * stretch's first point when nothing was drawn yet (an entity tagged while already frozen); if the entity is somewhere else at the end
     * of the stretch, that position becomes a drawn point (the resume point, dashed line). The explosion notes inside the stretch are its
     * pushes.
     */
    private static PathSnapshot.Stasis stasis(List<FlightPoint> pts, List<FlightPoint> run, boolean resumed, List<FlightNote> notes) {
        if (pts.isEmpty()) {
            pts.add(run.getFirst());
        }
        int point = pts.size() - 1;
        FlightPoint at = pts.get(point);
        FlightPoint lastFrozen = run.getLast();
        int resume = -1;
        if (lastFrozen.x() != at.x() || lastFrozen.y() != at.y() || lastFrozen.z() != at.z()) {
            pts.add(lastFrozen);
            resume = pts.size() - 1;
        }
        long[] ticks = new long[run.size()];
        double[] vel = new double[run.size() * 3];
        for (int i = 0; i < run.size(); i++) {
            FlightPoint p = run.get(i);
            ticks[i] = p.tick();
            vel[3 * i] = p.vx();
            vel[3 * i + 1] = p.vy();
            vel[3 * i + 2] = p.vz();
        }
        long from = run.getFirst().tick();
        long to = lastFrozen.tick();
        List<String> pushes = new ArrayList<>();
        for (FlightNote n : notes) {
            if (n.what().startsWith(obsidivision.ghostpath.record.CauseTracker.EXPLOSION_NOTE) && n.tick() >= from && n.tick() <= to) {
                pushes.add(n.what() + " t+" + n.tick());
            }
        }
        return new PathSnapshot.Stasis(point, resume, from, to, run.getFirst().frozen(), resumed, ticks, vel, List.copyOf(pushes));
    }

    private static String removal(FlightKind kind, String what) {
        if (what.contains("UNLOADED")) {
            return "unloaded";
        } else if (what.contains("CHANGED_DIMENSION")) {
            return "left dimension";
        } else if (kind == FlightKind.TNT) {
            return "exploded";
        } else if (kind == FlightKind.FALLING_BLOCK) {
            return "landed";
        }
        return "removed";
    }

    private static boolean isStasisPoint(List<PathSnapshot.Stasis> stasis, int i) {
        for (PathSnapshot.Stasis st : stasis) {
            if (st.point() == i || st.resume() == i) {
                return true;
            }
        }
        return false;
    }

    private static String stasisText(PathSnapshot.Line l) {
        StringBuilder sb = new StringBuilder();
        for (PathSnapshot.Stasis st : l.stasis()) {
            sb.append(st.signature()).append(';');
        }
        return sb.toString();
    }

    /** What makes two paths "identical": same type, start tick, end, and every position of every tick. */
    private static final class Key {
        private final PathSnapshot.Line line;
        private final int hash;

        Key(PathSnapshot.Line line) {
            this.line = line;
            this.hash = java.util.Objects.hash(line.type(), line.startTick(), line.end().reason(), line.end().tick(), Arrays.hashCode(line.ticks()), Arrays.hashCode(line.xyz()),
                line.tail() != null);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key k)) {
                return false;
            }
            PathSnapshot.Line a = this.line;
            PathSnapshot.Line b = k.line;
            return a.type().equals(b.type()) && a.startTick() == b.startTick() && a.end().reason().equals(b.end().reason()) && a.end().tick() == b.end().tick()
                && Arrays.equals(a.ticks(), b.ticks()) && Arrays.equals(a.xyz(), b.xyz()) && (a.tail() != null) == (b.tail() != null)
                && java.util.Objects.equals(a.freeze(), b.freeze()) && stasisText(a).equals(stasisText(b));
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
