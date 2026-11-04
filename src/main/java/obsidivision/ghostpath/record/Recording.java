package obsidivision.ghostpath.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * The finished result of one {@code /path machine} run (build step 1d): everything step 2 needs to copy and later check
 * the ghost against, held in memory only. Immutable copies; nothing here refers to the world.
 *
 * @param dimension       where it ran
 * @param trigger         the lever or button that was pressed
 * @param seed            the chain random seed
 * @param kickApplied     whether the TNT kick was applied ({@code /path kick})
 * @param startGameTime   the level's game time when the trigger was pressed
 * @param ticks           game ticks the recording ran
 * @param limit           the tick limit it ran under
 * @param limitHit        true if it stopped at the limit with chain work still pending
 * @param pendingAtEnd    what was still pending at the end (all zero unless the limit was hit)
 * @param events          the chain event log
 * @param writes          positions written by chain work
 * @param reads           positions read by chain work, with how
 * @param entityAreas     boxes queried for entities by chain work
 * @param heightmapColumns columns whose heightmap chain work read
 * @param tntSpawned      primed TNT added to the level by chain work
 * @param unseededDraws   chain draws from an unseeded random, by call site, with {@code allowed} or not
 * @param lateBlockEntities block entities that were changed before the undo log saw them (not restored exactly)
 * @param ticksNotRestored begin-time scheduled ticks the undo log could not put back
 */
public record Recording(
    ResourceKey<Level> dimension,
    BlockPos trigger,
    long seed,
    boolean kickApplied,
    long startGameTime,
    int ticks,
    int limit,
    boolean limitHit,
    CauseTracker.Pending pendingAtEnd,
    List<ChainEvent> events,
    Set<BlockPos> writes,
    Map<BlockPos, Set<CauseTracker.ReadKind>> reads,
    Set<AABB> entityAreas,
    Set<CauseTracker.Column> heightmapColumns,
    int tntSpawned,
    int unseededDrawsNotAllowed,
    int unseededDrawsAllowed,
    Set<BlockPos> lateBlockEntities,
    int ticksNotRestored
) {
    static Recording of(CauseTracker tracker, UndoLog undo, BlockPos trigger, int ticks, int limit, boolean limitHit,
                        CauseTracker.Pending pendingAtEnd, boolean kick, long seed, long startGameTime) {
        int notAllowed = 0;
        int allowed = 0;
        for (ChainEvent e : tracker.events()) {
            if (e.kind() == ChainEvent.Kind.UNSEEDED_DRAW) {
                if (e.detail().contains("WARNING: not allowed")) {
                    notAllowed++;
                } else {
                    allowed++;
                }
            }
        }
        Map<BlockPos, Set<CauseTracker.ReadKind>> reads = new LinkedHashMap<>();
        tracker.reads().forEach((pos, kinds) -> reads.put(pos, Set.copyOf(kinds)));
        return new Recording(
            tracker.level().dimension(), trigger.immutable(), seed, kick, startGameTime, ticks, limit, limitHit, pendingAtEnd,
            List.copyOf(new ArrayList<>(tracker.events())),
            Set.copyOf(new LinkedHashSet<>(tracker.writes())),
            Map.copyOf(reads),
            Set.copyOf(new LinkedHashSet<>(tracker.entityAreas())),
            Set.copyOf(new LinkedHashSet<>(tracker.heightmapColumns())),
            tracker.addedChainEntities(net.minecraft.world.entity.EntityTypes.TNT),
            notAllowed, allowed,
            Set.copyOf(undo.lateBlockEntities()),
            undo.ticksNotRestored());
    }

    /** Block positions read or written: the footprint without entity areas. */
    public Set<BlockPos> footprint() {
        Set<BlockPos> all = new LinkedHashSet<>(this.writes);
        all.addAll(this.reads.keySet());
        return all;
    }

    /** The summary the command prints: one line per entry. */
    public List<String> summary() {
        List<String> out = new ArrayList<>();
        out.add("Recorded " + this.trigger.toShortString() + ": " + this.ticks + " ticks"
            + (this.limitHit ? " - STOPPED AT THE TICK LIMIT (" + this.limit + "), chain work still pending: " + this.pendingAtEnd : "")
            + ", kick " + (this.kickApplied ? "on" : "off"));
        out.add("Footprint: " + this.writes.size() + " written / " + this.reads.size() + " read / " + this.entityAreas.size()
            + " entity areas (" + this.footprint().size() + " block positions; light reads "
            + this.reads.values().stream().filter(k -> k.contains(CauseTracker.ReadKind.LIGHT)).count()
            + ", heightmap columns " + this.heightmapColumns.size() + ")");
        out.add("TNT spawned: " + this.tntSpawned);
        out.add("Unseeded draws: " + this.unseededDrawsNotAllowed + " warnings"
            + (this.unseededDrawsAllowed > 0 ? " (+" + this.unseededDrawsAllowed + " allowed)" : ""));
        out.add("Late block entities: " + this.lateBlockEntities.size()
            + (this.ticksNotRestored > 0 ? "; scheduled ticks not restored: " + this.ticksNotRestored : ""));
        return out;
    }
}
