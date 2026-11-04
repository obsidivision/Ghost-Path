package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.clock.ClockState;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.AABB;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.ghost.GhostCheck;
import obsidivision.ghostpath.ghost.GhostCopy;
import obsidivision.ghostpath.ghost.GhostLevel;
import obsidivision.ghostpath.mixin.LevelWeatherAccessor;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.ChainEvent;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step 3: the ghost against the real run. Four machines (the 1b repeater/piston/TNT machine, a dispenser machine,
 * a hopper machine, a sculk-sensor machine) are recorded with {@link MachineRecorder} and checked with {@link GhostCheck};
 * each must report "exact" and must have exercised what it is there for. Break-it tests: a block changed in the ghost
 * after the copy, a different seed, a different kick setting, a different weather, and (through the tamper hook) a
 * ghost whose entity state, events, reads, heightmap columns, entity boxes, writes or pending work differ: the check must
 * name the right tick, position and kind. Also: the gaps are logged (ruling 3), the clocks are the ghost's own (ruling 2),
 * and no ghost entity is tracked or sent (ruling 4).
 */
public class GhostCheckTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** Rain on, as MachineCommandTest does: the machine then reads light and heightmaps (Level.precipitationAt). */
    private static Runnable pinRain(ServerLevel level) {
        WeatherData weather = level.getWeatherData();
        boolean wasRaining = weather.isRaining();
        int wasRainTime = weather.getRainTime();
        float wasRainLevel = level.getRainLevel(1.0F);
        weather.setClearWeatherTime(0);
        weather.setRaining(true);
        weather.setRainTime(1_000_000);
        level.setRainLevel(1.0F);
        return () -> {
            weather.setRaining(wasRaining);
            weather.setRainTime(wasRainTime);
            level.setRainLevel(wasRainLevel);
        };
    }

    private static void whenIdle(GameTestHelper helper, int waited, Runnable then) {
        if (MachineRecorder.current() == null) {
            then.run();
        } else if (waited > 6000) {
            helper.fail("recording did not finish within 6000 ticks");
        } else {
            helper.runAfterDelay(1, () -> whenIdle(helper, waited + 1, then));
        }
    }

    /** Records {@code lever} (what {@code /path machine} does) and hands over the recording. */
    private static void record(GameTestHelper helper, ServerLevel level, BlockPos lever, java.util.function.Consumer<Recording> then) {
        Recording[] out = new Recording[1];
        String[] failed = new String[1];
        try {
            MachineRecorder.start(level, lever, PathSettings.tickLimit, PathSettings.kick, new MachineRecorder.Listener() {
                @Override
                public void done(Recording recording) {
                    out[0] = recording;
                }

                @Override
                public void failed(String message) {
                    failed[0] = message;
                }
            });
        } catch (MachineRecorder.RejectedException e) {
            helper.fail("recording rejected: " + e.getMessage());
            return;
        }
        whenIdle(helper, 0, () -> {
            if (out[0] == null) {
                helper.fail("no recording: " + failed[0]);
                return;
            }
            then.accept(out[0]);
        });
    }

    private static void recordAndCheck(GameTestHelper helper, ServerLevel level, BlockPos lever, BiConsumer<Recording, GhostCheck.Result> then) {
        record(helper, level, lever, rec -> {
            GhostCheck.Result result = GhostCheck.run(level, rec);
            LOG.info("[ghostpath] ghost check of {}:\n{}", lever.toShortString(), String.join("\n", result.lines()));
            then.accept(rec, result);
        });
    }

    /** The first events of both logs around the first difference, for a failed check. */
    private static String around(Recording rec, GhostCheck.Result r) {
        if (r.ghostRun() == null || r.first() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(14, Math.max(rec.events().size(), r.ghostRun().events().size())); i++) {
            sb.append("\n  #").append(i).append(" real  ").append(i < rec.events().size() ? rec.events().get(i) : "<end>");
            sb.append("\n  #").append(i).append(" ghost ").append(i < r.ghostRun().events().size() ? r.ghostRun().events().get(i) : "<end>");
        }
        return sb.toString();
    }

    private static void assertExact(GameTestHelper helper, Recording rec, GhostCheck.Result r) {
        helper.assertTrue(r.ran(), "ghost check did not run: " + r.note());
        helper.assertTrue(r.exact(), "ghost check not exact:\n" + String.join("\n", r.lines()) + around(rec, r));
        helper.assertTrue(r.lines().getFirst().equals("Ghost check: exact"), "summary line: " + r.lines());
        helper.assertTrue(GhostLevel.strayPackets() == 0, "packets were sent while the ghost worked: " + GhostLevel.strayPacketNames());
    }

    private static boolean has(Recording rec, ChainEvent.Kind kind, String detail) {
        return rec.events().stream().anyMatch(e -> e.kind() == kind && e.detail().contains(detail));
    }

    private static boolean has(Recording rec, ChainEvent.Kind kind, BlockPos pos, String detail) {
        return rec.events().stream().anyMatch(e -> e.kind() == kind && pos.equals(e.pos()) && e.detail().contains(detail));
    }

    /** Waits until every given position is entity-ticking (forced chunks take real time to get there). */
    private static void whenTicking(GameTestHelper helper, List<BlockPos> rel, Runnable then, int waited) {
        boolean ready = true;
        for (BlockPos r : rel) {
            ready &= helper.getLevel().isPositionEntityTicking(helper.absolutePos(r));
        }
        if (ready) {
            then.run();
        } else if (waited > 40000) {
            helper.fail("machine area not entity-ticking after 40000 ticks");
        } else {
            helper.runAfterDelay(1, () -> whenTicking(helper, rel, then, waited + 1));
        }
    }

    /** A stone floor under the machine, no barrier shell, the area around it forced. */
    private static void prepare(GameTestHelper helper, int sizeX, int sizeZ) {
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        for (int x = -1; x <= sizeX; x++) {
            for (int z = 0; z <= sizeZ; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    private static void whenTicking(GameTestHelper helper, List<BlockPos> rel, Runnable then) {
        whenTicking(helper, rel, then, 0);
    }

    private static final List<BlockPos> CORNERS = List.of(new BlockPos(-6, 2, -6), new BlockPos(20, 2, -6), new BlockPos(-6, 2, 12), new BlockPos(20, 2, 12), new BlockPos(2, 2, 2));

    // ---- the four machines ------------------------------------------------------------------------------------------

    /** The 1b machine: repeaters, a piston pushing a redstone block, TNT; a floor lever as the trigger; rain on. */
    @GameTest(maxTicks = 60000, padding = 24, environment = "ghostpath-gametest:ghostcheck_cause")
    public void causeMachineGhostIsExact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        Runnable restoreWeather = pinRain(level);
        CauseTrackingTest.buildMachine(helper, BlockPos.ZERO, true);
        BlockPos lever = helper.absolutePos(CauseTrackingTest.trigger());
        CauseTrackingTest.whenEntityTicking(helper, () -> recordAndCheck(helper, level, lever, (rec, r) -> {
            try {
                assertExact(helper, rec, r);
                helper.assertTrue(rec.events().size() > 100 && r.statesCompared() > 50, "too little compared: " + r.lines());
                helper.assertTrue(rec.tntSpawned() == 1 && has(rec, ChainEvent.Kind.ENTITY_STATE, "minecraft:tnt"), "no TNT in the recording");
                helper.assertTrue(has(rec, ChainEvent.Kind.BLOCK_EVENT_RUN, "piston"), "the piston did not run");
                helper.assertTrue(!rec.heightmapColumns().isEmpty() && rec.reads().values().stream().anyMatch(k -> k.contains(CauseTracker.ReadKind.LIGHT)),
                    "no light/heightmap reads (rain)");
                helper.assertTrue(r.ghostRun().events().size() == rec.events().size(), "event counts differ");
                // every event of the ghost has the phase of the real one, and the phases are not all the same
                Set<String> phases = new HashSet<>();
                rec.events().forEach(e -> phases.add(e.phase()));
                helper.assertTrue(phases.size() >= 3, "phases not told apart: " + phases);
                helper.assertTrue(r.ghostRun().gaps().stream().anyMatch(g -> g.kind().equals("world border")), "no world border gap logged: " + r.ghostRun().gaps());
                LOG.info("[ghostpath] cause machine phases {}", phases);
            } finally {
                CauseTrackingTest.forceChunks(helper, false);
                restoreWeather.run();
            }
            helper.succeed();
        }));
    }

    /** Builds the dispenser machine: a lever with three dispensers around it (TNT and snowballs), a wall, fodder blocks. */
    private static void buildDispensers(GameTestHelper helper) {
        prepare(helper, 10, 6);
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 4; y++) {
                helper.setBlock(new BlockPos(9, y, z), Blocks.STONE);
            }
        }
        for (BlockPos p : List.of(new BlockPos(5, 2, 2), new BlockPos(5, 2, 3), new BlockPos(5, 2, 4), new BlockPos(6, 2, 3), new BlockPos(6, 2, 2))) {
            helper.setBlock(p, Blocks.DIRT);
        }
        helper.setBlock(new BlockPos(6, 3, 3), Blocks.SAND); // falls when the dirt below it is blasted away
        helper.setBlock(new BlockPos(5, 3, 2), Blocks.GRAVEL);
        helper.setBlock(new BlockPos(2, 2, 3), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        dispenser(helper, new BlockPos(2, 2, 2), Direction.EAST, new ItemStack(Items.SNOWBALL, 3));
        dispenser(helper, new BlockPos(2, 2, 4), Direction.EAST, new ItemStack(Items.TNT, 1));
        dispenser(helper, new BlockPos(3, 2, 3), Direction.UP, new ItemStack(Items.TNT, 1), new ItemStack(Items.SNOWBALL, 1));
    }

    private static void dispenser(GameTestHelper helper, BlockPos rel, Direction facing, ItemStack... stacks) {
        helper.setBlock(rel, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, facing));
        DispenserBlockEntity be = (DispenserBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        for (int i = 0; i < stacks.length; i++) {
            be.setItem(i, stacks[i]);
        }
    }

    /**
     * A lever next to three dispensers (TNT and snowballs; the item each fires is chosen from the seeded chain random),
     * fodder blocks, sand that falls. The dispensed TNT explodes (seeded), snowballs fly into the wall, sand falls.
     */
    @GameTest(maxTicks = 60000, padding = 24, environment = "ghostpath-gametest:ghostcheck_dispenser")
    public void dispenserMachineGhostIsExact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        buildDispensers(helper);
        BlockPos lever = helper.absolutePos(new BlockPos(2, 2, 3));
        whenTicking(helper, CORNERS, () -> recordAndCheck(helper, level, lever, (rec, r) -> {
            try {
                assertExact(helper, rec, r);
                helper.assertTrue(rec.tntSpawned() >= 1 && has(rec, ChainEvent.Kind.ENTITY_STATE, "minecraft:tnt"), "no dispensed TNT: " + rec.summary());
                helper.assertTrue(has(rec, ChainEvent.Kind.ENTITY_STATE, "minecraft:snowball"), "no snowball in flight");
                helper.assertTrue(rec.events().stream().anyMatch(e -> e.kind() == ChainEvent.Kind.WRITE && e.detail().contains("-> Block{minecraft:air}")),
                    "the blast destroyed nothing");
                helper.assertTrue(r.statesCompared() > 100, "too few entity states compared: " + r.statesCompared());
                LOG.info("[ghostpath] dispenser machine: {} events, {} states, {}", rec.events().size(), r.statesCompared(), rec.summary().get(2));
            } finally {
                CauseTrackingTest.forceChunks(helper, false);
            }
            helper.succeed();
        }));
    }

    /**
     * A lever (on) on a stone block disables the hopper beside it; pulling it enables the hopper, which passes 3 diamonds
     * and 2 stone through a second hopper into a chest. Block entities in the chain, hopper cooldowns, container writes.
     */
    @GameTest(maxTicks = 60000, padding = 24, environment = "ghostpath-gametest:ghostcheck_hopper")
    public void hopperMachineGhostIsExact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        prepare(helper, 8, 4);
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 3, 2), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.POWERED, true));
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(4, 2, 2), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(5, 2, 2), Blocks.CHEST);
        HopperBlockEntity first = (HopperBlockEntity) level.getBlockEntity(helper.absolutePos(new BlockPos(3, 2, 2)));
        first.setItem(0, new ItemStack(Items.DIAMOND, 3));
        first.setItem(1, new ItemStack(Items.STONE, 2));
        BlockPos lever = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos chest = helper.absolutePos(new BlockPos(5, 2, 2));
        whenTicking(helper, CORNERS, () -> {
            helper.assertTrue(!level.getBlockState(helper.absolutePos(new BlockPos(3, 2, 2))).getValue(HopperBlock.ENABLED), "hopper not disabled before the run");
            recordAndCheck(helper, level, lever, (rec, r) -> {
                try {
                    assertExact(helper, rec, r);
                    helper.assertTrue(has(rec, ChainEvent.Kind.BLOCK_ENTITY_TAGGED, helper.absolutePos(new BlockPos(3, 2, 2)), "hopper"), "first hopper not in the chain");
                    helper.assertTrue(has(rec, ChainEvent.Kind.BLOCK_ENTITY_TAGGED, helper.absolutePos(new BlockPos(4, 2, 2)), "hopper"), "second hopper not in the chain");
                    helper.assertTrue(has(rec, ChainEvent.Kind.WRITE, chest, "block entity data"), "the chest was never written");
                    helper.assertTrue(rec.ticks() >= 30, "the run was too short for the items to pass: " + rec.ticks());
                    LOG.info("[ghostpath] hopper machine: {} events, {} ticks", rec.events().size(), rec.ticks());
                } finally {
                    CauseTrackingTest.forceChunks(helper, false);
                }
                helper.succeed();
            });
        }, 0);
    }

    /**
     * A lever on a stone block powers a piston (stone block in front of it); the piston's game event reaches a sculk
     * sensor, which schedules a vibration (block entity listener), activates, and lights a lamp. Vibrations, block
     * entities, the sensor's cooldown.
     */
    @GameTest(maxTicks = 60000, padding = 24, environment = "ghostpath-gametest:ghostcheck_sculk")
    public void sculkMachineGhostIsExact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        prepare(helper, 8, 6);
        helper.setBlock(new BlockPos(0, 2, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(0, 3, 1), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.SCULK_SENSOR);
        helper.setBlock(new BlockPos(5, 2, 4), Blocks.REDSTONE_LAMP);
        BlockPos lever = helper.absolutePos(new BlockPos(0, 3, 1));
        BlockPos sensor = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos lamp = helper.absolutePos(new BlockPos(5, 2, 4));
        whenTicking(helper, CORNERS, () -> helper.runAfterDelay(5, () -> recordAndCheck(helper, level, lever, (rec, r) -> {
            try {
                assertExact(helper, rec, r);
                helper.assertTrue(has(rec, ChainEvent.Kind.BLOCK_ENTITY_TAGGED, sensor, "vibration"), "sensor not reached by the vibration");
                helper.assertTrue(has(rec, ChainEvent.Kind.WRITE, sensor, "sculk_sensor_phase=active"), "sensor never activated");
                helper.assertTrue(has(rec, ChainEvent.Kind.WRITE, lamp, "lit=true"), "lamp never lit");
                LOG.info("[ghostpath] sculk machine: {} events, {} ticks", rec.events().size(), rec.ticks());
                // Found by this check: the restore must not leave the chain's vibration pending in the real sensor (the first-touch
                // snapshot used to be taken after scheduleVibration added it). The real world is run on for 80 ticks: nothing happens.
                var realSensor = (net.minecraft.world.level.block.entity.SculkSensorBlockEntity) level.getBlockEntity(sensor);
                helper.assertTrue(realSensor.getVibrationData().getCurrentVibration() == null, "restored real sensor holds a vibration: "
                    + realSensor.getVibrationData().getCurrentVibration());
                helper.assertTrue(!java.util.regex.Pattern.compile("[{,]event:").matcher(realSensor.saveWithFullMetadata(level.registryAccess()).toString()).find(), "restored real sensor has a pending candidate: "
                    + realSensor.saveWithFullMetadata(level.registryAccess()));
                helper.runAfterDelay(80, () -> {
                    try {
                        helper.assertTrue(level.getBlockState(lamp).is(net.minecraft.world.level.block.Blocks.REDSTONE_LAMP)
                            && !level.getBlockState(lamp).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "the real lamp lit after the recording (stale vibration in the sensor)");
                        helper.assertTrue(level.getBlockState(sensor).getValue(net.minecraft.world.level.block.SculkSensorBlock.PHASE)
                            == net.minecraft.world.level.block.state.properties.SculkSensorPhase.INACTIVE, "the real sensor activated after the recording");
                    } finally {
                        CauseTrackingTest.forceChunks(helper, false);
                    }
                    helper.succeed();
                });
                return;
            } finally {
                if (false) {
                    CauseTrackingTest.forceChunks(helper, false);
                }
            }
        })), 0);
    }

    // ---- step 3 ruling: the ghost's game time is the recording's start ----------------------------------------------

    /**
     * Ruling "game time": the ghost's game time is the real game time at the RECORDING START, and each copied pending tick
     * keeps its remaining delay (its trigger tick moves by the same difference). The daylight detector acts on
     * {@code gameTime % 20} (DaylightDetectorBlock.java:111), so a ghost whose game time was shifted by anything but a
     * multiple of 20 would flip it on another tick.
     *
     * <p>Machine: a lever on stone (on), a repeater (delay 1) and a redstone lamp lit by it; a daylight detector next to
     * the lamp, its power set to a wrong value so that its ticker flips it on the first game tick with
     * {@code gameTime % 20 == 0}. The recording starts at {@code gameTime % 20 == 19}, so the flip is the first tick of the run
     * (t+1), before the repeater turns off (t+2): the lamp then sees the detector's final power when it loses the repeater's
     * (a chain tick is scheduled or not, depending on it). The recording leaves the detector flipped (it is not chain work,
     * the scoped restore leaves it); the test puts it back to its value at the start before the check, which is the world
     * the recording started from. The check must report exact; with the offset left out ({@code leaveGameTimeOffsetOut})
     * the ghost flips the detector on another tick relative to the run and the check must notice.
     */
    @GameTest(maxTicks = 60000, padding = 24, environment = "ghostpath-gametest:ghostcheck_daylight")
    public void daylightDetectorMachineGhostIsExact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        prepare(helper, 8, 6);
        helper.setBlock(new BlockPos(0, 2, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(0, 3, 2), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.POWERED, true));
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, 1));
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.REDSTONE_LAMP);
        helper.setBlock(new BlockPos(2, 2, 3), Blocks.DAYLIGHT_DETECTOR);
        BlockPos lever = helper.absolutePos(new BlockPos(0, 3, 2));
        BlockPos lamp = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos detector = helper.absolutePos(new BlockPos(2, 2, 3));
        whenTicking(helper, CORNERS, TestSupport.once(() -> TestSupport.whenSavesFree(helper, () -> TestSupport.later(helper, 60, () -> {
            int settled = level.getBlockState(detector).getValue(DaylightDetectorBlock.POWER);
            int wrong = settled == 0 ? 5 : 0;
            helper.assertTrue(level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "the lamp is not lit before the recording");
            LOG.info("[ghostpath] daylight machine: detector settled at power {}, set to {} before the recording", settled, wrong);
            whenGameTimeEnds19(helper, level, TestSupport.once(() -> {
                BlockState wrongState = level.getBlockState(detector).setValue(DaylightDetectorBlock.POWER, wrong);
                level.setBlock(detector, wrongState, 2);
                record(helper, level, lever, rec -> {
                    try {
                        helper.assertTrue(rec.startGameTime() % 20 == 19, "recording did not start at gameTime % 20 == 19: " + rec.startGameTime());
                        helper.assertTrue(level.getBlockState(detector).getValue(DaylightDetectorBlock.POWER) == settled,
                            "the real detector did not flip during the recording: " + level.getBlockState(detector));
                        helper.assertTrue(rec.footprint().contains(detector), "the chain never read the detector");
                        // the world as the recording started from it: the flip is not chain work and stays after the restore
                        level.setBlock(detector, wrongState, 2);
                        long offset = level.getGameTime() - rec.startGameTime();
                        helper.assertTrue(offset % 20 != 0, "game time moved by a multiple of 20 (" + offset + "): the test would prove nothing");
                        GhostCheck.Result exact = GhostCheck.run(level, rec);
                        LOG.info("[ghostpath] daylight machine, {} ticks recorded, offset {}:\n{}", rec.ticks(), offset, String.join("\n", exact.lines()));
                        assertExact(helper, rec, exact);
                        level.setBlock(detector, wrongState, 2);
                        GhostCheck.Options leaveOut = options();
                        leaveOut.leaveGameTimeOffsetOut = true;
                        GhostCheck.Result broken = GhostCheck.run(level, rec, leaveOut);
                        LOG.info("[ghostpath] break-it 'game time offset left out': {}", broken.lines().getFirst());
                        helper.assertTrue(broken.ran() && !broken.exact() && broken.first() != null,
                            "with the game time offset left out the check still says exact:\n" + String.join("\n", broken.lines()));
                        helper.succeed();
                    } finally {
                        CauseTrackingTest.forceChunks(helper, false);
                    }
                });
            }), 0);
        }))), 0);
    }

    private static void whenGameTimeEnds19(GameTestHelper helper, ServerLevel level, Runnable then, int waited) {
        if (level.getGameTime() % 20 == 19) {
            then.run();
        } else if (waited > 100) {
            helper.fail("game time never reached 19 mod 20");
        } else {
            TestSupport.later(helper, 1, () -> whenGameTimeEnds19(helper, level, then, waited + 1));
        }
    }

    /**
     * Copied pending ticks keep their remaining delay when the ghost's game time is not the real one: a repeater chain is
     * copied mid-pulse into a ghost whose game time is 1000 less; every pending block tick has the same remaining delay
     * ({@code triggerTick - gameTime}) in both, and so does the lamp at the end of the chain on the same relative tick.
     */
    @GameTest(maxTicks = 60000, padding = 24, environment = "ghostpath-gametest:ghostcheck_shift")
    public void copiedTicksKeepTheirRemainingDelayWithShiftedGameTime(GameTestHelper helper) throws java.io.IOException {
        ServerLevel level = helper.getLevel();
        prepare(helper, 8, 6);
        int[] delays = {1, 3, 2, 4};
        for (int i = 0; i < delays.length; i++) {
            helper.setBlock(new BlockPos(1 + i, 2, 2), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, delays[i]));
        }
        helper.setBlock(new BlockPos(5, 2, 2), Blocks.REDSTONE_LAMP);
        BlockPos lamp = helper.absolutePos(new BlockPos(5, 2, 2));
        whenTicking(helper, CORNERS, TestSupport.once(() -> TestSupport.whenSavesFree(helper, () -> {
            helper.setBlock(new BlockPos(0, 2, 2), Blocks.REDSTONE_BLOCK);
            TestSupport.later(helper, 2, () -> helper.setBlock(new BlockPos(0, 2, 2), Blocks.AIR));
            TestSupport.later(helper, 6, () -> {
                long shift = -1000;
                GhostLevel ghost;
                ChunkPos lo = ChunkPos.containing(helper.absolutePos(new BlockPos(-1, 2, 0)));
                ChunkPos hi = ChunkPos.containing(helper.absolutePos(new BlockPos(8, 2, 6)));
                try {
                    ghost = GhostLevel.create(level);
                    GhostCopy.copy(level, ghost, lo, hi, new GhostCopy.Start(null, null, level.getGameTime() + shift));
                } catch (java.io.IOException e) {
                    helper.fail("ghost: " + e);
                    return;
                }
                try {
                    List<Long> real = remainingDelays(level, lo, hi);
                    List<Long> copy = remainingDelays(ghost, lo, hi);
                    LOG.info("[ghostpath] shifted copy: remaining delays real {} ghost {}, game time real {} ghost {}", real, copy, level.getGameTime(), ghost.getGameTime());
                    helper.assertTrue(!real.isEmpty(), "no pending ticks at the copy: not mid-pulse");
                    helper.assertTrue(ghost.getGameTime() == level.getGameTime() + shift, "ghost game time " + ghost.getGameTime());
                    helper.assertTrue(real.equals(copy), "remaining delays differ: real " + real + ", ghost " + copy);
                    int[] on = {-1, -1};
                    long startReal = level.getGameTime();
                    long startGhost = ghost.getGameTime();
                    for (int i = 0; i < 40; i++) {
                        ghost.tickGhost();
                        if (on[1] < 0 && ghost.getBlockState(lamp).getValue(RedstoneLampBlock.LIT)) {
                            on[1] = i + 1;
                        }
                    }
                    for (int i = 0; i < 40; i++) {
                        TestSupport.later(helper, i + 1, () -> {
                            if (on[0] < 0 && level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT)) {
                                on[0] = (int) (level.getGameTime() - startReal);
                            }
                        });
                    }
                    TestSupport.later(helper, 42, () -> {
                        ghost.discard();
                        CauseTrackingTest.forceChunks(helper, false);
                        LOG.info("[ghostpath] shifted copy: lamp lit after +{} real, +{} ghost", on[0], on[1]);
                        helper.assertTrue(on[0] > 0 && on[0] == on[1], "lamp lit on different ticks: real +" + on[0] + ", ghost +" + on[1]);
                        helper.succeed();
                    });
                } catch (RuntimeException e) {
                    ghost.discard();
                    throw e;
                }
            });
        })), 0);
    }

    @SuppressWarnings("unchecked")
    private static List<Long> remainingDelays(ServerLevel level, ChunkPos lo, ChunkPos hi) {
        List<Long> out = new ArrayList<>();
        for (var tickContainer : ((obsidivision.ghostpath.mixin.LevelTicksAccessor<Object>) (Object) level.getBlockTicks()).ghostpath$allContainers().values()) {
            // only the copied chunks: other tests' leftovers sit elsewhere in the level
            tickContainer.getAll().filter(t -> (t.pos().getX() >> 4) >= lo.x() && (t.pos().getX() >> 4) <= hi.x()
                && (t.pos().getZ() >> 4) >= lo.z() && (t.pos().getZ() >> 4) <= hi.z()).forEach(t -> out.add(t.triggerTick() - level.getGameTime()));
        }
        java.util.Collections.sort(out);
        return out;
    }

    // ---- break-it: the check has to notice ----------------------------------------------------------------------

    private static GhostCheck.Options options() {
        return new GhostCheck.Options();
    }

    private static void expectDifference(GameTestHelper helper, String name, GhostCheck.Result r, long tick, String what, String where) {
        LOG.info("[ghostpath] break-it '{}': {}", name, r.lines().getFirst());
        helper.assertTrue(r.ran() && !r.exact() && r.first() != null, name + ": the check did not notice:\n" + String.join("\n", r.lines()));
        GhostCheck.Difference d = r.first();
        helper.assertTrue(what.equals(d.what()), name + ": wrong kind of difference: " + d.what() + " (wanted " + what + ")\n" + String.join("\n", r.lines()));
        helper.assertTrue(tick < 0 || d.tick() == tick, name + ": wrong tick: t+" + d.tick() + " (wanted t+" + tick + ")\n" + String.join("\n", r.lines()));
        helper.assertTrue(where == null || d.where().contains(where), name + ": wrong place: '" + d.where() + "' (wanted " + where + ")\n" + String.join("\n", r.lines()));
        helper.assertTrue(r.lines().getFirst().startsWith("Ghost check: first difference at t+" + d.tick() + ", phase " + d.phase()), name + ": summary line: " + r.lines().getFirst());
    }

    private static GhostCheck.GhostRun with(GhostCheck.GhostRun g, UnaryOperator<List<ChainEvent>> events, UnaryOperator<List<CauseTracker.EntityTrace>> trace,
                                            UnaryOperator<Map<BlockPos, Set<CauseTracker.ReadKind>>> reads, UnaryOperator<Set<AABB>> areas,
                                            UnaryOperator<Set<CauseTracker.Column>> columns, UnaryOperator<Set<BlockPos>> writes, UnaryOperator<CauseTracker.Pending> pending) {
        return new GhostCheck.GhostRun(events.apply(g.events()), trace.apply(g.trace()), writes.apply(g.writes()), reads.apply(g.reads()), g.readFirstTicks(),
            areas.apply(g.entityAreas()), columns.apply(g.heightmapColumns()), pending.apply(g.pending()), g.gaps(), g.ticks());
    }

    private static GhostCheck.GhostRun tamperTrace(GhostCheck.GhostRun g, UnaryOperator<List<CauseTracker.EntityTrace>> f) {
        return with(g, e -> e, f, r -> r, a -> a, c -> c, w -> w, p -> p);
    }

    /**
     * The 1b machine, recorded once and checked many times. Each variant breaks the ghost in one way; the check must
     * report a difference of the right kind at the right tick and place (computed here from the real recording).
     */
    @GameTest(maxTicks = 60000, padding = 24, environment = "ghostpath-gametest:ghostcheck_break")
    public void checkNamesTickAndPositionOfWhatDiffers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        Runnable restoreWeather = pinRain(level);
        CauseTrackingTest.buildMachine(helper, BlockPos.ZERO, true);
        BlockPos lever = helper.absolutePos(CauseTrackingTest.trigger());
        CauseTrackingTest.whenEntityTicking(helper, () -> record(helper, level, lever, rec -> {
            try {
                GhostCheck.Result base = GhostCheck.run(level, rec);
                assertExact(helper, rec, base);

                // 1. one block changed in the ghost after the copy: repeater 4 becomes stone
                BlockPos changed = helper.absolutePos(new BlockPos(4, 2, 1));
                ChainEvent firstAtChanged = rec.events().stream().filter(e -> changed.equals(e.pos())).findFirst().orElseThrow();
                GhostCheck.Options o = options();
                o.afterCopy = ghost -> ghost.setBlock(changed, Blocks.STONE.defaultBlockState(), 2 | 16);
                GhostCheck.Result r = GhostCheck.run(level, rec, o);
                // The check is more exact than a first guess: the repeater BEFORE the changed one schedules its tick with another
                // priority when the block in front of it is no repeater (DiodeBlock.getDelayPriority), one tick earlier than the
                // first event at the changed position itself. So: a TICK_SCHEDULED, at or before that event, next to the changed block.
                helper.assertTrue(r.first() != null && r.first().what().equals("TICK_SCHEDULED") && r.first().tick() <= firstAtChanged.tick()
                    && Math.abs(r.first().tick() - firstAtChanged.tick()) <= 3
                    && r.first().real().contains("EXTREMELY_HIGH") && r.first().ghost().contains("priority=HIGH"),
                    "block changed in the ghost: wrong difference:\n" + String.join("\n", r.lines()));
                boolean beside = false;
                for (Direction d : Direction.values()) {
                    beside |= r.first().where().equals(changed.relative(d).toShortString());
                }
                helper.assertTrue(beside || r.first().where().equals(changed.toShortString()), "difference not at the changed block or beside it: " + r.first().where());
                helper.assertTrue(!r.first().phase().isEmpty(), "no phase");
                LOG.info("[ghostpath] changed block: first real event at it {}; check says: {}", firstAtChanged, r.lines().getFirst());

                // 2. another seed / 3. kick off: the TNT's first state differs
                ChainEvent firstTnt = rec.events().stream().filter(e -> e.kind() == ChainEvent.Kind.ENTITY_STATE).findFirst().orElseThrow();
                o = options();
                o.seedOverride = rec.seed() + 1;
                expectDifference(helper, "other seed", GhostCheck.run(level, rec, o), -1, "ENTITY_STATE", null);
                o = options();
                o.kickOverride = !rec.kickApplied();
                r = GhostCheck.run(level, rec, o);
                expectDifference(helper, "kick flipped", r, firstTnt.tick(), "ENTITY_STATE", null);

                // 4. no rain in the ghost: the light and heightmap reads of the real run do not happen there
                o = options();
                o.afterCopy = ghost -> {
                    ghost.getWeatherData().setRaining(false);
                    ghost.setRainLevel(0.0F);
                    ((LevelWeatherAccessor) (Object) ghost).ghostpath$setORainLevel(0.0F);
                };
                r = GhostCheck.run(level, rec, o);
                LOG.info("[ghostpath] no rain in the ghost: {}", r.lines());
                helper.assertTrue(r.ran() && !r.exact(), "a dry ghost was not noticed:\n" + String.join("\n", r.lines()));

                // 5. tampered ghost data: each part of the compare, on its own
                List<CauseTracker.EntityTrace> trace = rec.entityTrace();
                int k = trace.size() / 2;
                CauseTracker.EntityTrace entry = trace.get(k);
                o = options();
                o.tamper = g -> tamperTrace(g, t -> {
                    List<CauseTracker.EntityTrace> l = new ArrayList<>(t);
                    CauseTracker.EntityTrace e = l.get(k);
                    l.set(k, new CauseTracker.EntityTrace(e.seq(), e.tick(), e.phase(), e.index(), e.type(), e.state().replace("fall", "fell").replace("tickCount=", "tickCount=9")));
                    return l;
                });
                expectDifference(helper, "entity state only", GhostCheck.run(level, rec, o), entry.tick(), "entity state", "#" + entry.index());

                int m = rec.events().size() / 2;
                ChainEvent dropped = rec.events().get(m);
                o = options();
                o.tamper = g -> with(g, e -> {
                    List<ChainEvent> l = new ArrayList<>(e);
                    l.remove(m);
                    return l;
                }, t -> t, x -> x, a -> a, c -> c, w -> w, p -> p);
                expectDifference(helper, "event missing", GhostCheck.run(level, rec, o), dropped.tick(), dropped.kind().name(), null);

                BlockPos bogus = lever.offset(40, 0, 40);
                o = options();
                o.tamper = g -> with(g, e -> e, t -> t, x -> {
                    Map<BlockPos, Set<CauseTracker.ReadKind>> m2 = new java.util.HashMap<>(x);
                    m2.put(bogus, Set.of(CauseTracker.ReadKind.BLOCK));
                    return m2;
                }, a -> a, c -> c, w -> w, p -> p);
                expectDifference(helper, "extra read", GhostCheck.run(level, rec, o), -1, "footprint read", bogus.toShortString());

                o = options();
                o.tamper = g -> with(g, e -> e, t -> t, x -> {
                    Map<BlockPos, Set<CauseTracker.ReadKind>> m2 = new java.util.HashMap<>();
                    x.forEach((p, kinds) -> {
                        Set<CauseTracker.ReadKind> s = new HashSet<>(kinds);
                        s.remove(CauseTracker.ReadKind.LIGHT);
                        m2.put(p, s);
                    });
                    return m2;
                }, a -> a, c -> c, w -> w, p -> p);
                expectDifference(helper, "no light reads", GhostCheck.run(level, rec, o), -1, "light reads (presence)", null);

                o = options();
                o.tamper = g -> with(g, e -> e, t -> t, x -> x, a -> a, c -> {
                    Set<CauseTracker.Column> s = new HashSet<>(c);
                    s.add(new CauseTracker.Column(12345, 6789));
                    return s;
                }, w -> w, p -> p);
                expectDifference(helper, "heightmap column", GhostCheck.run(level, rec, o), -1, "heightmap columns", null);

                o = options();
                o.tamper = g -> with(g, e -> e, t -> t, x -> x, a -> {
                    Set<AABB> s = new HashSet<>(a);
                    s.remove(s.iterator().next());
                    return s;
                }, c -> c, w -> w, p -> p);
                expectDifference(helper, "entity box", GhostCheck.run(level, rec, o), -1, "entity-query boxes", null);

                o = options();
                o.tamper = g -> with(g, e -> e, t -> t, x -> x, a -> a, c -> c, w -> {
                    Set<BlockPos> s = new HashSet<>(w);
                    s.add(bogus);
                    return s;
                }, p -> p);
                expectDifference(helper, "extra write", GhostCheck.run(level, rec, o), -1, "written positions", null);

                o = options();
                o.tamper = g -> with(g, e -> e, t -> t, x -> x, a -> a, c -> c, w -> w, p -> new CauseTracker.Pending(1, 0, 0, 0));
                expectDifference(helper, "pending at end", GhostCheck.run(level, rec, o), -1, "chain work pending at the end", null);

                // 6. after all that, an untouched ghost is exact again (nothing leaked between runs)
                assertExact(helper, rec, GhostCheck.run(level, rec));
                helper.assertTrue(GhostLevel.strayPackets() == 0, "stray packets");
            } finally {
                CauseTrackingTest.forceChunks(helper, false);
                restoreWeather.run();
            }
            helper.succeed();
        }));
    }

    // ---- gaps and clocks (rulings 2 and 3) --------------------------------------------------------------------------

    private static GhostLevel ghostOf(GameTestHelper helper, ServerLevel level, GhostCopy.Start start) throws java.io.IOException {
        ChunkPos c = ChunkPos.containing(helper.absolutePos(new BlockPos(2, 2, 2)));
        GhostLevel ghost = GhostLevel.create(level);
        GhostCopy.copy(level, ghost, c, c, start);
        return ghost;
    }

    /**
     * Ruling 3: world border, POI, players and the lookup by dimension key are not copied; each is logged as a gap when
     * chain work in the ghost uses it (once per tick and kind), and not when anything else does (the real level, ghost
     * infrastructure, work outside the chain). The chunk map's own players() call when chain work spawns an entity is not a use.
     */
    @GameTest(maxTicks = 60000, padding = 24, environment = "ghostpath-gametest:ghostcheck_gaps")
    public void ghostGapsAreLoggedWhenChainWorkHitsThem(GameTestHelper helper) throws java.io.IOException {
        ServerLevel level = helper.getLevel();
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        whenTicking(helper, CORNERS, () -> {
            GhostLevel ghost;
            try {
                ghost = ghostOf(helper, level, GhostCopy.Start.NOW);
            } catch (java.io.IOException e) {
                helper.fail("ghost: " + e);
                return;
            }
            CauseTracker tracker = CauseTracker.begin(ghost, 1L);
            try {
                // outside the chain: nothing is logged
                ghost.getWorldBorder();
                ghost.getPoiManager();
                ghost.players();
                level.getServer().getLevel(GhostLevel.KEY);
                helper.assertTrue(tracker.gaps().isEmpty(), "gaps logged outside the chain: " + tracker.gaps());
                // the real level is not a ghost
                tracker.runAsChain(() -> {
                    level.getWorldBorder();
                    level.getPoiManager();
                });
                helper.assertTrue(tracker.gaps().isEmpty(), "the real level logged gaps: " + tracker.gaps());
                // chain work in the ghost
                tracker.runAsChain(() -> {
                    ghost.getWorldBorder();
                    ghost.getWorldBorder(); // same tick, same kind: once
                    ghost.getPoiManager();
                    ghost.players();
                    level.getServer().getLevel(GhostLevel.KEY);
                });
                Set<String> kinds = new HashSet<>();
                tracker.gaps().forEach(g -> kinds.add(g.kind()));
                LOG.info("[ghostpath] gaps: {}", tracker.gaps());
                helper.assertTrue(kinds.equals(Set.of("world border", "POI", "players", "level lookup by dimension key")) && tracker.gaps().size() == 4,
                    "gaps: " + tracker.gaps());
                // adding an entity calls players() from the chunk map: no "players" gap for that
                CauseTracker spawning = tracker;
                int before = (int) spawning.gaps().stream().filter(g -> g.kind().equals("players")).count();
                ghost.tickGhost();
                tracker.runAsChain(() -> {
                    BlockPos at = helper.absolutePos(new BlockPos(2, 3, 2));
                    Entity item = new net.minecraft.world.entity.item.ItemEntity(ghost, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, new ItemStack(Items.STICK));
                    ghost.addFreshEntity(item);
                });
                helper.assertTrue(tracker.gaps().stream().filter(g -> g.kind().equals("players")).count() == before, "adding an entity logged a players gap: " + tracker.gaps());
            } finally {
                tracker.end();
                ghost.discard();
                CauseTrackingTest.forceChunks(helper, false);
            }
            helper.succeed();
        }, 0);
    }

    /**
     * Ruling 2: the ghost has its own world clocks. At the copy they are the real clocks (or those of the recording's
     * start); the ghost's ticks move only the ghost's; the real clocks do not move; the environment attributes (sun
     * angle, sky light) read the ghost's clock.
     */
    @GameTest(maxTicks = 60000, padding = 24, environment = "ghostpath-gametest:ghostcheck_clock")
    public void ghostClockIsItsOwn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        whenTicking(helper, CORNERS, () -> {
            try {
                long realBefore = level.getOverworldClockTime();
                boolean advances = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME);
                GhostLevel ghost = ghostOf(helper, level, GhostCopy.Start.NOW);
                try {
                    helper.assertTrue(ghost.clockManager() != level.clockManager(), "the ghost shares the server's clock manager");
                    helper.assertTrue(ghost.getOverworldClockTime() == realBefore, "ghost clock " + ghost.getOverworldClockTime() + " != real " + realBefore + " at the copy");
                    for (int i = 0; i < 10; i++) {
                        ghost.tickGhost();
                    }
                    helper.assertTrue(ghost.getOverworldClockTime() == realBefore + (advances ? 10 : 0), "ghost clock after 10 ticks: " + ghost.getOverworldClockTime() + " (real was " + realBefore + ")");
                    helper.assertTrue(level.getOverworldClockTime() == realBefore, "the real clock moved while the ghost ticked: " + level.getOverworldClockTime());
                } finally {
                    ghost.discard();
                }
                // a start state from the recording: the ghost starts there, not at the real clock now
                PackedClockStates real = level.clockManager().packState();
                java.util.HashMap<Holder<WorldClock>, ClockState> shifted = new java.util.HashMap<>();
                real.clocks().forEach((clock, s) -> shifted.put(clock, new ClockState(s.totalTicks() + 12000, s.partialTick(), s.rate(), s.paused())));
                int differing = 0;
                for (int shift : new int[] {6000, 12000}) {
                    java.util.HashMap<Holder<WorldClock>, ClockState> st = new java.util.HashMap<>();
                    real.clocks().forEach((clock, s) -> st.put(clock, new ClockState(s.totalTicks() + shift, s.partialTick(), s.rate(), s.paused())));
                    GhostLevel g = ghostOf(helper, level, new GhostCopy.Start(null, new PackedClockStates(st)));
                    try {
                        helper.assertTrue(g.getOverworldClockTime() == realBefore + shift, "ghost clock at the start state: " + g.getOverworldClockTime());
                        g.tickGhost(); // invalidates the attribute cache, updates the sky brightness
                        float ghostSky = g.environmentAttributes().getDimensionValue(EnvironmentAttributes.SKY_LIGHT_LEVEL);
                        level.environmentAttributes().invalidateTickCache();
                        float realSky = level.environmentAttributes().getDimensionValue(EnvironmentAttributes.SKY_LIGHT_LEVEL);
                        LOG.info("[ghostpath] clock +{}: sky light level real {} ghost {}", shift, realSky, ghostSky);
                        if (ghostSky != realSky) {
                            differing++;
                        }
                    } finally {
                        g.discard();
                    }
                }
                helper.assertTrue(differing > 0, "the ghost's sky light never followed the ghost's clock (attributes still read the server's?)");
            } catch (java.io.IOException e) {
                helper.fail("ghost: " + e);
                return;
            } finally {
                CauseTrackingTest.forceChunks(helper, false);
            }
            helper.succeed();
        }, 0);
    }

    // ---- ruling 4: ghost entities never reach a client -------------------------------------------------------------

    /**
     * Ghost entities share ids with real ones, so no packet about them may reach any client. The dispenser machine runs
     * with a real (mock) player connected: during the ghost's copy-free work (press and ticks) no packet at all is sent
     * from the server thread ({@code ConnectionSendMixin}); the ghost has no players; none of its entities is tracked by
     * any player, although they are in its chunk map (so the ghost's own id allocation sees them). Positive control: a packet
     * sent inside ghost work is counted.
     */
    @GameTest(maxTicks = 60000, padding = 24, environment = "ghostpath-gametest:ghostcheck_packets")
    @SuppressWarnings("deprecation")
    public void ghostEntitiesAreNeverTrackedOrSent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        buildDispensers(helper);
        BlockPos lever = helper.absolutePos(new BlockPos(2, 2, 3));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.teleportTo(helper.absolutePos(new BlockPos(3, 3, 3)).getX() + 0.5, helper.absolutePos(new BlockPos(3, 3, 3)).getY(), helper.absolutePos(new BlockPos(3, 3, 3)).getZ() + 0.5);
        whenTicking(helper, CORNERS, () -> {
            // positive control: the guard counts a packet sent inside ghost work
            GhostLevel.resetStrayPackets();
            GhostLevel.runWork(() -> player.connection.send(new ClientboundBlockUpdatePacket(lever, level.getBlockState(lever))));
            helper.assertTrue(GhostLevel.strayPackets() == 1, "the packet guard did not see a packet sent inside ghost work: " + GhostLevel.strayPackets());
            GhostLevel.resetStrayPackets();
            helper.assertTrue(level.players().contains(player), "the mock player is not in the level");
            record(helper, level, lever, rec -> {
                int[] seen = {0, 0, 0};
                GhostCheck.Options o = options();
                o.afterEachTick = ghost -> {
                    helper.assertTrue(ghost.players().isEmpty(), "the ghost has players");
                    for (Entity e : ghost.getAllEntities()) {
                        seen[0]++;
                        helper.assertTrue(!ghost.getChunkSource().chunkMap.isTrackedByAnyPlayer(e), "ghost entity tracked by a player: " + e);
                        if (ghost.getChunkSource().chunkMap.hasEntityWithId(e.getId())) {
                            seen[1]++;
                        }
                    }
                    seen[2] = Math.max(seen[2], (int) java.util.stream.StreamSupport.stream(ghost.getAllEntities().spliterator(), false).count());
                };
                GhostCheck.Result r = GhostCheck.run(level, rec, o);
                try {
                    LOG.info("[ghostpath] packets test: {} ghost entity samples (up to {} at once), {} of them in the ghost's chunk map; stray packets {}", seen[0], seen[2], seen[1], GhostLevel.strayPackets());
                    assertExact(helper, rec, r);
                    helper.assertTrue(seen[0] > 100 && seen[1] == seen[0] && seen[2] >= 2, "too few ghost entities to check (test proves little): " + seen[0] + "/" + seen[1] + ", max " + seen[2]);
                    helper.assertTrue(GhostLevel.strayPackets() == 0, "packets while the ghost worked: " + GhostLevel.strayPacketNames());
                } finally {
                    level.getServer().getPlayerList().remove(player);
                    CauseTrackingTest.forceChunks(helper, false);
                }
                helper.succeed();
            });
        }, 0);
    }
}
