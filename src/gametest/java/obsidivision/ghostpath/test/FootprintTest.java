package obsidivision.ghostpath.test;

import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.ChainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Build step 1c: read footprint. */
public class FootprintTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    private static PrimedTnt launch(ServerLevel level, CauseTracker tracker, Vec3 at, Vec3 velocity) {
        PrimedTnt[] tnt = new PrimedTnt[1];
        tracker.runAsChain(() -> {
            tnt[0] = new PrimedTnt(level, at.x, at.y, at.z, null);
            tnt[0].setDeltaMovement(velocity); // replaces the (seeded) kick: a fixed path
            tnt[0].setFuse(1000);              // no explosion during the test
            level.addFreshEntity(tnt[0]);
        });
        return tnt[0];
    }

    private static void whenEntityTicking(GameTestHelper helper, BlockPos abs, Runnable then) {
        if (helper.getLevel().isPositionEntityTicking(abs)) {
            then.run();
        } else {
            helper.runAfterDelay(1, () -> whenEntityTicking(helper, abs, then));
        }
    }

    /**
     * A chain TNT flies into a glass wall. The wall is read only by entity collision, which reads the chunk directly
     * (BlockCollisions.java:89), so it is in the footprint only through the BlockCollisions hook (DESIGN finding 3).
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:footprint_collision")
    public void collisionReadsInFootprint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSupport.removeBarriers(helper);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        for (int y = 2; y <= 3; y++) {
            for (int z = 0; z < 5; z++) {
                helper.setBlock(new BlockPos(6, y, z), Blocks.GLASS);
            }
        }
        BlockPos wall = helper.absolutePos(new BlockPos(6, 2, 2));
        Vec3 start = helper.absoluteVec(new Vec3(3.5, 2.0, 2.5));
        whenEntityTicking(helper, BlockPos.containing(start), () -> {
            CauseTracker tracker = CauseTracker.begin(level, 1L);
            PrimedTnt tnt;
            try {
                tnt = launch(level, tracker, start, new Vec3(3.0, 0.0, 0.0));
            } catch (RuntimeException e) {
                tracker.end();
                throw e;
            }
            helper.runAfterDelay(3, () -> {
                tracker.end();
                tnt.discard();
                Set<CauseTracker.ReadKind> kinds = tracker.reads().get(wall);
                LOG.info("[ghostpath] collision test: {} read positions; wall read as {}; TNT at x={} (wall face at x={})",
                    tracker.reads().size(), kinds, tnt.getX() - helper.absoluteVec(Vec3.ZERO).x, 6);
                helper.assertTrue(tnt.getX() - helper.absoluteVec(Vec3.ZERO).x < 5.6, "TNT did not stop at the wall: x=" + tnt.getX());
                helper.assertTrue(kinds != null && kinds.contains(CauseTracker.ReadKind.COLLISION), "wall not in the footprint as a collision read: " + kinds);
                helper.assertTrue(tracker.footprint().contains(wall), "wall not in the footprint");
                helper.succeed();
            });
        });
    }

    /**
     * A chain TNT is launched so fast that its collision sweep reaches past the first chunk that is not at FULL status
     * (the test force-loads nothing beyond the test structure); a glass wall in the last FULL chunk stops it. The sweep
     * must record "no collision: chunk below FULL". Chunks around the test keep loading asynchronously, so the first
     * chunk below FULL is picked at launch time, and an event for it or a chunk further out is accepted.
     */
    @GameTest(maxTicks = 12000, environment = "ghostpath-gametest:footprint_below_full")
    public void noCollisionBelowFullRecorded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSupport.removeBarriers(helper); // otherwise the shell at x = 8 stops the TNT, not the wall
        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 1));
        ChunkPos home = ChunkPos.containing(origin);
        whenEntityTicking(helper, origin, () -> {
            int below = Integer.MIN_VALUE;
            for (int cx = home.x() + 1; cx <= home.x() + 40; cx++) {
                if (level.getChunkForCollisions(cx, home.z()) == null) {
                    below = cx;
                    break;
                }
            }
            helper.assertTrue(below != Integer.MIN_VALUE, "no chunk below FULL within 40 chunks");
            int belowX = below;
            int wallX = (belowX << 4) - 1; // last column of the last FULL chunk
            for (int y = 0; y <= 2; y++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlock(new BlockPos(wallX, origin.getY() + y, origin.getZ() + dz), Blocks.GLASS.defaultBlockState(), 2);
                }
            }
            Vec3 start = Vec3.atBottomCenterOf(origin);
            double speed = (belowX << 4) + 34 - start.x; // the sweep ends two chunks into the region below FULL
            LOG.info("[ghostpath] below-FULL test: home chunk {}, first chunk below FULL at launch {} ({} chunks away); speed {}",
                home.x(), belowX, belowX - home.x(), speed);
            CauseTracker tracker = CauseTracker.begin(level, 1L);
            PrimedTnt tnt;
            try {
                tnt = launch(level, tracker, start, new Vec3(speed, 0.0, 0.0));
            } catch (RuntimeException e) {
                tracker.end();
                throw e;
            }
            helper.runAfterDelay(2, () -> {
                tracker.end();
                tnt.discard();
                for (int y = 0; y <= 2; y++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        level.setBlock(new BlockPos(wallX, origin.getY() + y, origin.getZ() + dz), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                LOG.info("[ghostpath] below-FULL test: TNT stopped at x = origin + {} (wall at origin + {}); events:\n{}",
                    tnt.getX() - origin.getX(), wallX - origin.getX(), tracker.eventsAsText(20));
                helper.assertTrue(tnt.getX() < wallX && tnt.getX() > wallX - 1.0, "TNT did not stop at the wall: x=" + tnt.getX() + ", wall x=" + wallX);
                helper.assertTrue(tracker.events().stream().anyMatch(e -> e.kind() == ChainEvent.Kind.NO_COLLISION_BELOW_FULL
                        && e.detail().endsWith("," + home.z()) && Integer.parseInt(e.detail().substring(6, e.detail().indexOf(','))) >= belowX),
                    "no 'no collision: chunk below FULL' event for chunk x >= " + belowX);
                helper.succeed();
            });
        });
    }
}
