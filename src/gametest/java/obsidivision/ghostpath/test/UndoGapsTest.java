package obsidivision.ghostpath.test;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import obsidivision.ghostpath.mixin.ServerLevelAccessor;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.UndoLog;

/**
 * Undo-log gaps closed before 1d (R4): pending ticks and queued block events are restored as at begin; an entity hurt
 * by the recording is snapshotted before the damage; a container changed through a reference taken before the
 * recording is snapshotted before the change; a block entity first seen at setChanged() is reported as late.
 */
public class UndoGapsTest {
    private static List<ScheduledTick<Block>> ticksAt(ServerLevel level, BlockPos pos) {
        @SuppressWarnings("unchecked")
        LevelChunkTicks<Block> container = (LevelChunkTicks<Block>) level.getChunkAt(pos).getBlockTicks();
        return container.getAll().filter(t -> t.pos().equals(pos)).toList();
    }

    private static List<BlockEventData> eventsAt(ServerLevel level, List<BlockPos> positions) {
        return ((ServerLevelAccessor) level).ghostpath$blockEvents().stream().filter(e -> positions.contains(e.pos())).toList();
    }

    @GameTest(maxTicks = 200, environment = "ghostpath-gametest:undo_gaps")
    public void ticksEventsHurtAndContainersRestored(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos consumedTick = helper.absolutePos(new BlockPos(1, 2, 1)); // pending at begin, runs during the recording
        BlockPos pendingTick = helper.absolutePos(new BlockPos(2, 2, 1));  // pending at begin and at restore
        BlockPos newTick = helper.absolutePos(new BlockPos(3, 2, 1));      // created by the recording
        BlockPos newTickOtherChunk = newTick.east(16);                    // created by the recording, in a chunk with no ticks at begin
        BlockPos eventPos = helper.absolutePos(new BlockPos(4, 2, 1));     // block event queued before begin
        BlockPos newEventPos = helper.absolutePos(new BlockPos(5, 2, 1));  // block event queued by the recording
        BlockPos chestRel = new BlockPos(1, 2, 4);
        BlockPos lateChestRel = new BlockPos(3, 2, 4);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        for (BlockPos pos : List.of(consumedTick, pendingTick, newTick, newTickOtherChunk, eventPos, newEventPos)) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3); // stone: its tick and block event do nothing
        }
        helper.setBlock(chestRel, Blocks.CHEST);
        helper.setBlock(lateChestRel, Blocks.CHEST);
        helper.getBlockEntity(chestRel, ChestBlockEntity.class).setItem(0, new ItemStack(Items.APPLE, 5));
        helper.getBlockEntity(lateChestRel, ChestBlockEntity.class).setItem(0, new ItemStack(Items.APPLE, 5));
        Pig pig = helper.spawn(EntityTypes.PIG, new Vec3(5.5, 2, 5.5));
        pig.setNoAi(true);
        Minecart cart = helper.spawn(EntityTypes.MINECART, new Vec3(6.5, 2, 2.5));

        helper.runAfterDelay(5, () -> {
            level.scheduleTick(consumedTick, Blocks.STONE, 3);
            level.scheduleTick(pendingTick, Blocks.STONE, 500);
            level.blockEvent(eventPos, Blocks.STONE, 7, 8);
            // references taken before the recording: changes through them do not go through a block-entity lookup
            ChestBlockEntity chest = helper.getBlockEntity(chestRel, ChestBlockEntity.class);
            ChestBlockEntity lateChest = helper.getBlockEntity(lateChestRel, ChestBlockEntity.class);
            float pigHealth = pig.getHealth();
            int pigInvulnerable = pig.invulnerableTime;

            UndoLog log = UndoLog.begin(level);
            try {
                pig.hurtServer(level, level.damageSources().generic(), 2.0F);
                cart.hurtServer(level, level.damageSources().generic(), 2.0F);
                chest.setItem(0, new ItemStack(Items.DIAMOND, 9));
                lateChest.getItem(0).grow(10); // changes the stack in place: no setItem, only setChanged
                lateChest.setChanged();
                level.scheduleTick(newTick, Blocks.STONE, 500);
                level.scheduleTick(newTickOtherChunk, Blocks.STONE, 500);
            } catch (RuntimeException e) {
                log.abandon();
                throw e;
            }

            helper.runAfterDelay(10, () -> {
                try {
                    // the recording really changed all of it
                    helper.assertTrue(pig.getHealth() < pigHealth, "pig was not hurt");
                helper.assertTrue(ticksAt(level, newTickOtherChunk).size() == 1, "setup: tick in the other chunk was not accepted");
                    helper.assertTrue(cart.getDamage() > 0, "minecart was not hurt");
                    helper.assertTrue(ticksAt(level, consumedTick).isEmpty(), "the pre-existing tick should have run");
                    helper.assertTrue(eventsAt(level, List.of(eventPos)).isEmpty(), "the pre-existing block event should have run");
                    level.blockEvent(newEventPos, Blocks.STONE, 1, 1); // still queued when restore runs
                } finally {
                    log.restore();
                }
                long now = level.getGameTime();
                List<ScheduledTick<Block>> c = ticksAt(level, consumedTick);
                List<ScheduledTick<Block>> p = ticksAt(level, pendingTick);
                helper.assertTrue(c.size() == 1 && c.get(0).triggerTick() - now == 3, "consumed tick not restored with delay 3: " + c + " now=" + now);
                helper.assertTrue(p.size() == 1 && p.get(0).triggerTick() - now == 500, "pending tick not restored with delay 500: " + p + " now=" + now);
                helper.assertTrue(ticksAt(level, newTick).isEmpty(), "tick created by the recording is still pending");
                helper.assertTrue(ticksAt(level, newTickOtherChunk).isEmpty(), "tick created by the recording in a chunk without ticks at begin is still pending");
                List<BlockEventData> events = eventsAt(level, List.of(eventPos, newEventPos));
                helper.assertTrue(events.equals(List.of(new BlockEventData(eventPos, Blocks.STONE, 7, 8))), "block events not as at begin: " + events);
                helper.assertTrue(pig.getHealth() == pigHealth, "pig health not restored: " + pig.getHealth());
                helper.assertTrue(pig.invulnerableTime == pigInvulnerable, "pig invulnerability (not in NBT) not restored: " + pig.invulnerableTime);
                helper.assertTrue(cart.getDamage() == 0.0F, "minecart damage not restored: " + cart.getDamage());
                ItemStack slot = helper.getBlockEntity(chestRel, ChestBlockEntity.class).getItem(0);
                helper.assertTrue(slot.is(Items.APPLE) && slot.getCount() == 5, "chest changed through an old reference not restored: " + slot);
                helper.assertTrue(log.lateBlockEntities().contains(helper.absolutePos(lateChestRel)), "late block entity not reported: " + log.lateBlockEntities());
                helper.assertTrue(log.ticksNotRestored() == 0, "ticks not restored: " + log.ticksNotRestored());
                level.getBlockTicks().clearArea(new BoundingBox(consumedTick).encapsulate(pendingTick));
                level.clearBlockEvents(new BoundingBox(eventPos));
                helper.succeed();
            });
        });
    }

    /**
     * Ruling after R4: a block entity that is not a container is snapshotted at the chain's first read of it
     * ({@code Level.getBlockEntity}), which comes before any change, so the restore is exact and it is not "late". A block
     * entity the chain changes without ever reading it (an old reference) stays reported as late.
     * A command block holds a command string in its block entity data and is no container.
     */
    @GameTest(maxTicks = 100, environment = "ghostpath-gametest:be_first_read")
    public void nonContainerBlockEntitySnapshotAtFirstRead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos readRel = new BlockPos(1, 2, 1);
        BlockPos oldRefRel = new BlockPos(3, 2, 1);
        helper.setBlock(readRel, Blocks.COMMAND_BLOCK);
        helper.setBlock(oldRefRel, Blocks.COMMAND_BLOCK);
        CommandBlockEntity read = helper.getBlockEntity(readRel, CommandBlockEntity.class);
        CommandBlockEntity oldRef = helper.getBlockEntity(oldRefRel, CommandBlockEntity.class); // taken before the recording
        read.getCommandBlock().setCommand("say original");
        oldRef.getCommandBlock().setCommand("say original");
        BlockPos readAbs = helper.absolutePos(readRel);
        BlockPos oldRefAbs = helper.absolutePos(oldRefRel);

        UndoLog log = UndoLog.begin(level);
        CauseTracker tracker = CauseTracker.begin(level, 1L);
        try {
            tracker.runAsChain(() -> {
                CommandBlockEntity be = (CommandBlockEntity) level.getBlockEntity(readAbs); // the first read: snapshot here
                be.getCommandBlock().setCommand("say changed");
                be.setChanged();
                oldRef.getCommandBlock().setCommand("say changed"); // never read through the level during the recording
                oldRef.setChanged();
            });
        } finally {
            tracker.end();
        }
        helper.assertTrue("say changed".equals(read.getCommandBlock().getCommand()), "setup: the recording did not change the command block");
        boolean readInFootprint = tracker.reads().getOrDefault(readAbs, java.util.Set.of()).contains(CauseTracker.ReadKind.BLOCK_ENTITY)
            && tracker.writes().contains(readAbs);
        java.util.Set<BlockPos> late = new java.util.HashSet<>(log.lateBlockEntities());
        log.restore();
        helper.assertTrue(readInFootprint, "block entity read/write missing from the footprint");
        String after = helper.getBlockEntity(readRel, CommandBlockEntity.class).getCommandBlock().getCommand();
        helper.assertTrue("say original".equals(after), "block entity read before the change not restored exactly: " + after);
        helper.assertTrue(!late.contains(readAbs), "block entity that was read first is reported as late: " + late);
        helper.assertTrue(late.contains(oldRefAbs), "block entity changed without a read is not reported as late: " + late);
        helper.succeed();
    }
}
