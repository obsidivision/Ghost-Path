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
 * @param unseededDrawsInexact unseeded draws made inside a tagged mob's tick ("inexact: mob AI"): the run may not repeat exactly
 * @param weatherAtStart  rain and thunder state when the trigger was pressed (ruling: for the ghost)
 * @param weatherAtEnd    the same at the end of the recording (before the restore; weather is not restored)
 * @param chunksReloaded  chunks that unloaded during the recording and were loaded again for the restore
 * @param entitiesNotRestored spawned or changed entities left as they were because their chunk could not be loaded in time
 * @param itemDriftTicks  item ticks counted as "position unchanged" although it moved by less than 1e-6 (settle rule)
 * @param sharedRestored  positions written by both chain and non-chain work (restored)
 * @param nonChainBlocksLeft positions changed only outside the chain during the recording, left in place
 * @param nonChainEntitiesLeft entities spawned or changed only outside the chain, left in place
 * @param clocksAtStart   the world clocks (day time) when the trigger was pressed (step 3: the ghost starts from them)
 * @param entityTrace     every chain entity's full state after each of its ticks (step 3)
 * @param readFirstTicks  the relative tick at which each read position was first read
 * @param triggerStateAtStart the trigger's block state just before it was pressed (step 4 ruling: a rerun presses the same press)
 * @param flightPoints    every chain entity's position per tick, as numbers (step 5)
 * @param flightNotes     hits and removals of chain entities (step 5)
 * @param liveProjectiles chain projectiles still flying when the recording ended (tick limit), saved for the client's continuation (step 5)
 * @param notSimulated   things the ghost cannot imitate, as text ("player-owned pearl: chunk loading not simulated")
 * @param notFollowed     chain things lost or not followed (unloaded with their chunk, left the dimension, vibration to
 *                        an entity listener), as event text with tick and position
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
    int unseededDrawsInexact,
    Set<BlockPos> lateBlockEntities,
    int ticksNotRestored,
    Weather weatherAtStart,
    Weather weatherAtEnd,
    int chunksReloaded,
    int entitiesNotRestored,
    int itemDriftTicks,
    int sharedRestored,
    int nonChainBlocksLeft,
    int nonChainEntitiesLeft,
    List<String> notFollowed,
    net.minecraft.world.clock.PackedClockStates clocksAtStart,
    List<CauseTracker.EntityTrace> entityTrace,
    Map<BlockPos, Long> readFirstTicks,
    net.minecraft.world.level.block.state.BlockState triggerStateAtStart,
    List<CauseTracker.FlightPoint> flightPoints,
    List<CauseTracker.FlightNote> flightNotes,
    List<CauseTracker.LiveEntity> liveProjectiles,
    List<String> notSimulated
) {
    static Recording of(CauseTracker tracker, UndoLog undo, BlockPos trigger, int ticks, int limit, boolean limitHit,
                        CauseTracker.Pending pendingAtEnd, boolean kick, long seed, long startGameTime, Weather weatherAtStart,
                        Weather weatherAtEnd, int chunksReloaded, net.minecraft.world.clock.PackedClockStates clocksAtStart,
                        net.minecraft.world.level.block.state.BlockState triggerStateAtStart,
                        List<CauseTracker.LiveEntity> liveProjectiles) {
        int notAllowed = 0;
        int allowed = 0;
        int inexact = 0;
        for (ChainEvent e : tracker.events()) {
            if (e.kind() == ChainEvent.Kind.UNSEEDED_DRAW) {
                if (e.detail().contains("WARNING: not allowed")) {
                    notAllowed++;
                } else if (e.detail().endsWith(CauseTracker.INEXACT_MOB_AI) || e.detail().endsWith(CauseTracker.INEXACT_MOB)) {
                    inexact++;
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
            notAllowed, allowed, inexact,
            Set.copyOf(undo.lateBlockEntities()),
            undo.ticksNotRestored(),
            weatherAtStart, weatherAtEnd, chunksReloaded, undo.entitiesNotRestored(), tracker.itemDriftTicks(),
            undo.sharedRestored(), undo.nonChainBlocksLeft(), undo.nonChainEntitiesLeft(),
            tracker.notFollowed().stream().map(ChainEvent::toString).toList(),
            clocksAtStart, List.copyOf(tracker.entityTrace()), Map.copyOf(tracker.readFirstTicks()), triggerStateAtStart,
            List.copyOf(tracker.flightPoints()), List.copyOf(tracker.flightNotes()), List.copyOf(liveProjectiles), tracker.notSimulated());
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
            + (this.unseededDrawsAllowed > 0 ? " (+" + this.unseededDrawsAllowed + " allowed)" : "")
            + (this.unseededDrawsInexact > 0 ? " (+" + this.unseededDrawsInexact + " inexact: mob)" : ""));
        out.add("Late block entities: " + this.lateBlockEntities.size()
            + (this.ticksNotRestored > 0 ? "; scheduled ticks not restored: " + this.ticksNotRestored : "")
            + (this.chunksReloaded > 0 ? "; chunks loaded again for the restore: " + this.chunksReloaded : "")
            + (this.entitiesNotRestored > 0 ? "; entities not restored (chunk not loaded): " + this.entitiesNotRestored : ""));
        out.add("Restore: chain changes only; shared positions restored: " + this.sharedRestored + "; non-chain changes left in place: "
            + this.nonChainBlocksLeft + " positions, " + this.nonChainEntitiesLeft + " entities");
        out.add("Weather: " + this.weatherAtStart + " at start" + (this.weatherAtEnd.toString().equals(this.weatherAtStart.toString()) ? "" : ", " + this.weatherAtEnd + " at end"));
        if (this.unseededDrawsInexact > 0) {
            out.add("Inexact: a mob (its AI, or its own random) drew unseeded numbers, so the result may not repeat exactly");
        }
        if (!this.notSimulated.isEmpty()) {
            out.add("Not simulated: " + String.join("; ", this.notSimulated));
        }
        if (!this.notFollowed.isEmpty()) {
            // they count as gone for pending(), but the summary names each one (ruling, gaps 2, 7, 8)
            out.add("Not followed: " + this.notFollowed.size() + " - " + String.join("; ", this.notFollowed));
        }
        return out;
    }
}
