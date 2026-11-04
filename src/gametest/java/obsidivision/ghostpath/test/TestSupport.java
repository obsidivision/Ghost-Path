package obsidivision.ghostpath.test;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

final class TestSupport {
    private TestSupport() {
    }

    /**
     * {@code helper.runAfterDelay}, but the callback runs at most once. The 26.2 harness keeps its delayed callbacks in a
     * fastutil open-addressing map and iterates it while the callbacks themselves add new entries
     * ({@code GameTestInfo.tickInternal}, :122-139): {@code it.remove()} shifts entries, so a callback that schedules another
     * one can be run a second time (seen: a ghost created twice, "saves are already held"). Tests that nest delayed callbacks
     * use this.
     */
    static void later(GameTestHelper helper, long ticks, Runnable body) {
        boolean[] ran = {false};
        helper.runAfterDelay(ticks, () -> {
            if (!ran[0]) {
                ran[0] = true;
                body.run();
            }
        });
    }

    /**
     * Runs {@code then} once nothing holds the autosave guard: no recording, no ghost (a previous test's ghost or recording
     * can still be closing when the next environment starts; {@code SaveGuard.begin} would throw "saves are already held").
     */
    static void whenSavesFree(GameTestHelper helper, Runnable then) {
        whenSavesFree(helper, TestSupport.once(then), 0);
    }

    private static void whenSavesFree(GameTestHelper helper, Runnable then, int waited) {
        if (obsidivision.ghostpath.record.SaveGuard.active() == null && obsidivision.ghostpath.record.MachineRecorder.current() == null
            && obsidivision.ghostpath.record.CauseTracker.active() == null) {
            then.run();
        } else if (waited > 6000) {
            helper.fail("the autosave guard is still held after 6000 ticks (a recording or a ghost from before?)");
        } else {
            helper.runAfterDelay(1, () -> whenSavesFree(helper, then, waited + 1));
        }
    }

    /** {@code body}, but at most once (see {@link #later}). */
    static Runnable once(Runnable body) {
        boolean[] ran = {false};
        return () -> {
            if (!ran[0]) {
                ran[0] = true;
                body.run();
            }
        };
    }

    /**
     * The 26.2 game-test framework encases every test structure in barrier blocks, one block outside its bounds, ceiling
     * included unless skyAccess (GameTestInfo.java:75 encaseStructure; TestInstanceBlockEntity.java:358-363, 374-391); they
     * are removed only after the test passes. Machines larger than the 8x8x8 default structure would have a barrier wall
     * running through them, which no real world has. Clears that shell before building.
     */
    static void removeBarriers(GameTestHelper helper) {
        AABB box = helper.getBounds().inflate(1.0);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (helper.getLevel().getBlockState(pos).is(Blocks.BARRIER)) {
                helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }
}
