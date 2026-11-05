package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

final class TestSupport {
    private TestSupport() {
    }

    /**
     * The harness timeout of every test that waits on asynchronous work (chunk generation, loading and unloading, entity
     * sections, the IO worker, a restore's reload). The harness counts ticks, and game tests tick as fast as they can: on a
     * loaded machine 12000 ticks went by in one second while a fresh chunk was still being generated
     * (chain_gaps_test_unloaded_chain_things_logged, "Didn't succeed or fail within 12000 ticks", under
     * {@code scripts/repeat-gametest.sh --stress}). The waits themselves give up after {@link #REAL_TIME_LIMIT_S} seconds
     * of real time ({@link #overRealTime}); this is only the backstop.
     */
    static final int ASYNC_MAX_TICKS = 1_000_000;
    /** Real time a test may spend waiting for asynchronous work before its wait fails. */
    static final int REAL_TIME_LIMIT_S = 180;
    private static final java.util.Map<GameTestHelper, Long> FIRST_WAIT = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * True once more than {@link #REAL_TIME_LIMIT_S} seconds of real time have passed since this test first asked. Waits on
     * asynchronous work use this instead of a tick count: how many ticks such a wait takes depends on how fast the machine
     * is, not on the test.
     */
    static boolean overRealTime(GameTestHelper helper) {
        long now = System.nanoTime();
        long first = FIRST_WAIT.computeIfAbsent(helper, h -> now);
        return now - first > REAL_TIME_LIMIT_S * 1_000_000_000L;
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
        } else if (TestSupport.overRealTime(helper)) {
            helper.fail("the autosave guard is still held within the real-time limit (" + TestSupport.REAL_TIME_LIMIT_S + " s) (a recording or a ghost from before?)");
        } else {
            helper.runAfterDelay(1, () -> whenSavesFree(helper, then, waited + 1));
        }
    }

    /**
     * Runs {@code body.accept(t)} on the test's ticks {@code first..last} (relative to now, one call per tick), then
     * {@code after}, keeping **one** harness callback at a time. Do not schedule many callbacks at once instead
     * (a loop of {@code runAfterDelay(t, ...)}): the harness map ({@code Object2LongOpenHashMap}) grows while
     * {@code GameTestInfo.tickInternal} (:122-139) iterates it, and an iterator over a rehashed table can skip an entry
     * (the callback is lost) or remove another one. Seen: a lamp check at +14 lost, the next one read +15
     * (copied_ticks_keep_their_remaining_delay, "lamp lit on different ticks: real +15, ghost +14").
     */
    static void eachTick(GameTestHelper helper, int first, int last, java.util.function.IntConsumer body, Runnable after) {
        later(helper, first, () -> eachTickStep(helper, first, last, body, after));
    }

    private static void eachTickStep(GameTestHelper helper, int t, int last, java.util.function.IntConsumer body, Runnable after) {
        body.accept(t);
        if (t < last) {
            later(helper, 1, () -> eachTickStep(helper, t + 1, last, body, after));
        } else {
            after.run();
        }
    }

    private static final java.util.Map<GameTestHelper, java.util.Set<ChunkPos>> FORCED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Remembers that this test force-loaded the chunk, for {@link #forcedChunksTicking}. */
    static void noteForced(GameTestHelper helper, int cx, int cz) {
        FORCED.computeIfAbsent(helper, h -> new java.util.HashSet<>()).add(new ChunkPos(cx, cz));
    }

    static void noteUnforced(GameTestHelper helper, int cx, int cz) {
        java.util.Set<ChunkPos> forced = FORCED.get(helper);
        if (forced != null) {
            forced.remove(new ChunkPos(cx, cz));
        }
    }

    /**
     * True when every chunk this test force-loaded ticks entities and has its entities loaded. The wait helpers checked
     * only a few machine positions (its corners); a forced chunk in between or at the edge could still be loading when a
     * test started its first run, so on a loaded machine an entity that flew into it froze in one run and not in the next.
     */
    static boolean forcedChunksTicking(GameTestHelper helper) {
        java.util.Set<ChunkPos> forced = FORCED.get(helper);
        if (forced == null) {
            return true;
        }
        ServerLevel level = helper.getLevel();
        synchronized (FORCED) {
            for (ChunkPos c : forced) {
                if (!level.areEntitiesActuallyLoadedAndTicking(c) || !level.isPositionEntityTicking(c.getMiddleBlockPosition(level.getMinY() + 1))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Discards every non-player entity in the chunks this test force-loaded, once they are loaded (call it when
     * {@link #forcedChunksTicking} is true and before the test makes entities of its own); returns what it found, for the
     * log. Those entities are another test's: seen, 19 items in the forced area of machine_command (they fell into its
     * machine area and changed the entity count: "entities not restored") and 15 in a neighbour chunk of
     * ghost_disk_entities. A blast or a machine that reaches past its own test cell leaves them; frozen in chunks nobody
     * ticks any more, they wake when the next test forces those chunks. The harness clears only a new test's own padded
     * box, and only the entities loaded at that moment ({@code StructureUtils.clearSpaceForStructure}, :70-78).
     */
    static String clearForeignEntities(GameTestHelper helper) {
        java.util.Set<ChunkPos> forced = FORCED.get(helper);
        if (forced == null) {
            return "0";
        }
        ServerLevel level = helper.getLevel();
        List<net.minecraft.world.entity.Entity> found = new ArrayList<>();
        synchronized (FORCED) {
            for (ChunkPos c : forced) {
                found.addAll(level.getEntities((net.minecraft.world.entity.Entity) null,
                    new AABB(c.getMinBlockX(), level.getMinY(), c.getMinBlockZ(), c.getMaxBlockX() + 1.0, level.getMaxY(), c.getMaxBlockZ() + 1.0),
                    e -> !(e instanceof net.minecraft.world.entity.player.Player)));
            }
        }
        StringBuilder sb = new StringBuilder().append(found.size());
        java.util.Map<String, Integer> types = new java.util.TreeMap<>();
        for (net.minecraft.world.entity.Entity e : found) {
            types.merge(net.minecraft.world.entity.EntityType.getKey(e.getType()).getPath(), 1, Integer::sum);
            e.discard();
        }
        if (!found.isEmpty()) {
            sb.append(' ').append(types);
        }
        return sb.toString();
    }

    /** "N: type@x,y,z ..." for the non-player entities in {@code box} (diagnostics: what else is in a test's area). */
    static String describeEntities(ServerLevel level, AABB box) {
        List<net.minecraft.world.entity.Entity> list = level.getEntities((net.minecraft.world.entity.Entity) null, box,
            e -> !(e instanceof net.minecraft.world.entity.player.Player));
        StringBuilder sb = new StringBuilder().append(list.size());
        for (int i = 0; i < Math.min(8, list.size()); i++) {
            net.minecraft.world.entity.Entity e = list.get(i);
            sb.append(i == 0 ? ": " : ", ").append(net.minecraft.world.entity.EntityType.getKey(e.getType()).getPath())
                .append('@').append(e.blockPosition().toShortString());
        }
        return sb.toString();
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

    /**
     * Force-loads the chunks within {@code radius} chunks of the test structure, which makes them entity ticking (forced tickets
     * are level 31). A projectile that flies out of the structure's chunk would otherwise stop being ticked in the neighbour
     * chunk (the structure sits at a different chunk offset in every run) and hold a recording open until the limit. Returns the
     * undo: it does <b>nothing</b>, on purpose. Tests of one environment run side by side and their structures sit in the same
     * chunks; the framework force-loads each structure's chunks itself (TestInstanceBlockEntity.java:341) and unforces every forced
     * chunk when the batch ends (GameTestRunner.java:116). "I forced it, I unforce it" released chunks a neighbour test still needed:
     * its arrow stopped being ticked and held the recording to the tick limit (1 in 15 runs of the flight tests, only 42 of 1999 ticks
     * logged), and a release of a chunk that the framework had forced for a test not yet started hung that test for good (a test whose
     * structure chunks are not entity ticking does not count ticks, so it never times out, GameTestInfo.java:103). Test setup only: a
     * real recording never changes tickets (R9).
     */
    static Runnable forceAround(GameTestHelper helper, int radius) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(new BlockPos(0, 0, 0));
        int cx = centre.getX() >> 4;
        int cz = centre.getZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                level.setChunkForced(cx + dx, cz + dz, true);
                noteForced(helper, cx + dx, cz + dz);
            }
        }
        return () -> { };
    }
}
