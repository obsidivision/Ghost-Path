package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.AABB;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.command.PathCommand;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.ChainEvent;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.record.RecordingStore;
import obsidivision.ghostpath.record.UndoLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step 1d: {@code /path machine}. The 1b machine (repeaters, piston, TNT) with a lever as trigger is recorded
 * through the real command, twice. Checks: the printed summary is the recording's; its numbers are recomputed from the
 * recording's raw data; the second run gives the same recording (so the world was restored); the events equal those of a
 * direct tracker run that pulls the same lever; the world is as before; the tick limit is reported; bad targets, the kick
 * setting and permissions behave.
 */
public class MachineCommandTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    private static Runnable restoreWeather = () -> {};
    private static final Pattern VELOCITY = Pattern.compile("minecraft:tnt pos=\\(.*\\) v=\\(([^,]+), ([^,]+), ([^)]+)\\)");

    static final class Capture implements CommandSource {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            this.lines.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private static CommandSourceStack source(ServerLevel level, Capture capture, LevelBasedPermissionSet permissions) {
        return level.getServer().createCommandSourceStack().withSource(capture).withLevel(level).withPermission(permissions);
    }

    private static Map<BlockPos, BlockState> snapshot(ServerLevel level, BlockPos min, BlockPos max) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            out.put(p.immutable(), level.getBlockState(p));
        }
        return out;
    }

    private static String diff(Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> after) {
        List<String> d = new ArrayList<>();
        before.forEach((p, s) -> {
            if (after.get(p) != s) {
                d.add(p.toShortString() + " " + s + " -> " + after.get(p));
            }
        });
        return d.stream().limit(5).toList() + " (" + d.size() + " differ)";
    }

    private static void whenIdle(GameTestHelper helper, int waited, Runnable then) {
        if (MachineRecorder.current() == null) {
            then.run();
        } else if (TestSupport.overRealTime(helper)) {
            helper.fail("recording did not finish within the real-time limit (" + TestSupport.REAL_TIME_LIMIT_S + " s)");
        } else {
            helper.runAfterDelay(1, () -> whenIdle(helper, waited + 1, then));
        }
    }

    /** Runs {@code /path machine pos} and calls {@code then} with the captured output once the recording is over. */
    static void runCommand(GameTestHelper helper, ServerLevel level, Capture capture, BlockPos pos, Runnable then) {
        MinecraftServer server = level.getServer();
        PathSettings.watchAfterMachine = false; // a watch would outlive the test and rerun when the next batch clears the area
        server.getCommands().performPrefixedCommand(source(level, capture, LevelBasedPermissionSet.GAMEMASTER),
            "path machine " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        whenIdle(helper, 0, then);
    }

    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:machine_command")
    public void pathMachineOnTheCauseMachine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
        RecordingStore.clear();
        // The machine reads light and heightmaps only while it rains: Entity.isInRain -> Level.precipitationAt returns early
        // when !isRaining() (Level.java:957). Weather is not part of the undo log and varies from run to run (found by
        // this test: "light reads 0" in some runs), so it is pinned here to make the footprint repeatable and the hooks exercised.
        WeatherData weather = level.getWeatherData();
        boolean wasRaining = weather.isRaining();
        int wasRainTime = weather.getRainTime();
        float wasRainLevel = level.getRainLevel(1.0F);
        weather.setClearWeatherTime(0);
        weather.setRaining(true);
        weather.setRainTime(1_000_000);
        level.setRainLevel(1.0F);
        // thunder is pinned like the rain: thundering with the level at its target, so it does not ramp between two recordings
        // (seen without: thunder level 0.41 at the start of the first recording, 1.0 at the start of the second)
        boolean wasThundering = weather.isThundering();
        int wasThunderTime = weather.getThunderTime();
        float wasThunderLevel = level.getThunderLevel(1.0F);
        weather.setThundering(true);
        weather.setThunderTime(1_000_000);
        level.setThunderLevel(1.0F);
        restoreWeather = () -> {
            weather.setRaining(wasRaining);
            weather.setRainTime(wasRainTime);
            level.setRainLevel(wasRainLevel);
            weather.setThundering(wasThundering);
            weather.setThunderTime(wasThunderTime);
            level.setThunderLevel(wasThunderLevel);
        };
        CauseTrackingTest.buildMachine(helper, BlockPos.ZERO, true);
        BlockPos lever = helper.absolutePos(CauseTrackingTest.trigger());
        BlockPos min = helper.absolutePos(new BlockPos(-4, 0, -4));
        BlockPos max = helper.absolutePos(new BlockPos(18, 5, 10));
        AABB box = new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);

        CauseTrackingTest.whenEntityTicking(helper, () -> {
            Map<BlockPos, BlockState> before = snapshot(level, min, max);
            int entitiesBefore = level.getEntities((net.minecraft.world.entity.Entity) null, box).size();
            Capture out1 = new Capture();
            runCommand(helper, level, out1, lever, () -> {
                Recording r1 = RecordingStore.latest();
                helper.assertTrue(r1 != null, "no recording stored; output: " + out1.lines);
                LOG.info("[ghostpath] /path machine output:\n{}", String.join("\n", out1.lines));
                helper.assertTrue(UndoLog.active() == null && CauseTracker.active() == null, "recording state left behind");
                helper.assertTrue(diff(before, snapshot(level, min, max)).endsWith("(0 differ)"), "world not restored: " + diff(before, snapshot(level, min, max)));
                helper.assertTrue(level.getEntities((net.minecraft.world.entity.Entity) null, box).size() == entitiesBefore, "entities not restored");
                checkSummary(helper, r1, out1);

                // run 2: same lever, same seed, restored world -> the same recording
                Capture out2 = new Capture();
                runCommand(helper, level, out2, lever, () -> {
                    Recording r2 = RecordingStore.latest();
                    helper.assertTrue(r2 != null && r2 != r1, "second recording missing");
                    for (int i = 0; i < Math.max(r1.events().size(), r2.events().size()); i++) {
                        String x = i < r1.events().size() ? r1.events().get(i).toString() : "<end>";
                        String y = i < r2.events().size() ? r2.events().get(i).toString() : "<end>";
                        helper.assertTrue(x.equals(y), "second run differs at event " + i + " of " + r1.events().size() + "/" + r2.events().size() + ":\n  run 1: " + x + "\n  run 2: " + y);
                    }
                    // Light reads are compared only for presence: the number of positions read for light varied by one between two
                    // otherwise identical runs (skylight is updated by the asynchronous light thread after the restore).
                    helper.assertTrue(normalized(r1).equals(normalized(r2)), "second run differs: " + r1.summary() + " vs " + r2.summary());
                    helper.assertTrue(readsWithoutLight(r1).equals(readsWithoutLight(r2)) && r1.writes().equals(r2.writes())
                        && r1.entityAreas().equals(r2.entityAreas()) && heightmapEqualUpToLight(r1, r2), "second run has a different footprint");

                    directRun(helper, level, lever, r1, () -> afterwards(helper, level, lever, before, min, max));
                });
            });
        });
    }

    private static List<String> normalized(Recording r) {
        // light reads are not repeatable (DESIGN). The restore is scoped to the chain, so "chunks loaded again for the
        // restore" is compared again (it varied with unrelated chunks while the restore was level-wide).
        return r.summary().stream().map(l -> l.replaceAll("light reads \\d+", "light reads *").replaceAll("heightmap columns \\d+", "heightmap columns *")).toList();
    }

    /**
     * Heightmap columns are read only after canSeeSky found sky light (Level.precipitationAt, Level.java:961-963), so a column
     * read in one run only, where both runs read light, is the light gate closed in one run (seen: 7 vs 8 columns, DESIGN step 3).
     */
    private static boolean heightmapEqualUpToLight(Recording a, Recording b) {
        Set<CauseTracker.Column> diff = new HashSet<>(a.heightmapColumns());
        diff.addAll(b.heightmapColumns());
        diff.removeIf(c -> a.heightmapColumns().contains(c) && b.heightmapColumns().contains(c));
        return diff.stream().allMatch(c -> lightIn(a, c) && lightIn(b, c));
    }

    private static boolean lightIn(Recording r, CauseTracker.Column c) {
        return r.reads().entrySet().stream().anyMatch(e -> e.getKey().getX() == c.x() && e.getKey().getZ() == c.z() && e.getValue().contains(CauseTracker.ReadKind.LIGHT));
    }

    private static Map<BlockPos, Set<CauseTracker.ReadKind>> readsWithoutLight(Recording r) {
        Map<BlockPos, Set<CauseTracker.ReadKind>> out = new HashMap<>();
        r.reads().forEach((pos, kinds) -> {
            Set<CauseTracker.ReadKind> rest = new HashSet<>(kinds);
            rest.remove(CauseTracker.ReadKind.LIGHT);
            if (!rest.isEmpty()) {
                out.put(pos, rest);
            }
        });
        return out;
    }

    /** The printed summary is the recording's; each number is recomputed from the recording's raw data. */
    private static void checkSummary(GameTestHelper helper, Recording r, Capture out) {
        // the recording's summary is printed as one block; the ghost check's lines (step 3) follow it
        helper.assertTrue(out.lines.size() >= 1 + r.summary().size() && java.util.Collections.indexOfSubList(out.lines, r.summary()) >= 0,
            "printed summary is not the recording's: " + out.lines + " vs " + r.summary());
        for (String key : List.of("ticks", "written", "read", "entity areas", "TNT spawned: 1", "Unseeded draws: 0 warnings", "Late block entities: 0")) {
            helper.assertTrue(r.summary().stream().anyMatch(l -> l.contains(key)), "summary lacks '" + key + "': " + r.summary());
        }
        Set<BlockPos> writeEvents = new HashSet<>();
        long tnt = 0;
        long lastTick = 0;
        for (ChainEvent e : r.events()) {
            if (e.kind() == ChainEvent.Kind.WRITE) {
                writeEvents.add(e.pos());
            }
            if (e.kind() == ChainEvent.Kind.ENTITY_TAGGED && e.detail().contains("minecraft:tnt")) {
                tnt++;
            }
            lastTick = Math.max(lastTick, e.tick());
        }
        helper.assertTrue(!r.limitHit(), "limit hit: " + r.summary());
        helper.assertTrue(r.summary().get(0).contains(r.ticks() + " ticks"), "ticks not in the summary: " + r.summary().get(0));
        helper.assertTrue(r.ticks() >= lastTick && r.ticks() - lastTick <= 2, "ticks " + r.ticks() + " do not match the last event at t+" + lastTick);
        helper.assertTrue(r.writes().equals(writeEvents), "write footprint differs from the WRITE events: " + r.writes().size() + " vs " + writeEvents.size());
        helper.assertTrue(r.summary().get(1).startsWith("Footprint: " + r.writes().size() + " written / " + r.reads().size() + " read / " + r.entityAreas().size() + " entity areas"),
            "footprint numbers wrong: " + r.summary().get(1));
        // found by probing this machine: Level.precipitationAt (Level.java:961, 965) reads light (canSeeSky) and a heightmap
        helper.assertTrue(r.reads().values().stream().anyMatch(k -> k.contains(CauseTracker.ReadKind.LIGHT)), "no light read recorded: " + r.summary().get(1));
        helper.assertTrue(!r.heightmapColumns().isEmpty(), "no heightmap read recorded: " + r.summary().get(1));
        helper.assertTrue(r.summary().get(1).contains("heightmap columns " + r.heightmapColumns().size()), "heightmap count not in the summary: " + r.summary().get(1));
        helper.assertTrue(r.tntSpawned() == 1 && tnt == 1, "TNT spawned " + r.tntSpawned() + " / tagged " + tnt);
        helper.assertTrue(r.writes().size() > 10 && r.reads().size() > 100 && !r.entityAreas().isEmpty(), "footprint too small: " + r.summary().get(1));
        helper.assertTrue(r.unseededDrawsNotAllowed() == 0 && r.lateBlockEntities().isEmpty(), "unexpected warnings: " + r.summary());
        helper.assertTrue(r.writes().contains(r.trigger()), "the lever is not in the written positions");
        helper.assertTrue(r.dimension().equals(helper.getLevel().dimension()) && r.seed() == MachineRecorder.SEED, "dimension or seed");
    }

    /** The same lever pulled in a plain tracker run (no recorder): the events up to the recorder's end must be the same. */
    private static void directRun(GameTestHelper helper, ServerLevel level, BlockPos lever, Recording r, Runnable then) {
        UndoLog log = UndoLog.begin(level);
        CauseTracker tracker = CauseTracker.begin(level, MachineRecorder.SEED);
        BlockState state = level.getBlockState(lever);
        tracker.runAsChain(() -> ((LeverBlock) state.getBlock()).pull(state, level, lever, null));
        helper.runAfterDelay(r.ticks() + 5, () -> {
            tracker.end();
            try {
                List<ChainEvent> direct = tracker.events().stream().filter(e -> e.tick() < r.ticks() - 1).toList();
                List<ChainEvent> recorded = r.events().stream().filter(e -> e.tick() < r.ticks() - 1).toList();
                for (int i = 0; i < Math.max(direct.size(), recorded.size()); i++) {
                    String a = i < direct.size() ? direct.get(i).toString() : "<end>";
                    String b = i < recorded.size() ? recorded.get(i).toString() : "<end>";
                    helper.assertTrue(a.equals(b), "direct run differs at event " + i + ":\n  direct:   " + a + "\n  recorder: " + b);
                }
                LOG.info("[ghostpath] recorder events equal a direct tracker run: {} events compared", direct.size());
            } finally {
                log.restore();
            }
            then.run();
        });
    }

    private static void afterwards(GameTestHelper helper, ServerLevel level, BlockPos lever, Map<BlockPos, BlockState> before, BlockPos min, BlockPos max) {
        // tick limit: reported, world restored
        PathSettings.tickLimit = 5;
        Capture limited = new Capture();
        runCommand(helper, level, limited, lever, () -> {
            PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
            Recording r = RecordingStore.latest();
            LOG.info("[ghostpath] limited run output:\n{}", String.join("\n", limited.lines));
            helper.assertTrue(r.limitHit() && r.ticks() <= 6 && r.ticks() >= 4, "limit 5 not hit as expected: " + r.summary());
            helper.assertTrue(limited.lines.stream().anyMatch(l -> l.contains("STOPPED AT THE TICK LIMIT (5)")), "limit hit not said: " + limited.lines);
            helper.assertTrue(r.pendingAtEnd().any(), "limit hit but nothing pending");
            helper.assertTrue(diff(before, snapshot(level, min, max)).endsWith("(0 differ)"), "world not restored after a limited run");

            // not a lever or a button: a clear failure, nothing recorded
            Capture bad = new Capture();
            BlockPos stone = lever.below();
            Recording last = RecordingStore.latest();
            level.getServer().getCommands().performPrefixedCommand(source(level, bad, LevelBasedPermissionSet.GAMEMASTER),
                "path machine " + stone.getX() + " " + stone.getY() + " " + stone.getZ());
            LOG.info("[ghostpath] bad target output: {}", bad.lines);
            helper.assertTrue(bad.lines.stream().anyMatch(l -> l.contains("not a lever or a button")), "no clear failure: " + bad.lines);
            helper.assertTrue(MachineRecorder.current() == null && UndoLog.active() == null && RecordingStore.latest() == last, "a bad target started a recording");

            // permissions: level 0 is refused, level 2 allowed
            Capture denied = new Capture();
            level.getServer().getCommands().performPrefixedCommand(source(level, denied, LevelBasedPermissionSet.ALL),
                "path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
            helper.assertTrue(MachineRecorder.current() == null && RecordingStore.latest() == last, "permission level 0 started a recording");
            helper.assertTrue(!PathCommand.allowed(source(level, denied, LevelBasedPermissionSet.ALL)), "level 0 allowed");
            helper.assertTrue(PathCommand.allowed(source(level, denied, LevelBasedPermissionSet.GAMEMASTER)), "level 2 not allowed");

            kickOff(helper, level, lever, before, min, max);
        });
    }

    /** {@code /path kick off}: the TNT starts with no horizontal velocity; on, it has the seeded kick. */
    private static void kickOff(GameTestHelper helper, ServerLevel level, BlockPos lever, Map<BlockPos, BlockState> before, BlockPos min, BlockPos max) {
        Recording on = RecordingStore.latest();
        Capture ignored = new Capture();
        level.getServer().getCommands().performPrefixedCommand(source(level, ignored, LevelBasedPermissionSet.GAMEMASTER), "path kick off");
        helper.assertTrue(!PathSettings.kick, "/path kick off did not set the setting");
        runCommand(helper, level, ignored, lever, () -> {
            Recording off = RecordingStore.latest();
            level.getServer().getCommands().performPrefixedCommand(source(level, ignored, LevelBasedPermissionSet.GAMEMASTER), "path kick on");
            helper.assertTrue(PathSettings.kick, "/path kick on did not set the setting");
            helper.assertTrue(off != on && !off.kickApplied(), "recording did not note kick off");
            double[] vOff = firstTntVelocity(off);
            helper.assertTrue(vOff[0] == 0.0 && vOff[2] == 0.0, "kick off but TNT has horizontal velocity: " + java.util.Arrays.toString(vOff));
            // reference: the ticked-once velocity of a TNT without kick must show the pop only; with a kick it has x/z
            runCommand(helper, level, ignored, lever, () -> {
                Recording again = RecordingStore.latest();
                double[] vOn = firstTntVelocity(again);
                LOG.info("[ghostpath] first TNT velocity: kick off {} / kick on {}", java.util.Arrays.toString(vOff), java.util.Arrays.toString(vOn));
                helper.assertTrue(again.kickApplied() && (vOn[0] != 0.0 || vOn[2] != 0.0), "kick on but TNT has no horizontal velocity: " + java.util.Arrays.toString(vOn));
                helper.assertTrue(diff(before, snapshot(level, min, max)).endsWith("(0 differ)"), "world not restored at the end");
                CauseTrackingTest.forceChunks(helper, false);
                restoreWeather.run();
                helper.succeed();
            });
        });
    }

    private static double[] firstTntVelocity(Recording r) {
        for (ChainEvent e : r.events()) {
            if (e.kind() == ChainEvent.Kind.ENTITY_STATE) {
                Matcher m = VELOCITY.matcher(e.detail());
                if (m.find()) {
                    return new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))};
                }
            }
        }
        throw new IllegalStateException("no TNT state in the recording");
    }
}
