package obsidivision.ghostpath.test;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.ChainEvent;
import obsidivision.ghostpath.record.UndoLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cause-tracking gaps closed by the ruling after 1d: block entities whose state the chain changes (1), vibrations (2),
 * items merging and hoppers picking up chain items (5), dispensers changing entities (6), chain things unloaded with
 * their chunk (7), entities leaving the dimension (8), and pending() counting busy chain block entities.
 */
public class ChainGapsTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    private static void whenEntityTicking(GameTestHelper helper, BlockPos abs, Runnable then) {
        if (helper.getLevel().isPositionEntityTicking(abs)) {
            then.run();
        } else {
            helper.runAfterDelay(1, () -> whenEntityTicking(helper, abs, then));
        }
    }

    private static boolean has(CauseTracker t, ChainEvent.Kind kind, BlockPos pos, String detail) {
        return t.events().stream().anyMatch(e -> e.kind() == kind && (pos == null || pos.equals(e.pos())) && e.detail().contains(detail));
    }

    private static String eventsAt(CauseTracker t, BlockPos pos) {
        return t.events().stream().filter(e -> pos.equals(e.pos())).map(ChainEvent::toString).toList().toString();
    }

    /** Ends the tracker if {@code body} throws, so a failed test does not leave tracking active for later tests. */
    private static void guarded(CauseTracker tracker, UndoLog undo, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException | Error e) {
            tracker.end();
            if (undo != null) {
                undo.restore();
            }
            throw e;
        }
    }

    // ---- 1: block state change keeps the block entity ----------------------------------------------------------

    /**
     * A hopper (3 diamonds, facing down into a chest) is disabled by a redstone block beside it. Chain work removes the
     * redstone block; the hopper's ENABLED changes (HopperBlock.checkPoweredState, a setBlock that keeps the block
     * entity), so the hopper joins the chain and its later moves into the chest are chain work. pending() counts it while
     * its cooldown is above 0 and for 8 ticks after the chest last changed. Undo puts everything back.
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:gap_hopper_state")
    public void hopperEnabledByChainIsChainWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestRel = new BlockPos(2, 2, 2);
        BlockPos hopperRel = new BlockPos(2, 3, 2);
        BlockPos powerRel = new BlockPos(3, 3, 2);
        helper.setBlock(chestRel, Blocks.CHEST);
        helper.setBlock(powerRel, Blocks.REDSTONE_BLOCK);
        helper.setBlock(hopperRel, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        BlockPos hopper = helper.absolutePos(hopperRel);
        BlockPos chest = helper.absolutePos(chestRel);
        HopperBlockEntity hopperBe = (HopperBlockEntity) level.getBlockEntity(hopper);
        hopperBe.setItem(0, new ItemStack(Items.DIAMOND, 3));
        whenEntityTicking(helper, hopper, () -> {
            helper.assertTrue(!level.getBlockState(hopper).getValue(HopperBlock.ENABLED), "hopper not disabled before the run");
            UndoLog undo = UndoLog.begin(level);
            CauseTracker tracker = CauseTracker.begin(level, 1L);
            guarded(tracker, undo, () -> tracker.runAsChain(() -> helper.setBlock(powerRel, Blocks.AIR)));
            int[] maxBusy = {0};
            int[] lastBusyTick = {-1};
            for (int t = 1; t <= 45; t++) {
                int tick = t;
                helper.runAfterDelay(t, () -> guarded(tracker, undo, () -> {
                    int busy = tracker.pending().blockEntities();
                    maxBusy[0] = Math.max(maxBusy[0], busy);
                    if (busy > 0) {
                        lastBusyTick[0] = tick;
                    }
                }));
            }
            helper.runAfterDelay(46, () -> guarded(tracker, undo, () -> {
                ChestBlockEntity chestBe = (ChestBlockEntity) level.getBlockEntity(chest);
                int moved = chestBe.countItem(Items.DIAMOND);
                CauseTracker.Pending pending = tracker.pending();
                LOG.info("[ghostpath] hopper state gap: {} diamonds moved; busy max {}, last busy at t+{}; pending at end {}; hopper events {}",
                    moved, maxBusy[0], lastBusyTick[0], pending, eventsAt(tracker, hopper));
                helper.assertTrue(moved == 3, "hopper did not move the diamonds: " + moved);
                helper.assertTrue(has(tracker, ChainEvent.Kind.BLOCK_ENTITY_TAGGED, hopper, "block state changed"),
                    "hopper not tagged when chain work changed its block state: " + eventsAt(tracker, hopper));
                long chestWrites = tracker.events().stream().filter(e -> e.kind() == ChainEvent.Kind.WRITE && chest.equals(e.pos())).count();
                helper.assertTrue(chestWrites >= 3, "the hopper's moves into the chest are not chain work: " + eventsAt(tracker, chest));
                helper.assertTrue(maxBusy[0] > 0 && lastBusyTick[0] >= 20, "pending() did not count the busy hopper/chest: max " + maxBusy[0] + ", last t+" + lastBusyTick[0]);
                helper.assertTrue(!pending.any(), "still pending after the hopper finished: " + pending);
                tracker.end();
                undo.restore();
                helper.assertTrue(((HopperBlockEntity) level.getBlockEntity(hopper)).countItem(Items.DIAMOND) == 3, "hopper contents not restored");
                helper.assertTrue(((ChestBlockEntity) level.getBlockEntity(chest)).countItem(Items.DIAMOND) == 0, "chest contents not restored");
                helper.assertTrue(level.getBlockState(helper.absolutePos(powerRel)).is(Blocks.REDSTONE_BLOCK)
                    && !level.getBlockState(hopper).getValue(HopperBlock.ENABLED), "hopper state or power not restored");
                helper.succeed();
            }));
        });
    }

    /**
     * pending() for a hopper on cooldown: a chain-enabled hopper feeds 3 seeds into a composter, which has no block
     * entity, so after t+8 nothing but the hopper's own transfer cooldown (moves at about t+1, 9, 17) can keep it pending.
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:gap_hopper_cooldown")
    public void hopperCooldownKeepsPending(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos hopperRel = new BlockPos(2, 3, 2);
        BlockPos powerRel = new BlockPos(3, 3, 2);
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.COMPOSTER);
        helper.setBlock(powerRel, Blocks.REDSTONE_BLOCK);
        helper.setBlock(hopperRel, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        BlockPos hopper = helper.absolutePos(hopperRel);
        HopperBlockEntity hopperBe = (HopperBlockEntity) level.getBlockEntity(hopper);
        hopperBe.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 3));
        whenEntityTicking(helper, hopper, () -> {
            CauseTracker tracker = CauseTracker.begin(level, 1L);
            guarded(tracker, null, () -> tracker.runAsChain(() -> helper.setBlock(powerRel, Blocks.AIR)));
            int[] busyAt20 = {-1};
            helper.runAfterDelay(20, () -> busyAt20[0] = tracker.pending().blockEntities());
            helper.runAfterDelay(40, () -> guarded(tracker, null, () -> {
                tracker.end();
                CauseTracker.Pending end = tracker.pending();
                LOG.info("[ghostpath] hopper cooldown: busy at t+20 {}, seeds left {}, pending at end {}", busyAt20[0], hopperBe.countItem(Items.WHEAT_SEEDS), end);
                helper.assertTrue(hopperBe.countItem(Items.WHEAT_SEEDS) == 0, "hopper did not feed the composter");
                helper.assertTrue(busyAt20[0] > 0, "hopper on cooldown not pending at t+20: " + busyAt20[0]);
                helper.assertTrue(!end.any(), "still pending after the hopper finished: " + end);
                helper.succeed();
            }));
        });
    }

    // ---- 2: vibrations ---------------------------------------------------------------------------------------------

    /**
     * A chain piston extends (block event, BLOCK_ACTIVATE game event, PistonBaseBlock.java:173); a sculk sensor four
     * blocks away schedules the vibration, joins the chain, and its activation and its output (a lamp beside it) are
     * chain work.
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:gap_vibration")
    public void chainPistonTriggersSculkSensor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.SCULK_SENSOR);
        helper.setBlock(new BlockPos(5, 2, 4), Blocks.REDSTONE_LAMP);
        BlockPos sensor = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos lamp = helper.absolutePos(new BlockPos(5, 2, 4));
        whenEntityTicking(helper, sensor, () -> helper.runAfterDelay(5, () -> {
            CauseTracker tracker = CauseTracker.begin(level, 1L);
            guarded(tracker, null, () -> tracker.runAsChain(() -> helper.setBlock(new BlockPos(0, 2, 1), Blocks.REDSTONE_BLOCK)));
            // the sensor is neither a hopper nor a piston: it is pending only through "changed in the last 8 ticks"
            // (setChanged each tick of the travel, last one at activation, t+5; next change at the cooldown tick, t+35)
            int[] busy = new int[2];
            helper.runAfterDelay(4, () -> busy[0] = tracker.pending().blockEntities());
            helper.runAfterDelay(22, () -> busy[1] = tracker.pending().blockEntities());
            helper.runAfterDelay(40, () -> guarded(tracker, null, () -> {
                tracker.end();
                LOG.info("[ghostpath] vibration gap: busy block entities at t+4 {}, at t+22 {}", busy[0], busy[1]);
                helper.assertTrue(busy[0] > 0, "sculk sensor changed in the last 8 ticks not pending: " + busy[0]);
                helper.assertTrue(busy[1] == 0, "sculk sensor still pending more than 8 ticks after its last change: " + busy[1]);
                LOG.info("[ghostpath] vibration gap: sensor events {}; lamp events {}", eventsAt(tracker, sensor), eventsAt(tracker, lamp));
                helper.assertTrue(has(tracker, ChainEvent.Kind.BLOCK_ENTITY_TAGGED, sensor, "vibration"),
                    "sculk sensor not tagged by the chain vibration: " + eventsAt(tracker, sensor));
                helper.assertTrue(has(tracker, ChainEvent.Kind.WRITE, sensor, "sculk_sensor_phase=active"),
                    "sculk sensor activation is not chain work: " + eventsAt(tracker, sensor));
                helper.assertTrue(has(tracker, ChainEvent.Kind.WRITE, lamp, "lit=true"), "sensor output (lamp) is not chain work: " + eventsAt(tracker, lamp));
                helper.succeed();
            }));
        }));
    }

    // ---- 5: items ----------------------------------------------------------------------------------------------------

    /** A chain item (1 stone) merges into a resting non-chain item (10 stone): the receiving item joins the chain. */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:gap_item_merge")
    public void chainItemMergeTagsReceiver(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        Vec3 at = helper.absoluteVec(new Vec3(2.5, 2.0, 2.5));
        whenEntityTicking(helper, BlockPos.containing(at), () -> {
            ItemEntity big = new ItemEntity(level, at.x, at.y, at.z, new ItemStack(Items.STONE, 10));
            big.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(big);
            CauseTracker tracker = CauseTracker.begin(level, 1L);
            ItemEntity[] small = new ItemEntity[1];
            guarded(tracker, null, () -> tracker.runAsChain(() -> {
                small[0] = new ItemEntity(level, at.x + 0.2, at.y, at.z, new ItemStack(Items.STONE, 1));
                small[0].setDeltaMovement(Vec3.ZERO);
                level.addFreshEntity(small[0]);
            }));
            helper.runAfterDelay(60, () -> guarded(tracker, null, () -> {
                tracker.end();
                LOG.info("[ghostpath] merge gap: big {} (removed {}), small removed {}; chain {}", big.getItem().getCount(), big.isRemoved(),
                    small[0].isRemoved(), tracker.isChainEntity(big));
                helper.assertTrue(small[0].isRemoved() && big.getItem().getCount() == 11, "items did not merge: " + big.getItem().getCount());
                helper.assertTrue(tracker.isChainEntity(big) && has(tracker, ChainEvent.Kind.ENTITY_TAGGED, null, "merged chain item"),
                    "receiving item not tagged after a chain item merged into it");
                big.discard();
                helper.succeed();
            }));
        });
    }

    /**
     * A chain item lands on a bottom slab above a hopper (inside the hopper's suck area, not inside the hopper, so the
     * pickup is the hopper's own tick, HopperBlockEntity.suckInItems). The hopper joins the chain; its move into the chest
     * below is chain work.
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:gap_hopper_pickup")
    public void hopperPickingUpChainItemIsChainWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestRel = new BlockPos(2, 2, 2);
        BlockPos hopperRel = new BlockPos(2, 3, 2);
        helper.setBlock(chestRel, Blocks.CHEST);
        helper.setBlock(hopperRel, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        helper.setBlock(new BlockPos(2, 4, 2), Blocks.STONE_SLAB);
        BlockPos hopper = helper.absolutePos(hopperRel);
        BlockPos chest = helper.absolutePos(chestRel);
        Vec3 drop = helper.absoluteVec(new Vec3(2.5, 4.6, 2.5));
        whenEntityTicking(helper, hopper, () -> {
            CauseTracker tracker = CauseTracker.begin(level, 1L);
            ItemEntity[] item = new ItemEntity[1];
            guarded(tracker, null, () -> tracker.runAsChain(() -> {
                item[0] = new ItemEntity(level, drop.x, drop.y, drop.z, new ItemStack(Items.DIAMOND, 2));
                item[0].setDeltaMovement(Vec3.ZERO);
                level.addFreshEntity(item[0]);
            }));
            helper.runAfterDelay(40, () -> guarded(tracker, null, () -> {
                tracker.end();
                int inChest = ((ChestBlockEntity) level.getBlockEntity(chest)).countItem(Items.DIAMOND);
                LOG.info("[ghostpath] hopper pickup gap: item removed {}, {} in chest; hopper events {}", item[0].isRemoved(), inChest, eventsAt(tracker, hopper));
                helper.assertTrue(item[0].isRemoved() && inChest == 2, "hopper did not pick up and pass on the item: chest " + inChest);
                helper.assertTrue(has(tracker, ChainEvent.Kind.BLOCK_ENTITY_TAGGED, hopper, "picked up chain item"),
                    "hopper not tagged after picking up a chain item: " + eventsAt(tracker, hopper));
                helper.assertTrue(has(tracker, ChainEvent.Kind.WRITE, chest, "block entity data"),
                    "the hopper's move into the chest is not chain work: " + eventsAt(tracker, chest));
                helper.succeed();
            }));
        });
    }

    // ---- 6: dispensers change entities -----------------------------------------------------------------------------

    /**
     * Two chain-powered dispensers: shears at a sheep, an iron helmet at an armor stand. Both targets join the chain,
     * and the undo log restores both (the changes go through no other hooked method).
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:gap_dispenser")
    public void dispenserChangesTagTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(1, 2, 4), Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        ((DispenserBlockEntity) level.getBlockEntity(helper.absolutePos(new BlockPos(1, 2, 1)))).setItem(0, new ItemStack(Items.SHEARS));
        ((DispenserBlockEntity) level.getBlockEntity(helper.absolutePos(new BlockPos(1, 2, 4)))).setItem(0, new ItemStack(Items.IRON_HELMET));
        Sheep sheep = helper.spawn(EntityTypes.SHEEP, new BlockPos(2, 2, 1));
        sheep.setNoAi(true);
        ArmorStand stand = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(2, 2, 4));
        whenEntityTicking(helper, helper.absolutePos(new BlockPos(2, 2, 4)), () -> helper.runAfterDelay(3, () -> {
            UndoLog undo = UndoLog.begin(level);
            CauseTracker tracker = CauseTracker.begin(level, 1L);
            guarded(tracker, undo, () -> tracker.runAsChain(() -> {
                helper.setBlock(new BlockPos(0, 2, 1), Blocks.REDSTONE_BLOCK);
                helper.setBlock(new BlockPos(0, 2, 4), Blocks.REDSTONE_BLOCK);
            }));
            helper.runAfterDelay(10, () -> guarded(tracker, undo, () -> {
                tracker.end();
                LOG.info("[ghostpath] dispenser gap: sheared {}, helmet {}; tags {}", sheep.isSheared(), stand.getItemBySlot(EquipmentSlot.HEAD),
                    tracker.events().stream().filter(e -> e.kind() == ChainEvent.Kind.ENTITY_TAGGED).map(ChainEvent::toString).toList());
                helper.assertTrue(sheep.isSheared() && stand.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "dispensers did not act");
                helper.assertTrue(tracker.isChainEntity(sheep) && has(tracker, ChainEvent.Kind.ENTITY_TAGGED, null, "dispenser shears: shear"),
                    "sheared sheep not tagged");
                helper.assertTrue(tracker.isChainEntity(stand) && has(tracker, ChainEvent.Kind.ENTITY_TAGGED, null, "dispenser equipment"),
                    "equipped armor stand not tagged");
                undo.restore();
                Sheep sheepNow = (Sheep) level.getEntity(sheep.getUUID());
                ArmorStand standNow = (ArmorStand) level.getEntity(stand.getUUID());
                helper.assertTrue(sheepNow != null && !sheepNow.isSheared(), "sheep not restored unsheared");
                helper.assertTrue(standNow != null && standNow.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "armor stand helmet not restored: " +
                    (standNow == null ? null : standNow.getItemBySlot(EquipmentSlot.HEAD)));
                helper.succeed();
            }));
        }));
    }

    // ---- 7: unloaded with its chunk -------------------------------------------------------------------------------

    /**
     * Far from everything (no other ticket), a force-loaded chunk gets a chain TNT (long fuse, no gravity) and a chain
     * chest. The chunk is then released and unloads: both are logged as unloaded with position and tick, pending()
     * counts them as gone, and they are listed as not followed. The world is kept between runs, so leftovers from an
     * earlier run are cleared first.
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:gap_unload")
    public void unloadedChainThingsLogged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(BlockPos.ZERO).offset(1600, 0, 1600);
        int cx = base.getX() >> 4;
        int cz = base.getZ() >> 4;
        BlockPos column = new BlockPos((cx << 4) + 8, 0, (cz << 4) + 8);
        level.setChunkForced(cx, cz, true);
        whenEntityTicking(helper, column.atY(level.getMinY() + 1), () -> {
            BlockPos chestPos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column).above(3);
            AABB area = new AABB(column.getX() - 8, level.getMinY(), column.getZ() - 8, column.getX() + 8, level.getMaxY(), column.getZ() + 8);
            for (Entity old : level.getEntitiesOfClass(PrimedTnt.class, area, e -> true)) {
                old.discard();
            }
            level.setBlock(chestPos, Blocks.AIR.defaultBlockState(), 2);
            CauseTracker tracker = CauseTracker.begin(level, 1L);
            PrimedTnt[] tnt = new PrimedTnt[1];
            guarded(tracker, null, () -> tracker.runAsChain(() -> {
                level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 2);
                tnt[0] = new PrimedTnt(level, chestPos.getX() + 0.5, chestPos.getY() + 2, chestPos.getZ() + 0.5, null);
                tnt[0].setNoGravity(true);
                tnt[0].setDeltaMovement(Vec3.ZERO);
                tnt[0].setFuse(20000);
                level.addFreshEntity(tnt[0]);
            }));
            BlockEntity chest = level.getBlockEntity(chestPos);
            helper.runAfterDelay(3, () -> guarded(tracker, null, () -> {
                helper.assertTrue(tracker.isChainBlockEntity(chest) && tracker.isChainEntity(tnt[0]), "chain chest or TNT not tagged");
                level.setChunkForced(cx, cz, false);
                waitUnloaded(helper, tracker, tnt[0], chest, chestPos, 0);
            }));
        });
    }

    private static void waitUnloaded(GameTestHelper helper, CauseTracker tracker, PrimedTnt tnt, BlockEntity chest, BlockPos chestPos, int waited) {
        if (!(tnt.isRemoved() && chest.isRemoved())) {
            helper.runAfterDelay(1, () -> guarded(tracker, null, () -> waitUnloaded(helper, tracker, tnt, chest, chestPos, waited + 1)));
            return;
        }
        tracker.end();
        LOG.info("[ghostpath] unload gap: unloaded after {} ticks (TNT reason {}); not followed {}; pending {}", waited, tnt.getRemovalReason(),
            tracker.notFollowed(), tracker.pending());
        helper.assertTrue(tnt.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK, "TNT removed for another reason: " + tnt.getRemovalReason());
        List<ChainEvent> lost = tracker.notFollowed();
        helper.assertTrue(lost.stream().anyMatch(e -> e.kind() == ChainEvent.Kind.CHAIN_UNLOADED && e.detail().startsWith("chain entity unloaded #0 minecraft:tnt")
                && BlockPos.containing(tnt.position()).equals(e.pos())),
            "no 'chain entity unloaded' event with the TNT's position: " + lost);
        helper.assertTrue(lost.stream().anyMatch(e -> e.kind() == ChainEvent.Kind.CHAIN_UNLOADED && e.detail().startsWith("chain block entity unloaded minecraft:chest")
                && chestPos.equals(e.pos())),
            "no 'chain block entity unloaded' event at the chest: " + lost);
        helper.assertTrue(lost.stream().allMatch(e -> e.tick() > 0), "unload events without their tick: " + lost);
        helper.assertTrue(!tracker.pending().any(), "unloaded chain work still pending: " + tracker.pending());
        helper.succeed();
    }

    // ---- 8: change of dimension --------------------------------------------------------------------------------

    /** A chain TNT is teleported to the nether by chain work: "left dimension" is logged and neither copy is followed. */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:gap_dimension")
    public void leftDimensionNotFollowed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "no nether level");
        Vec3 at = helper.absoluteVec(new Vec3(2.5, 3.0, 2.5));
        whenEntityTicking(helper, BlockPos.containing(at), () -> {
            CauseTracker tracker = CauseTracker.begin(level, 1L);
            PrimedTnt[] tnt = new PrimedTnt[1];
            Entity[] moved = new Entity[1];
            guarded(tracker, null, () -> {
                tracker.runAsChain(() -> {
                    tnt[0] = new PrimedTnt(level, at.x, at.y, at.z, null);
                    tnt[0].setNoGravity(true);
                    tnt[0].setFuse(20000);
                    level.addFreshEntity(tnt[0]);
                });
                helper.assertTrue(tracker.isChainEntity(tnt[0]), "TNT not tagged");
                tracker.runAsChain(() -> moved[0] = tnt[0].teleport(new TeleportTransition(nether, new Vec3(at.x / 8, 100, at.z / 8), Vec3.ZERO, 0f, 0f,
                    TeleportTransition.DO_NOTHING)));
            });
            helper.runAfterDelay(2, () -> guarded(tracker, null, () -> {
                tracker.end();
                LOG.info("[ghostpath] dimension gap: old removed {} ({}), new {}; not followed {}", tnt[0].isRemoved(), tnt[0].getRemovalReason(), moved[0],
                    tracker.notFollowed());
                helper.assertTrue(moved[0] != null && moved[0] != tnt[0] && tnt[0].getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION,
                    "TNT did not change dimension: " + moved[0]);
                helper.assertTrue(has(tracker, ChainEvent.Kind.LEFT_DIMENSION, null, "left dimension #0 minecraft:tnt"),
                    "no 'left dimension' event: " + tracker.eventsAsText(50));
                helper.assertTrue(!tracker.isChainEntity(tnt[0]) && !tracker.isChainEntity(moved[0]), "an entity that left the dimension is still followed");
                helper.assertTrue(tracker.pending().entities() == 0, "entity in another dimension still pending: " + tracker.pending());
                moved[0].discard();
                helper.succeed();
            }));
        });
    }
}
