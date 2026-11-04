package obsidivision.ghostpath.watch;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.ChainEvent;
import obsidivision.ghostpath.record.Recording;

/**
 * What a live rerun in the ghost predicts (build step 4): every chain entity's full state after each of its ticks
 * ({@code trace}, the same records the ghost check compares), the chain event log, and the new footprint, which replaces
 * the watched one. Memory only.
 *
 * @param ticks           ghost ticks run
 * @param limitHit        stopped at the tick limit with chain work still pending
 * @param tntSpawned      primed TNT the chain added to the ghost
 * @param tntSpawnTicks   relative tick at which each of them spawned, in order of appearance
 * @param events          the chain event log of the ghost run
 * @param trace           every chain entity's state after each of its ticks (tick, phase, index, type, state text)
 * @param footprint       the new footprint
 * @param pendingAtEnd    chain work pending at the end (all zero unless the limit was hit)
 * @param copiedMin       the chunk rectangle that was copied (min corner)
 * @param copiedMax       the chunk rectangle that was copied (max corner)
 * @param widened         the first run reached beyond the copied chunks, so this one copied a larger area
 * @param beyond          chunks of the footprint outside the copied rectangle, if any are left (could not be widened)
 * @param gaps            gaps the ghost hit (border, POI, players, lookup by key)
 * @param serverTicks     server ticks the run was spread over
 * @param millis          server-thread time the run took in total
 * @param copyMillis      of which the copy (the one slice that cannot be split: one consistent moment of the real world)
 * @param createMillis    of which creating the (empty) ghost level; not budgeted, logged in the chat line (ruling 4 after step 4)
 * @param triggerNote     the real trigger is not in the state the recording found it in: "real lever is on; prediction is for off → on"; or null
 * @param flightPoints    every chain entity's position per tick, as numbers (step 5)
 * @param flightNotes     hits and removals of chain entities (step 5)
 * @param liveProjectiles chain projectiles still flying when the run stopped at the tick limit (step 5)
 * @param maxSliceMillis  the longest slice of ghost ticks, copy excluded (the budget is checked after each ghost tick)
 * @param notTicking      copied chunks the real world does not tick entities in: chunk key to "unloaded" or "lazy" (see GhostCopy.Prepared#realNotTicking)
 */
public record Prediction(int ticks, boolean limitHit, int tntSpawned, List<Integer> tntSpawnTicks, List<ChainEvent> events,
                         List<CauseTracker.EntityTrace> trace, Footprint footprint, CauseTracker.Pending pendingAtEnd, ChunkPos copiedMin,
                         ChunkPos copiedMax, boolean widened, List<ChunkPos> beyond, List<String> gaps, int serverTicks, long millis, long copyMillis,
                         long maxSliceMillis, long createMillis, String triggerNote, List<CauseTracker.FlightPoint> flightPoints,
                         List<CauseTracker.FlightNote> flightNotes, List<CauseTracker.LiveEntity> liveProjectiles, Map<Long, String> notTicking) {

    /** The chain entity states of one tick, in the order the ghost ticked them. */
    public List<CauseTracker.EntityTrace> statesAt(long tick) {
        return this.trace.stream().filter(t -> t.tick() == tick).toList();
    }

    /** TNT spawn ticks of a list of chain events: the ENTITY_TAGGED event of each TNT that went on to tick. */
    public static List<Integer> tntSpawnTicks(List<ChainEvent> events, List<CauseTracker.EntityTrace> trace) {
        java.util.Set<Integer> ticked = new java.util.HashSet<>();
        for (CauseTracker.EntityTrace t : trace) {
            ticked.add(t.index());
        }
        List<Integer> out = new ArrayList<>();
        Pattern p = Pattern.compile("^#(\\d+) minecraft:tnt ");
        for (ChainEvent e : events) {
            if (e.kind() == ChainEvent.Kind.ENTITY_TAGGED) {
                Matcher m = p.matcher(e.detail());
                if (m.find() && ticked.contains(Integer.parseInt(m.group(1)))) {
                    out.add((int) e.tick());
                }
            }
        }
        return out;
    }

    /** One chain entity, as far as a "changed since last" line needs it. */
    record EntitySummary(String type, long spawnTick, long lastTick, String endPos, boolean removed) {
    }

    /**
     * What two runs of one machine are compared by: ticks, each chain entity's spawn tick, last tick and end position, and
     * the footprint's positions.
     */
    public record Summary(int ticks, int tntSpawned, Map<Integer, EntitySummary> entities, LongOpenHashSet positions) {
        private static final Pattern STATE = Pattern.compile("^#(\\d+) (\\S+).*? pos=\\(([^)]*)\\) v=\\([^)]*\\)( removed)?$");

        public static Summary of(Recording rec) {
            return build(rec.ticks(), rec.tntSpawned(), rec.events(), rec.footprint());
        }

        public static Summary of(Prediction p) {
            return build(p.ticks(), p.tntSpawned(), p.events(), p.footprint().positions());
        }

        private static Summary build(int ticks, int tnt, List<ChainEvent> events, java.util.Collection<BlockPos> footprint) {
            Map<Integer, long[]> spawn = new LinkedHashMap<>();
            Map<Integer, EntitySummary> out = new LinkedHashMap<>();
            for (ChainEvent e : events) {
                if (e.kind() == ChainEvent.Kind.ENTITY_TAGGED) {
                    int hash = e.detail().indexOf(' ');
                    if (e.detail().startsWith("#") && hash > 1) {
                        spawn.putIfAbsent(Integer.parseInt(e.detail().substring(1, hash)), new long[] {e.tick()});
                    }
                } else if (e.kind() == ChainEvent.Kind.ENTITY_STATE) {
                    Matcher m = STATE.matcher(e.detail());
                    if (m.find()) {
                        int index = Integer.parseInt(m.group(1));
                        long first = spawn.containsKey(index) ? spawn.get(index)[0] : e.tick();
                        out.put(index, new EntitySummary(m.group(2), first, e.tick(), round(m.group(3)), m.group(4) != null));
                    }
                }
            }
            LongOpenHashSet keys = new LongOpenHashSet();
            for (BlockPos p : footprint) {
                keys.add(p.asLong());
            }
            return new Summary(ticks, tnt, out, keys);
        }

        private static String round(String xyz) {
            StringBuilder sb = new StringBuilder("(");
            String[] parts = xyz.split(",\\s*");
            for (int i = 0; i < parts.length; i++) {
                try {
                    sb.append(i == 0 ? "" : ", ").append(String.format(java.util.Locale.ROOT, "%.2f", Double.parseDouble(parts[i])));
                } catch (NumberFormatException ex) {
                    sb.append(parts[i]);
                }
            }
            return sb.append(")").toString();
        }

        /** What differs from {@code before}, as short phrases (empty if nothing). */
        public List<String> changesSince(Summary before) {
            List<String> out = new ArrayList<>();
            if (this.ticks != before.ticks) {
                out.add("ticks " + before.ticks + " -> " + this.ticks);
            }
            if (this.tntSpawned != before.tntSpawned) {
                out.add("TNT " + before.tntSpawned + " -> " + this.tntSpawned);
            }
            for (int index : new TreeSet<>(union(before.entities.keySet(), this.entities.keySet()))) {
                EntitySummary a = before.entities.get(index);
                EntitySummary b = this.entities.get(index);
                if (a == null) {
                    out.add(name(b.type) + " #" + index + " new, spawns t+" + b.spawnTick);
                } else if (b == null) {
                    out.add(name(a.type) + " #" + index + " gone (spawned t+" + a.spawnTick + ")");
                } else if (!a.type.equals(b.type)) {
                    out.add("#" + index + " " + name(a.type) + " -> " + name(b.type));
                } else if (a.spawnTick != b.spawnTick) {
                    out.add(name(b.type) + " #" + index + " spawn t+" + a.spawnTick + " -> t+" + b.spawnTick);
                } else if (a.lastTick != b.lastTick || !a.endPos.equals(b.endPos) || a.removed != b.removed) {
                    out.add(name(b.type) + " #" + index + " ends t+" + a.lastTick + " " + a.endPos + " -> t+" + b.lastTick + " " + b.endPos);
                }
            }
            int added = 0;
            int removed = 0;
            for (long k : this.positions) {
                if (!before.positions.contains(k)) {
                    added++;
                }
            }
            for (long k : before.positions) {
                if (!this.positions.contains(k)) {
                    removed++;
                }
            }
            if (added + removed > 0) {
                out.add("footprint +" + added + "/-" + removed + " positions");
            }
            return out;
        }

        private static java.util.Set<Integer> union(java.util.Set<Integer> a, java.util.Set<Integer> b) {
            java.util.Set<Integer> u = new java.util.HashSet<>(a);
            u.addAll(b);
            return u;
        }

        private static String name(String type) {
            String n = type.startsWith("minecraft:") ? type.substring(10) : type;
            return n.equals("tnt") ? "TNT" : n;
        }
    }
}
