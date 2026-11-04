package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.ChainEvent;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.record.RecordingStore;
import obsidivision.ghostpath.record.SaveGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rulings after the cause-tracking gaps: tagged mobs never hold a recording open and their AI draws are "inexact";
 * saving is off during a recording and back afterwards on every way out; the weather is stored in the recording.
 */
public class RecordingRulesTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    private static void whenEntityTicking(GameTestHelper helper, BlockPos abs, Runnable then) {
        if (helper.getLevel().isPositionEntityTicking(abs)) {
            then.run();
        } else {
            helper.runAfterDelay(1, () -> whenEntityTicking(helper, abs, then));
        }
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    private static Map<ServerLevel, Boolean> saveFlags(MinecraftServer server) {
        Map<ServerLevel, Boolean> out = new IdentityHashMap<>();
        for (ServerLevel l : server.getAllLevels()) {
            out.put(l, l.noSave);
        }
        return out;
    }

    private static boolean allSavingOff(MinecraftServer server) {
        for (ServerLevel l : server.getAllLevels()) {
            if (!l.noSave) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code /path machine} on a lever that powers a dispenser with shears aimed at a sheep with its AI on. The sheep
     * is tagged (dispenser), its AI ticks are chain work and draw from its unseeded random ("inexact: mob AI"), but it
     * never counts as pending: the recording ends once the wool has settled, long before the limit.
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:rules_sheep")
    public void shearedSheepWithAiEndsBeforeLimit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
        RecordingStore.clear();
        floor(helper);
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        ((DispenserBlockEntity) level.getBlockEntity(helper.absolutePos(new BlockPos(2, 2, 2)))).setItem(0, new ItemStack(Items.SHEARS));
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        for (BlockPos glass : List.of(new BlockPos(4, 2, 2), new BlockPos(3, 2, 1), new BlockPos(3, 2, 3), new BlockPos(4, 3, 2),
            new BlockPos(3, 3, 1), new BlockPos(3, 3, 3))) {
            helper.setBlock(glass, Blocks.GLASS); // a pen, so the sheep stays in front of the dispenser
        }
        Sheep sheep = helper.spawn(EntityTypes.SHEEP, new BlockPos(3, 2, 2));
        BlockPos lever = helper.absolutePos(new BlockPos(1, 2, 2));
        whenEntityTicking(helper, helper.absolutePos(new BlockPos(3, 2, 2)), () -> helper.runAfterDelay(5, () -> {
            helper.assertTrue(!sheep.isNoAi() && !sheep.isSheared(), "sheep set up wrong");
            MachineCommandTest.Capture out = new MachineCommandTest.Capture();
            MachineCommandTest.runCommand(helper, level, out, lever, () -> {
                Recording r = RecordingStore.latest();
                helper.assertTrue(r != null, "no recording: " + out.lines);
                long tagged = r.events().stream().filter(e -> e.kind() == ChainEvent.Kind.ENTITY_TAGGED && e.detail().contains("minecraft:sheep dispenser shears: shear")).count();
                List<String> warnings = r.events().stream().filter(e -> e.kind() == ChainEvent.Kind.UNSEEDED_DRAW && e.detail().contains("WARNING"))
                    .map(ChainEvent::detail).distinct().toList();
                LOG.info("[ghostpath] sheep machine: {} ticks, limit hit {}, inexact {}, warnings {} at {}; summary {}", r.ticks(), r.limitHit(),
                    r.unseededDrawsInexact(), r.unseededDrawsNotAllowed(), warnings, r.summary());
                helper.assertTrue(tagged == 1, "sheep not tagged by the dispenser: " + tagged);
                helper.assertTrue(!r.limitHit() && r.ticks() < 200, "a tagged mob held the recording open: " + r.ticks() + " ticks, " + r.pendingAtEnd());
                helper.assertTrue(r.unseededDrawsInexact() > 0, "no 'inexact: mob AI' draw from the sheep's AI");
                helper.assertTrue(r.summary().stream().anyMatch(l -> l.startsWith("Inexact:") && l.contains("may not repeat exactly")),
                    "summary does not say the result may not repeat: " + r.summary());
                helper.assertTrue(r.events().stream().noneMatch(e -> e.kind() == ChainEvent.Kind.UNSEEDED_DRAW && e.detail().contains("WARNING")
                    && e.detail().endsWith(CauseTracker.INEXACT_MOB_AI)), "a draw is both a warning and inexact");
                helper.assertTrue(!sheep.isSheared(), "sheep not restored unsheared");
                helper.succeed();
            });
        }));
    }

    /**
     * Saving is off on every level while a recording runs and as before afterwards, for the three ways out: normal
     * end, tick limit, and a server stop ({@code MachineRecorder.abort}, what SERVER_STOPPING calls). The weather when
     * the trigger was pressed is in the recording and its summary.
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:rules_saving")
    public void savingOffDuringRecordingAndWeatherStored(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        RecordingStore.clear();
        PathSettings.kick = true;
        floor(helper);
        // lever -> four repeaters: the recording runs a few ticks (scheduled ticks pending)
        helper.setBlock(new BlockPos(0, 2, 1), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        for (int x = 1; x <= 4; x++) {
            helper.setBlock(new BlockPos(x, 2, 1), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST));
        }
        BlockPos lever = helper.absolutePos(new BlockPos(0, 2, 1));
        WeatherData weather = level.getWeatherData();
        boolean wasRaining = weather.isRaining();
        boolean wasThundering = weather.isThundering();
        int rainTime = weather.getRainTime();
        int thunderTime = weather.getThunderTime();
        float rainLevel = level.getRainLevel(1.0F);
        Runnable restoreWeather = () -> {
            weather.setRaining(wasRaining);
            weather.setThundering(wasThundering);
            weather.setRainTime(rainTime);
            weather.setThunderTime(thunderTime);
            level.setRainLevel(rainLevel);
        };
        weather.setClearWeatherTime(0);
        weather.setRaining(true);
        weather.setThundering(true);
        weather.setRainTime(500_000);
        weather.setThunderTime(400_000);
        level.setRainLevel(1.0F);
        Map<ServerLevel, Boolean> before = saveFlags(server);
        List<String> seen = new ArrayList<>();
        whenEntityTicking(helper, lever, () -> {
            try {
                // 1. normal end
                PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
                MachineCommandTest.Capture out = new MachineCommandTest.Capture();
                MachineCommandTest.runCommand(helper, level, out, lever, () -> {
                    Recording r = RecordingStore.latest();
                    helper.assertTrue(r != null && !r.limitHit(), "first recording failed: " + out.lines);
                    helper.assertTrue(saveFlags(server).equals(before), "saving not back after a normal end: " + saveFlags(server) + " vs " + before);
                    helper.assertTrue(r.weatherAtStart().raining() && r.weatherAtStart().thundering() && r.weatherAtStart().rainTime() == 500_000
                        && r.weatherAtStart().thunderTime() == 400_000 && r.weatherAtStart().rainLevel() == 1.0F,
                        "weather at start not stored: " + r.weatherAtStart());
                    helper.assertTrue(r.weatherAtEnd().rainTime() == 500_000 - r.ticks() || !r.weatherAtStart().advanceWeather(),
                        "weather at end not stored: " + r.weatherAtEnd() + " after " + r.ticks() + " ticks");
                    helper.assertTrue(r.summary().stream().anyMatch(l -> l.startsWith("Weather: rain+thunder")), "weather not in the summary: " + r.summary());
                    seen.add("normal " + r.ticks());
                    // 2. tick limit
                    PathSettings.tickLimit = 1;
                    MachineCommandTest.Capture out2 = new MachineCommandTest.Capture();
                    MachineCommandTest.runCommand(helper, level, out2, lever, () -> {
                        Recording r2 = RecordingStore.latest();
                        helper.assertTrue(r2 != null && r2 != r && r2.limitHit(), "limit 1 not hit: " + out2.lines);
                        helper.assertTrue(saveFlags(server).equals(before), "saving not back after the tick limit");
                        seen.add("limit " + r2.ticks());
                        // 3. server stop in the middle
                        PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
                        RecordingStore.clear();
                        server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack().withSource(new MachineCommandTest.Capture()).withLevel(level)
                                .withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER),
                            "path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
                        helper.assertTrue(MachineRecorder.current() != null && SaveGuard.active() != null && allSavingOff(server),
                            "saving not off during the recording: " + saveFlags(server));
                        MachineRecorder.abort(server);
                        helper.assertTrue(MachineRecorder.current() == null && SaveGuard.active() == null && saveFlags(server).equals(before),
                            "saving not back after a stop: " + saveFlags(server));
                        helper.assertTrue(!level.getBlockState(lever).getValue(LeverBlock.POWERED), "lever not restored after a stop");
                        seen.add("stop");
                        LOG.info("[ghostpath] saving rules: {}; saving flags before/after {}", seen, before.values());
                        restoreWeather.run();
                        helper.succeed();
                    });
                });
                helper.assertTrue(MachineRecorder.current() == null || allSavingOff(server), "saving not off while recording");
            } catch (RuntimeException | Error e) {
                restoreWeather.run();
                throw e;
            }
        });
    }

    /**
     * Probe for the item-settle ruling ("8 ticks on the ground with zero velocity"). A resting item's velocity is
     * never zero in 26.2: on 3 of 4 ticks it does not move (ItemEntity.java:141, only every 4th tick by
     * (tickCount + id) % 4) but gravity is still applied (:129), so its y velocity is -0.04, -0.08, -0.12, and the 4th
     * tick's move resets it. Logs the velocities and checks that no tick had zero velocity.
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:rules_item_probe")
    public void restingItemVelocityProbe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        Vec3 at = helper.absoluteVec(new Vec3(2.5, 2.2, 2.5));
        whenEntityTicking(helper, BlockPos.containing(at), () -> {
            ItemEntity item = new ItemEntity(level, at.x, at.y, at.z, new ItemStack(Items.DIAMOND));
            level.addFreshEntity(item);
            List<String> samples = new ArrayList<>();
            int[] zero = {0};
            int[] grounded = {0};
            for (int t = 100; t < 140; t++) {
                helper.runAfterDelay(t, () -> {
                    Vec3 v = item.getDeltaMovement();
                    if (item.onGround()) {
                        grounded[0]++;
                    }
                    if (v.equals(Vec3.ZERO)) {
                        zero[0]++;
                    }
                    if (samples.size() < 8) {
                        samples.add(item.onGround() + " " + v);
                    }
                });
            }
            helper.runAfterDelay(141, () -> {
                LOG.info("[ghostpath] item probe: 40 ticks after 100, on ground {}, zero velocity {}; first samples {}", grounded[0], zero[0], samples);
                item.discard();
                helper.assertTrue(grounded[0] == 40, "item not resting on the ground: " + grounded[0]);
                helper.assertTrue(zero[0] == 0, "a resting item had zero velocity on " + zero[0] + " ticks: the probe's finding no longer holds");
                helper.succeed();
            });
        });
    }
}
