package obsidivision.ghostpath.ghost;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.CauseTracker.ReadKind;
import obsidivision.ghostpath.record.ChainEvent;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step 3: the ghost against the real run. After {@code /path machine} recorded and restored the real world, the
 * machine (the footprint's chunks, with a margin) is copied into a fresh ghost, started from the weather and the world
 * clocks of the recording's start; the same trigger is pressed there with the same seed (and the same kick setting), and
 * the ghost is ticked as many times as the recording ran. Then everything is compared:
 * <ol>
 * <li>the chain event logs, in order (tick, kind, position, detail, and the phase of the level tick);</li>
 * <li>every chain entity's full state after each of its ticks (saved data without UUID, plus the transient state);</li>
 * <li>the footprint: positions read (all kinds but light), written, the boxes queried for entities, the heightmap
 * columns, and whether light was read at all (light is compared for presence only: which positions get read for light
 * varies with the asynchronous light engine, DESIGN step 1d); the chain work still pending at the end.</li>
 * </ol>
 * The first difference is reported with its tick and phase, both values, and the gaps (things the ghost does not copy,
 * ruling 3 of step 3) and unseeded draws logged within a tick of it.
 *
 * <p>Runs synchronously on the server thread (copying waits for the ghost's own chunk work, up to 20 s real time).
 */
public final class GhostCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger("ghostpath");
    private static volatile @Nullable Result latest;

    private GhostCheck() {
    }

    public static @Nullable Result latest() {
        return latest;
    }

    /** Test hooks. */
    public static final class Options {
        /** Called with the ghost right after the copy, before the trigger is pressed (a test changes a block here). */
        public @Nullable Consumer<GhostLevel> afterCopy;
        /** Called with the ghost after each of its ticks (a test samples the ghost's entities here). */
        public @Nullable Consumer<GhostLevel> afterEachTick;
        /** Called with the ghost after its ticks, before it is discarded (a test inspects the ghost level here). */
        public @Nullable Consumer<GhostLevel> beforeDiscard;
        /** Test hook: breaks the ghost's data on purpose before the compare, to prove that the compare notices. */
        public java.util.function.@Nullable UnaryOperator<GhostRun> tamper;
        /** Test hook: seed for the ghost's chain random instead of the recording's. */
        public @Nullable Long seedOverride;
        /** Test hook: TNT kick setting for the ghost instead of the recording's. */
        public @Nullable Boolean kickOverride;
        /**
         * Test hook (break-it): the ghost keeps the real game time of the copy instead of the recording's start, and the
         * copied ticks' trigger ticks are not shifted. Never set outside tests.
         */
        public boolean leaveGameTimeOffsetOut;
    }

    /**
     * What the ghost run produced.
     *
     * @param events the ghost's chain event log
     */
    public record GhostRun(List<ChainEvent> events, List<CauseTracker.EntityTrace> trace, Set<BlockPos> writes,
                           Map<BlockPos, Set<ReadKind>> reads, Map<BlockPos, Long> readFirstTicks, Set<AABB> entityAreas,
                           Set<CauseTracker.Column> heightmapColumns, CauseTracker.Pending pending, List<CauseTracker.Gap> gaps, int ticks) {
    }

    /**
     * The first difference.
     *
     * @param tick    relative tick (t+N)
     * @param phase   the phase of the level tick (trigger, block ticks, fluid ticks, block events, entities, block entities)
     * @param what    what differs ("WRITE", "entity #0 minecraft:tnt state", "footprint read", ...)
     * @param where   position or entity, as text ("" if none)
     * @param real    the real value
     * @param ghost   the ghost value
     * @param gaps    gaps the ghost run hit within a tick of it
     * @param unseeded unseeded draws logged within a tick of it, by either run
     */
    public record Difference(long tick, String phase, String what, String where, String real, String ghost, List<String> gaps,
                             List<String> unseeded) {
        public String headline() {
            return "first difference at t+" + this.tick + ", phase " + this.phase + ", " + this.what + (this.where.isEmpty() ? "" : " " + this.where)
                + ": real " + this.real + ", ghost " + this.ghost;
        }
    }

    /**
     * @param ran        false if the ghost could not be built or run; {@code note} says why
     * @param exact      ran and nothing differs
     * @param first      the first difference, if any
     * @param lines      what the command prints
     * @param ghostRun   the ghost's data (null if it did not run)
     */
    public record Result(boolean ran, boolean exact, @Nullable Difference first, String note, List<String> lines, @Nullable GhostRun ghostRun,
                         int eventsCompared, int statesCompared, int differences) {
    }

    public static Result run(ServerLevel real, Recording rec) {
        return run(real, rec, new Options());
    }

    /** Runs the check; never throws (a failure is a "not run" result). */
    public static Result run(ServerLevel real, Recording rec, Options options) {
        obsidivision.ghostpath.watch.LiveWatch.preempt(); // one ghost at a time
        Result result;
        try {
            result = runChecked(real, rec, options);
        } catch (RuntimeException | IOException e) {
            LOGGER.error("[ghostpath] ghost check failed", e);
            result = notRun("error: " + e);
        }
        latest = result;
        return result;
    }

    private static Result notRun(String why) {
        return new Result(false, false, null, why, List.of("Ghost check: not run: " + why), null, 0, 0, 0);
    }

    // ---- the ghost run ----

    private static Result runChecked(ServerLevel real, Recording rec, Options options) throws IOException {
        if (!rec.dimension().equals(real.dimension())) {
            return notRun("the recording is from another dimension");
        }
        ChunkPos[] rect = chooseRectangle(real, rec);
        if (rect == null) {
            return notRun("a chunk of the machine is not loaded (FULL, with entities), so it cannot be copied");
        }
        GhostLevel.resetStrayPackets();
        GhostLevel ghost = GhostLevel.create(real);
        GhostCopy.copy(real, ghost, rect[0], rect[1], new GhostCopy.Start(rec.weatherAtStart(), rec.clocksAtStart(),
            options.leaveGameTimeOffsetOut ? null : rec.startGameTime()));
        GhostRun run;
        try {
            if (options.afterCopy != null) {
                options.afterCopy.accept(ghost);
            }
            CauseTracker tracker = CauseTracker.begin(ghost, options.seedOverride != null ? options.seedOverride : rec.seed(),
                options.kickOverride != null ? options.kickOverride : rec.kickApplied());
            try {
                tracker.enableEntityTrace();
                BlockPos trigger = rec.trigger();
                GhostLevel.runWork(() -> tracker.runAsChain(() -> MachineRecorder.press(ghost, trigger, ghost.getBlockState(trigger))));
                for (int i = 0; i < rec.ticks(); i++) {
                    ghost.tickGhost();
                    if (options.afterEachTick != null) {
                        options.afterEachTick.accept(ghost);
                    }
                }
                run = new GhostRun(List.copyOf(tracker.events()), List.copyOf(tracker.entityTrace()), Set.copyOf(tracker.writes()),
                    copyReads(tracker.reads()), Map.copyOf(tracker.readFirstTicks()), Set.copyOf(tracker.entityAreas()),
                    Set.copyOf(tracker.heightmapColumns()), tracker.pending(), List.copyOf(tracker.gaps()), rec.ticks());
                if (options.beforeDiscard != null) {
                    options.beforeDiscard.accept(ghost);
                }
            } finally {
                tracker.end();
            }
        } finally {
            ghost.discard();
        }
        if (options.tamper != null) {
            run = options.tamper.apply(run);
        }
        return compare(rec, run, rect);
    }

    private static Map<BlockPos, Set<ReadKind>> copyReads(Map<BlockPos, Set<ReadKind>> reads) {
        Map<BlockPos, Set<ReadKind>> out = new java.util.HashMap<>();
        reads.forEach((pos, kinds) -> out.put(pos, Set.copyOf(kinds)));
        return out;
    }

    /**
     * The chunk rectangle to copy: every chunk of the footprint and the heightmap columns, plus (if all of those chunks
     * are loaded) the chunks of the entity-query boxes and a one-chunk margin; falls back to less. Null if even the
     * footprint's own rectangle has a chunk that is not loaded.
     */
    private static ChunkPos @Nullable [] chooseRectangle(ServerLevel real, Recording rec) {
        return chooseRectangle(real, rec.footprint(), rec.heightmapColumns(), rec.entityAreas(), null);
    }

    /**
     * The same choice for any footprint (the live rerun uses it with the footprint of its last run). {@code mustInclude}
     * (chunk x min, z min, x max, z max) is a rectangle the result has to cover as well (a rerun that grows its area keeps
     * what it had).
     */
    public static ChunkPos @Nullable [] chooseRectangle(ServerLevel real, java.util.Collection<BlockPos> footprint,
                                                         Set<CauseTracker.Column> columns, Set<AABB> areas, int @Nullable [] mustInclude) {
        return chooseRectangle(real, footprint, columns, areas, mustInclude, 1);
    }

    /**
     * As above with a margin of up to {@code margin} chunks around the footprint (the largest that is entirely loaded,
     * then smaller ones, then none). A live rerun that found the chain reaching beyond its area widens with a margin of
     * several chunks, because the first run's footprint stops where the copied area stops.
     */
    public static ChunkPos @Nullable [] chooseRectangle(ServerLevel real, java.util.Collection<BlockPos> footprint,
                                                         Set<CauseTracker.Column> columns, Set<AABB> areas, int @Nullable [] mustInclude, int margin) {
        return chooseRectangle(real, footprint, columns, areas, mustInclude, margin, false);
    }

    /**
     * With {@code disk}, a margin of {@code margin} chunks is taken whether or not those chunks are loaded (the ones that are not are
     * copied from the save, {@code GhostCopy.prepare} with {@code allowDisk}); only the footprint's own chunks and the entity areas
     * have to be loaded. A rerun that reaches beyond its copied area widens like this.
     */
    public static ChunkPos @Nullable [] chooseRectangle(ServerLevel real, java.util.Collection<BlockPos> footprint,
                                                         Set<CauseTracker.Column> columns, Set<AABB> areas, int @Nullable [] mustInclude, int margin, boolean disk) {
        int[] core = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (BlockPos p : footprint) {
            include(core, p.getX() >> 4, p.getZ() >> 4);
        }
        for (CauseTracker.Column c : columns) {
            include(core, c.x() >> 4, c.z() >> 4);
        }
        if (mustInclude != null) {
            include(core, mustInclude[0], mustInclude[1]);
            include(core, mustInclude[2], mustInclude[3]);
        }
        if (core[0] == Integer.MAX_VALUE) {
            return null;
        }
        int[] withAreas = core.clone();
        for (AABB box : areas) {
            include(withAreas, BlockPos.containing(box.minX, box.minY, box.minZ).getX() >> 4, BlockPos.containing(box.minX, box.minY, box.minZ).getZ() >> 4);
            include(withAreas, BlockPos.containing(box.maxX, box.maxY, box.maxZ).getX() >> 4, BlockPos.containing(box.maxX, box.maxY, box.maxZ).getZ() >> 4);
        }
        List<int[]> candidates = new ArrayList<>();
        if (disk && margin > 1) {
            candidates.add(grow(withAreas, margin));
        }
        if (margin > 1) {
            candidates.add(growLoaded(real, withAreas, margin));
        }
        candidates.add(grow(withAreas, 1));
        candidates.add(withAreas);
        candidates.add(grow(core, 1));
        candidates.add(core);
        for (int[] c : candidates) {
            // a disk candidate (the first one) only has to have its core loaded: the rest comes from the save
            if (allLoaded(real, c) || disk && c == candidates.get(0) && margin > 1 && allLoaded(real, core)) {
                return new ChunkPos[] {new ChunkPos(c[0], c[1]), new ChunkPos(c[2], c[3])};
            }
        }
        return null;
    }

    /**
     * {@code r} grown by up to {@code margin} chunks on each side, each side as far as the chunks it adds are loaded
     * (FULL, with entities). {@code r} itself must be loaded, else it is returned as it is (the caller's check fails).
     */
    private static int[] growLoaded(ServerLevel real, int[] r, int margin) {
        int[] out = r.clone();
        if (!allLoaded(real, out)) {
            return out;
        }
        for (int step = 0; step < margin; step++) {
            for (int side = 0; side < 4; side++) {
                // one chunk on this side, if the whole strip it adds is loaded; the sides are extended one after the other
                int[] strip = switch (side) {
                    case 0 -> new int[] {out[0] - 1, out[1], out[0] - 1, out[3]};
                    case 1 -> new int[] {out[2] + 1, out[1], out[2] + 1, out[3]};
                    case 2 -> new int[] {out[0], out[1] - 1, out[2], out[1] - 1};
                    default -> new int[] {out[0], out[3] + 1, out[2], out[3] + 1};
                };
                if (allLoaded(real, strip)) {
                    switch (side) {
                        case 0 -> out[0]--;
                        case 1 -> out[2]++;
                        case 2 -> out[1]--;
                        default -> out[3]++;
                    }
                }
            }
        }
        return out;
    }

    private static void include(int[] r, int x, int z) {
        r[0] = Math.min(r[0], x);
        r[1] = Math.min(r[1], z);
        r[2] = Math.max(r[2], x);
        r[3] = Math.max(r[3], z);
    }

    private static int[] grow(int[] r, int by) {
        return new int[] {r[0] - by, r[1] - by, r[2] + by, r[3] + by};
    }

    private static boolean allLoaded(ServerLevel real, int[] r) {
        for (int x = r[0]; x <= r[2]; x++) {
            for (int z = r[1]; z <= r[3]; z++) {
                if (real.getChunkSource().getChunkNow(x, z) == null || !real.areEntitiesLoaded(ChunkPos.pack(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- the comparison ----

    private static Result compare(Recording rec, GhostRun ghost, ChunkPos[] rect) {
        List<ChainEvent> re = rec.events();
        List<ChainEvent> ge = ghost.events();
        int eventDiff = -1;
        for (int i = 0; i < Math.max(re.size(), ge.size()); i++) {
            ChainEvent a = i < re.size() ? re.get(i) : null;
            ChainEvent b = i < ge.size() ? ge.get(i) : null;
            if (a == null || b == null || !a.toString().equals(b.toString()) || !a.phase().equals(b.phase())) {
                eventDiff = i;
                break;
            }
        }
        List<CauseTracker.EntityTrace> rt = rec.entityTrace();
        List<CauseTracker.EntityTrace> gt = ghost.trace();
        int traceDiff = -1;
        for (int i = 0; i < Math.max(rt.size(), gt.size()); i++) {
            CauseTracker.EntityTrace a = i < rt.size() ? rt.get(i) : null;
            CauseTracker.EntityTrace b = i < gt.size() ? gt.get(i) : null;
            if (a == null || b == null || a.seq() != b.seq() || a.index() != b.index() || !a.type().equals(b.type()) || !a.state().equals(b.state())) {
                traceDiff = i;
                break;
            }
        }
        int differences = 0;
        Difference first = null;
        // the trace entry with seq s was taken after s events, so it comes before event s
        int traceSeq = Integer.MAX_VALUE;
        if (traceDiff >= 0) {
            CauseTracker.EntityTrace a = traceDiff < rt.size() ? rt.get(traceDiff) : null;
            CauseTracker.EntityTrace b = traceDiff < gt.size() ? gt.get(traceDiff) : null;
            traceSeq = Math.min(a != null ? a.seq() : Integer.MAX_VALUE, b != null ? b.seq() : Integer.MAX_VALUE);
        }
        if (eventDiff >= 0) {
            differences++;
        }
        if (traceDiff >= 0) {
            differences++;
        }
        if (eventDiff >= 0 && (traceDiff < 0 || eventDiff < traceSeq)) {
            ChainEvent a = eventDiff < re.size() ? re.get(eventDiff) : null;
            ChainEvent b = eventDiff < ge.size() ? ge.get(eventDiff) : null;
            ChainEvent at = a != null ? a : b;
            first = difference(rec, ghost, at.tick(), at.phase(), a != null ? a.kind().name() : b.kind().name(),
                at.pos() == null ? "" : at.pos().toShortString(), a == null ? "<no such event>" : a.toString(),
                b == null ? "<no such event>" : b.toString());
        } else if (traceDiff >= 0) {
            CauseTracker.EntityTrace a = traceDiff < rt.size() ? rt.get(traceDiff) : null;
            CauseTracker.EntityTrace b = traceDiff < gt.size() ? gt.get(traceDiff) : null;
            CauseTracker.EntityTrace at = a != null ? a : b;
            String[] excerpt = a != null && b != null ? excerpts(a.state(), b.state()) : new String[] {a == null ? "<no such state>" : a.state(), b == null ? "<no such state>" : b.state()};
            first = difference(rec, ghost, at.tick(), at.phase(), "entity state", "#" + at.index() + " " + at.type(), excerpt[0], excerpt[1]);
        }
        // footprint (only when the logs are identical: otherwise it follows from them)
        if (first == null) {
            List<Difference> footprint = footprintDifferences(rec, ghost);
            differences += footprint.size();
            if (!footprint.isEmpty()) {
                first = footprint.getFirst();
            }
        } else {
            differences += footprintDifferences(rec, ghost).size();
        }
        List<String> lines = new ArrayList<>();
        if (first == null) {
            lines.add("Ghost check: exact");
            lines.add("Compared: " + re.size() + " events, " + rt.size() + " entity states, " + rec.footprint().size() + " footprint positions (light for presence only), "
                + rec.entityAreas().size() + " entity areas, " + rec.heightmapColumns().size() + " heightmap columns, over " + ghost.ticks() + " ticks; copied "
                + (rect[1].x() - rect[0].x() + 1) + "x" + (rect[1].z() - rect[0].z() + 1) + " chunks");
        } else {
            lines.add("Ghost check: " + first.headline());
            if (differences > 1) {
                lines.add("  (" + differences + " kinds of difference in all; this is the earliest)");
            }
            if (!first.gaps().isEmpty()) {
                lines.add("  gaps hit by the ghost near it: " + String.join("; ", first.gaps()));
            }
            if (!first.unseeded().isEmpty()) {
                lines.add("  unseeded draws near it: " + String.join("; ", first.unseeded()));
            }
            if (rec.unseededDrawsInexact() > 0) {
                lines.add("  the real run drew unseeded numbers for a mob (" + rec.unseededDrawsInexact() + " draws): it may not repeat exactly");
            }
        }
        Set<CauseTracker.Column> gated = gatedColumns(rec, ghost);
        if (!gated.isEmpty()) {
            lines.add("Heightmap columns read in one run only, light read there in both (the read is gated by canSeeSky; light is compared for presence only): " + gated);
        }
        if (!ghost.gaps().isEmpty()) {
            Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            ghost.gaps().forEach(g -> counts.merge(g.kind(), 1, Integer::sum));
            lines.add("Ghost gaps hit: " + counts.entrySet().stream().map(e -> e.getKey() + " x" + e.getValue()).toList());
        }
        if (GhostLevel.strayPackets() > 0) {
            lines.add("WARNING: " + GhostLevel.strayPackets() + " packets were sent while the ghost worked: " + GhostLevel.strayPacketNames());
        }
        return new Result(true, first == null, first, "", lines, ghost, re.size(), rt.size(), differences);
    }

    private static Difference difference(Recording rec, GhostRun ghost, long tick, String phase, String what, String where, String real, String gh) {
        List<String> gaps = new ArrayList<>();
        for (CauseTracker.Gap g : ghost.gaps()) {
            if (Math.abs(g.tick() - tick) <= 1) {
                gaps.add(g.toString());
            }
        }
        List<String> unseeded = new ArrayList<>();
        for (ChainEvent e : rec.events()) {
            if (e.kind() == ChainEvent.Kind.UNSEEDED_DRAW && Math.abs(e.tick() - tick) <= 1 && unseeded.size() < 5) {
                unseeded.add("real " + e);
            }
        }
        for (ChainEvent e : ghost.events()) {
            if (e.kind() == ChainEvent.Kind.UNSEEDED_DRAW && Math.abs(e.tick() - tick) <= 1 && unseeded.size() < 10) {
                unseeded.add("ghost " + e);
            }
        }
        return new Difference(tick, phase, what, where, real, gh, gaps, unseeded);
    }

    /** The parts of two long strings around their first difference. */
    private static String[] excerpts(String a, String b) {
        int p = 0;
        while (p < a.length() && p < b.length() && a.charAt(p) == b.charAt(p)) {
            p++;
        }
        int from = Math.max(0, p - 40);
        return new String[] {"..." + a.substring(from, Math.min(a.length(), p + 60)), "..." + b.substring(from, Math.min(b.length(), p + 60))};
    }

    private static List<Difference> footprintDifferences(Recording rec, GhostRun ghost) {
        List<Difference> out = new ArrayList<>();
        // reads: every kind but light, exact
        Set<BlockPos> positions = new LinkedHashSet<>(rec.reads().keySet());
        positions.addAll(ghost.reads().keySet());
        List<BlockPos> sorted = new ArrayList<>(positions);
        sorted.sort(Comparator.<BlockPos>comparingLong(p -> Math.min(rec.readFirstTicks().getOrDefault(p, Long.MAX_VALUE), ghost.readFirstTicks().getOrDefault(p, Long.MAX_VALUE)))
            .thenComparing(p -> p.getX()).thenComparing(p -> p.getY()).thenComparing(p -> p.getZ()));
        for (BlockPos p : sorted) {
            Set<ReadKind> a = withoutLight(rec.reads().get(p));
            Set<ReadKind> b = withoutLight(ghost.reads().get(p));
            if (!a.equals(b)) {
                long tick = Math.min(rec.readFirstTicks().getOrDefault(p, Long.MAX_VALUE), ghost.readFirstTicks().getOrDefault(p, Long.MAX_VALUE));
                out.add(difference(rec, ghost, tick == Long.MAX_VALUE ? rec.ticks() : tick, "(footprint)", "footprint read", p.toShortString(),
                    a.isEmpty() ? "not read" : a.toString(), b.isEmpty() ? "not read" : b.toString()));
            }
        }
        boolean realLight = rec.reads().values().stream().anyMatch(k -> k.contains(ReadKind.LIGHT));
        boolean ghostLight = ghost.reads().values().stream().anyMatch(k -> k.contains(ReadKind.LIGHT));
        if (realLight != ghostLight) {
            out.add(difference(rec, ghost, rec.ticks(), "(footprint)", "light reads (presence)", "", String.valueOf(realLight), String.valueOf(ghostLight)));
        }
        if (!rec.writes().equals(ghost.writes())) {
            Set<BlockPos> onlyReal = new HashSet<>(rec.writes());
            onlyReal.removeAll(ghost.writes());
            Set<BlockPos> onlyGhost = new HashSet<>(ghost.writes());
            onlyGhost.removeAll(rec.writes());
            out.add(difference(rec, ghost, rec.ticks(), "(footprint)", "written positions", "", "only in real: " + onlyReal, "only in ghost: " + onlyGhost));
        }
        if (!rec.entityAreas().equals(ghost.entityAreas())) {
            Set<AABB> onlyReal = new HashSet<>(rec.entityAreas());
            onlyReal.removeAll(ghost.entityAreas());
            Set<AABB> onlyGhost = new HashSet<>(ghost.entityAreas());
            onlyGhost.removeAll(rec.entityAreas());
            out.add(difference(rec, ghost, rec.ticks(), "(footprint)", "entity-query boxes", "",
                onlyReal.size() + " only in real, e.g. " + onlyReal.stream().limit(2).toList(), onlyGhost.size() + " only in ghost, e.g. " + onlyGhost.stream().limit(2).toList()));
        }
        if (!rec.heightmapColumns().equals(ghost.heightmapColumns())) {
            Set<CauseTracker.Column> onlyReal = new HashSet<>(rec.heightmapColumns());
            onlyReal.removeAll(ghost.heightmapColumns());
            Set<CauseTracker.Column> onlyGhost = new HashSet<>(ghost.heightmapColumns());
            onlyGhost.removeAll(rec.heightmapColumns());
            // A heightmap is read only after Level.precipitationAt found canSeeSky (Level.java:961-963): the read is gated
            // by a light read in the same column. A column read in one run only, where BOTH runs read light, is that gate
            // closed in one run: light is compared for presence only, so it is not a difference (DESIGN, step 3: the 7-vs-8).
            Set<CauseTracker.Column> realLightCols = lightColumns(rec.reads());
            Set<CauseTracker.Column> ghostLightCols = lightColumns(ghost.reads());
            onlyReal.removeIf(c -> realLightCols.contains(c) && ghostLightCols.contains(c));
            onlyGhost.removeIf(c -> realLightCols.contains(c) && ghostLightCols.contains(c));
            if (!onlyReal.isEmpty() || !onlyGhost.isEmpty()) {
                out.add(difference(rec, ghost, rec.ticks(), "(footprint)", "heightmap columns", "", rec.heightmapColumns().size() + " columns, only in real: " + onlyReal,
                    ghost.heightmapColumns().size() + " columns, only in ghost: " + onlyGhost));
            }
        }
        CauseTracker.Pending realPending = rec.pendingAtEnd();
        if (!realPending.equals(ghost.pending())) {
            out.add(difference(rec, ghost, rec.ticks(), "(end)", "chain work pending at the end", "", realPending.toString(), ghost.pending().toString()));
        }
        return out;
    }

    /** Columns (x, z) in which the run read light at any height. */
    private static Set<CauseTracker.Column> lightColumns(Map<BlockPos, Set<ReadKind>> reads) {
        Set<CauseTracker.Column> out = new HashSet<>();
        reads.forEach((pos, kinds) -> {
            if (kinds.contains(ReadKind.LIGHT)) {
                out.add(new CauseTracker.Column(pos.getX(), pos.getZ()));
            }
        });
        return out;
    }

    /** Heightmap columns read in one run only although both read light there (the gate); for the summary. */
    private static Set<CauseTracker.Column> gatedColumns(Recording rec, GhostRun ghost) {
        Set<CauseTracker.Column> diff = new HashSet<>(rec.heightmapColumns());
        diff.addAll(ghost.heightmapColumns());
        Set<CauseTracker.Column> both = new HashSet<>(rec.heightmapColumns());
        both.retainAll(ghost.heightmapColumns());
        diff.removeAll(both);
        Set<CauseTracker.Column> realLight = lightColumns(rec.reads());
        Set<CauseTracker.Column> ghostLight = lightColumns(ghost.reads());
        diff.removeIf(c -> !(realLight.contains(c) && ghostLight.contains(c)));
        return diff;
    }

    private static Set<ReadKind> withoutLight(@Nullable Set<ReadKind> kinds) {
        if (kinds == null) {
            return Set.of();
        }
        Set<ReadKind> out = new HashSet<>(kinds);
        out.remove(ReadKind.LIGHT);
        return out;
    }
}
