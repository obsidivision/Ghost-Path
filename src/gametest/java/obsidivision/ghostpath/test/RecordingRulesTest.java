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
                helper.assertTrue(r.events().stream().anyMatch(e -> e.kind() == ChainEvent.Kind.UNSEEDED_DRAW && e.detail().endsWith(CauseTracker.INEXACT_MOB_AI)),
                    "no 'inexact: mob AI' draw from the sheep's AI");
                helper.assertTrue(r.events().stream().anyMatch(e -> e.kind() == ChainEvent.Kind.UNSEEDED_DRAW && e.detail().contains("Sheep.java:176")
                    && e.detail().endsWith(CauseTracker.INEXACT_MOB)), "sheared wool spread not marked 'inexact: mob'");
                helper.assertTrue(r.unseededDrawsNotAllowed() == 0, "warnings left (dispenser slot is seeded, wool is inexact): " + warnings);
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
     * Autosave is held while a recording runs: a non-forced {@code saveEverything} (what the autosave and the pause save
     * call) is skipped and done after the restore; {@code noSave} is never touched. Checked for the three ways out:
     * normal end, tick limit, and a server stop ({@code MachineRecorder.abort}, what SERVER_STOPPING calls; no held save
     * is done then). The weather when the trigger was pressed is in the recording and its summary.
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
                // 1. normal end, with an autosave asked for in the middle
                PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
                MachineCommandTest.Capture out = new MachineCommandTest.Capture();
                int done0 = SaveGuard.heldSavesDone();
                MachineCommandTest.runCommand(helper, level, out, lever, () -> {
                    Recording r = RecordingStore.latest();
                    helper.assertTrue(r != null && !r.limitHit(), "first recording failed: " + out.lines);
                    helper.assertTrue(saveFlags(server).equals(before), "noSave changed by a recording: " + saveFlags(server) + " vs " + before);
                    helper.assertTrue(SaveGuard.active() == null && SaveGuard.heldSavesDone() == done0 + 1, "held autosave not done after the restore");
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
                        helper.assertTrue(MachineRecorder.current() != null && SaveGuard.active() != null && saveFlags(server).equals(before),
                            "autosave not held, or noSave touched, during the recording: " + saveFlags(server));
                        int doneBefore = SaveGuard.heldSavesDone();
                        helper.assertTrue(!server.saveEverything(true, false, false) && SaveGuard.active().heldSaves() == 1,
                            "a non-forced save went through during the recording");
                        MachineRecorder.abort(server);
                        helper.assertTrue(MachineRecorder.current() == null && SaveGuard.active() == null && saveFlags(server).equals(before)
                            && SaveGuard.heldSavesDone() == doneBefore, "autosave hold not ended after a stop, or a held save was done on stop");
                        helper.assertTrue(!level.getBlockState(lever).getValue(LeverBlock.POWERED), "lever not restored after a stop");
                        seen.add("stop");
                        LOG.info("[ghostpath] saving rules: {}; saving flags before/after {}", seen, before.values());
                        restoreWeather.run();
                        helper.succeed();
                    });
                });
                helper.assertTrue(MachineRecorder.current() != null && SaveGuard.active() != null && !server.saveEverything(true, false, false),
                    "autosave not held while recording");
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

    /**
     * Item settle rule: {@code /path machine} on a lever powering a dropper with one stick. The stick lands and the
     * recording ends once it has been on the ground with its position unchanged (within 1e-6) for 8 ticks, before the
     * limit. Logs how many ticks only counted as unchanged because of the 1e-6 tolerance.
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:rules_item_settle")
    public void droppedItemSettlesBeforeLimit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
        RecordingStore.clear();
        floor(helper);
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        ((DispenserBlockEntity) level.getBlockEntity(helper.absolutePos(new BlockPos(2, 2, 2)))).setItem(0, new ItemStack(Items.STICK));
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        BlockPos lever = helper.absolutePos(new BlockPos(1, 2, 2));
        whenEntityTicking(helper, lever, () -> {
            MachineCommandTest.Capture out = new MachineCommandTest.Capture();
            MachineCommandTest.runCommand(helper, level, out, lever, () -> {
                Recording r = RecordingStore.latest();
                helper.assertTrue(r != null, "no recording: " + out.lines);
                List<ChainEvent> states = r.events().stream().filter(e -> e.kind() == ChainEvent.Kind.ENTITY_STATE && e.detail().contains("item=1 minecraft:stick")).toList();
                long lastMove = -1;
                String prev = null;
                for (ChainEvent e : states) {
                    String pos = e.detail().replaceAll(".*pos=\\(([^)]*)\\).*", "$1");
                    if (!pos.equals(prev)) {
                        lastMove = e.tick();
                    }
                    prev = pos;
                }
                LOG.info("[ghostpath] item settle: {} ticks, limit hit {}, {} stick states, last exact position change at t+{}, drift ticks under 1e-6: {}",
                    r.ticks(), r.limitHit(), states.size(), lastMove, r.itemDriftTicks());
                helper.assertTrue(!states.isEmpty(), "no dropped stick in the recording");
                helper.assertTrue(!r.limitHit() && r.ticks() < 200, "the stick did not settle before the limit: " + r.ticks() + " ticks, " + r.pendingAtEnd());
                helper.assertTrue(r.ticks() >= 8, "ended before 8 ticks: " + r.ticks());
                helper.succeed();
            });
        });
    }

    /**
     * Seeded slot choice: three dispensers, each with five different single items, fired by one lever. Recorded twice
     * with {@code /path machine}: the same items come out (the item is in each item's ENTITY_STATE), every event is
     * equal, and no unseeded draw is left. Unseeded, the chance that all three choices repeat is (1/5)^3.
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:rules_dispenser_slots")
    public void dispenserSlotChoiceRepeatsExactly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
        RecordingStore.clear();
        floor(helper);
        List<net.minecraft.world.item.Item> items = List.of(Items.STICK, Items.FEATHER, Items.STRING, Items.FLINT, Items.LEATHER);
        BlockPos[] dispensers = {new BlockPos(2, 2, 3), new BlockPos(3, 2, 2), new BlockPos(3, 2, 4)};
        Direction[] facing = {Direction.WEST, Direction.NORTH, Direction.SOUTH};
        for (int i = 0; i < 3; i++) {
            helper.setBlock(dispensers[i], Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, facing[i]));
            DispenserBlockEntity be = (DispenserBlockEntity) level.getBlockEntity(helper.absolutePos(dispensers[i]));
            for (int slot = 0; slot < items.size(); slot++) {
                be.setItem(slot * 2, new ItemStack(items.get(slot)));
            }
        }
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        BlockPos lever = helper.absolutePos(new BlockPos(3, 2, 3));
        whenEntityTicking(helper, lever, () -> {
            MachineCommandTest.Capture out1 = new MachineCommandTest.Capture();
            MachineCommandTest.runCommand(helper, level, out1, lever, () -> {
                Recording r1 = RecordingStore.latest();
                helper.assertTrue(r1 != null, "first run failed: " + out1.lines);
                MachineCommandTest.Capture out2 = new MachineCommandTest.Capture();
                MachineCommandTest.runCommand(helper, level, out2, lever, () -> {
                    Recording r2 = RecordingStore.latest();
                    helper.assertTrue(r2 != null && r2 != r1, "second run failed: " + out2.lines);
                    List<String> chosen1 = chosenItems(r1);
                    List<String> chosen2 = chosenItems(r2);
                    LOG.info("[ghostpath] dispenser slots: run 1 {}, run 2 {}; unseeded {} / {}; events {} / {}", chosen1, chosen2,
                        r1.unseededDrawsNotAllowed(), r2.unseededDrawsNotAllowed(), r1.events().size(), r2.events().size());
                    helper.assertTrue(chosen1.size() == 3, "not three items dispensed: " + chosen1);
                    helper.assertTrue(r1.unseededDrawsNotAllowed() == 0 && r2.unseededDrawsNotAllowed() == 0, "unseeded draw left: "
                        + r1.events().stream().filter(e -> e.kind() == ChainEvent.Kind.UNSEEDED_DRAW).map(ChainEvent::detail).distinct().toList());
                    helper.assertTrue(chosen1.equals(chosen2), "slot choice differs between runs: " + chosen1 + " vs " + chosen2);
                    for (int i = 0; i < Math.max(r1.events().size(), r2.events().size()); i++) {
                        String a = i < r1.events().size() ? r1.events().get(i).toString() : null;
                        String b = i < r2.events().size() ? r2.events().get(i).toString() : null;
                        helper.assertTrue(a != null && a.equals(b), "runs differ at event " + i + ": " + a + " vs " + b);
                    }
                    helper.succeed();
                });
            });
        });
    }

    /** The item of each dispensed item entity, in order of first appearance. */
    private static List<String> chosenItems(Recording r) {
        Map<String, String> byIndex = new java.util.LinkedHashMap<>();
        for (ChainEvent e : r.events()) {
            if (e.kind() == ChainEvent.Kind.ENTITY_STATE && e.detail().contains(" item=")) {
                String index = e.detail().substring(0, e.detail().indexOf(' '));
                byIndex.putIfAbsent(index, e.detail().replaceAll(".* item=1 ([^ ]+) .*", "$1"));
            }
        }
        return new ArrayList<>(byIndex.values());
    }

    /**
     * A chunk with chain changes unloads in the middle of a recording (its forced ticket is removed by the test while
     * the dispensed TNT is fuming). The TNT is unloaded with it ("chain entity unloaded"), the recording ends, the
     * recorder loads the chunk again with its restore ticket, restores the lever and the dispenser and removes the TNT
     * that was saved into the chunk, by UUID. Checked after the chunk was unloaded and loaded once more.
     */
    @GameTest(maxTicks = 400000, environment = "ghostpath-gametest:rules_unloaded_restore") // chunk loads take real time, ticks run at full speed
    public void unloadedChunkRestored(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
        RecordingStore.clear();
        BlockPos base = helper.absolutePos(BlockPos.ZERO).offset(-1600, 0, 1600);
        int cx = base.getX() >> 4;
        int cz = base.getZ() >> 4;
        BlockPos column = new BlockPos((cx << 4) + 8, 0, (cz << 4) + 8);
        long key = new net.minecraft.world.level.ChunkPos(cx, cz).pack();
        level.setChunkForced(cx, cz, true);
        whenEntityTicking(helper, column.atY(level.getMinY() + 1), () -> {
            BlockPos ground = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, column);
            BlockPos dispenser = ground.above(3);
            BlockPos lever = dispenser.west();
            net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(column.getX() - 8, level.getMinY(), column.getZ() - 8,
                column.getX() + 8, level.getMaxY(), column.getZ() + 8);
            for (net.minecraft.world.entity.Entity old : level.getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class, area, e -> true)) {
                old.discard(); // a TNT left in this chunk by an earlier failed run (the world is kept)
            }
            for (BlockPos p : BlockPos.betweenClosed(dispenser.offset(-2, -1, -2), dispenser.offset(2, 1, 2))) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
            for (BlockPos p : BlockPos.betweenClosed(dispenser.offset(-2, -1, -2), dispenser.offset(2, -1, 2))) {
                level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
            }
            level.setBlock(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.UP), 3);
            ((DispenserBlockEntity) level.getBlockEntity(dispenser)).setItem(0, new ItemStack(Items.TNT));
            level.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR), 3);
            helper.runAfterDelay(2, () -> {
                MinecraftServer server = level.getServer();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(new MachineCommandTest.Capture())
                        .withLevel(level).withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER),
                    "path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
                helper.assertTrue(MachineRecorder.current() != null, "recording did not start");
                waitForTnt(helper, level, area, cx, cz, key, dispenser, lever, 0);
            });
        });
    }

    private static void waitForTnt(GameTestHelper helper, ServerLevel level, net.minecraft.world.phys.AABB area, int cx, int cz, long key,
                                   BlockPos dispenser, BlockPos lever, int waited) {
        List<net.minecraft.world.entity.item.PrimedTnt> tnt = level.getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class, area, e -> true);
        if (tnt.isEmpty()) {
            helper.assertTrue(waited < 40 && MachineRecorder.current() != null, "no TNT dispensed");
            helper.runAfterDelay(1, () -> waitForTnt(helper, level, area, cx, cz, key, dispenser, lever, waited + 1));
            return;
        }
        java.util.UUID tntId = tnt.get(0).getUUID();
        level.setChunkForced(cx, cz, false); // the chunk unloads during the recording
        waitIdle(helper, 0, () -> {
            Recording r = RecordingStore.latest();
            helper.assertTrue(r != null, "no recording");
            LOG.info("[ghostpath] unloaded restore: {} ticks, chunks loaded again {}, entities not restored {}, not followed {}", r.ticks(),
                r.chunksReloaded(), r.entitiesNotRestored(), r.notFollowed());
            helper.assertTrue(r.notFollowed().stream().anyMatch(t -> t.contains("chain entity unloaded") && t.contains("minecraft:tnt")),
                "TNT not logged as unloaded: " + r.notFollowed());
            helper.assertTrue(r.chunksReloaded() >= 1 && r.entitiesNotRestored() == 0, "chunk not loaded for the restore: " + r.summary());
            // look at the chunk as it is saved: wait until it unloaded again, then load it once more
            waitUnloadedThenCheck(helper, level, cx, cz, key, dispenser, lever, tntId, area, 0);
        });
    }

    private static void waitIdle(GameTestHelper helper, int waited, Runnable then) {
        if (MachineRecorder.current() == null) {
            then.run();
        } else {
            helper.assertTrue(waited < 300000, "recording or restore did not finish");
            helper.runAfterDelay(1, () -> waitIdle(helper, waited + 1, then));
        }
    }

    private static void waitUnloadedThenCheck(GameTestHelper helper, ServerLevel level, int cx, int cz, long key, BlockPos dispenser, BlockPos lever,
                                              java.util.UUID tntId, net.minecraft.world.phys.AABB area, int waited) {
        if (level.getChunkSource().getChunkNow(cx, cz) != null) {
            helper.assertTrue(waited < 300000, "chunk never unloaded after the restore");
            helper.runAfterDelay(1, () -> waitUnloadedThenCheck(helper, level, cx, cz, key, dispenser, lever, tntId, area, waited + 1));
            return;
        }
        level.setChunkForced(cx, cz, true);
        waitEntitiesLoaded(helper, level, cx, cz, key, dispenser, lever, tntId, area, 0);
    }

    private static void waitEntitiesLoaded(GameTestHelper helper, ServerLevel level, int cx, int cz, long key, BlockPos dispenser, BlockPos lever,
                                           java.util.UUID tntId, net.minecraft.world.phys.AABB area, int waited) {
        if (level.getChunkSource().getChunkNow(cx, cz) == null || !level.areEntitiesLoaded(key)) {
            helper.assertTrue(waited < 300000, "chunk did not load again");
            helper.runAfterDelay(1, () -> waitEntitiesLoaded(helper, level, cx, cz, key, dispenser, lever, tntId, area, waited + 1));
            return;
        }
        boolean leverOn = level.getBlockState(lever).getValue(LeverBlock.POWERED);
        int tntItems = ((DispenserBlockEntity) level.getBlockEntity(dispenser)).countItem(Items.TNT);
        List<net.minecraft.world.entity.item.PrimedTnt> left = level.getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class, area, e -> true);
        LOG.info("[ghostpath] unloaded restore, reloaded: lever on {}, TNT items {}, primed TNT {} (ours {})", leverOn, tntItems, left.size(),
            level.getEntity(tntId) != null);
        level.setChunkForced(cx, cz, false);
        helper.assertTrue(!leverOn, "lever not restored in the unloaded chunk");
        helper.assertTrue(tntItems == 1, "dispenser contents not restored: " + tntItems);
        helper.assertTrue(left.isEmpty() && level.getEntity(tntId) == null, "spawned TNT saved into the chunk not removed: " + left.size());
        helper.succeed();
    }
}
