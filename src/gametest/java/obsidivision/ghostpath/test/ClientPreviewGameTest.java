package obsidivision.ghostpath.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import obsidivision.ghostpath.client.PathRenderer;
import obsidivision.ghostpath.client.Tails;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.record.MachineRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Step 5 on the real client (Fabric client game test, run under a virtual display with software OpenGL):
 * <ol>
 * <li>a dispenser shoots an arrow straight up; the recording is limited to 10 ticks, so the arrow is still flying: its line
 *     ends "limit" with a tail and the client continues it with a copy of the entity (mixins: no particles, sounds, level
 *     events, no hit effects; the copy never joins the level) until it hits the ground;</li>
 * <li>the same machine is then recorded without the limit; the continuation must follow the same positions, tick for tick, and
 *     end at the same place for the same reason (the ghost run and the client use the same vanilla tick code);</li>
 * <li>the renderer submitted lines in the frames (counters). What the lines look like cannot be checked here: with the virtual
 *     display's software OpenGL no gizmo shows in a screenshot, not even vanilla's own (F3+G chunk borders, tried), so the
 *     screenshots this test takes only show the scene.</li>
 * </ol>
 */
public class ClientPreviewGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            BlockPos[] machine = new BlockPos[2];
            server.runOnServer(srv -> {
                ServerLevel level = srv.overworld();
                BlockPos spawn = level.getRespawnData().pos();
                int x = spawn.getX() + 6;
                int z = spawn.getZ();
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos disp = new BlockPos(x, y, z);
                BlockPos lever = disp.west();
                level.setBlock(disp, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.UP), 3);
                ((DispenserBlockEntity) level.getBlockEntity(disp)).setItem(0, new ItemStack(Items.ARROW));
                level.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR), 3);
                machine[0] = disp;
                machine[1] = lever;
            });
            BlockPos disp = machine[0];
            BlockPos lever = machine[1];
            LOG.info("[ghostpath] client test: dispenser at {}, lever {}", disp, lever);
            server.runCommand("gamemode spectator @a");
            // the player floats west of the machine and looks east at it
            server.runCommand("tp @a " + (disp.getX() - 34) + " " + (disp.getY() + 24) + " " + disp.getZ() + " -90 5");
            context.waitTicks(40);

            // 1. limited to 10 ticks: the arrow is still flying
            server.runCommand("path limit 10");
            server.runCommand("path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
            server.waitFor(srv -> MachineRecorder.current() == null && PathStore.latest() != null);
            PathSnapshot limited = PathStore.latest();
            check(limited.limitHit() && limited.lines().size() == 1, "limited snapshot: " + limited);
            PathSnapshot.Line line = limited.lines().getFirst();
            check(line.end().reason().equals("limit") && line.tail() != null, "the arrow's line: " + line.end() + ", tail " + line.tail());
            context.waitFor(client -> {
                Tails.Job j = Tails.job(limited, 0);
                return j != null && j.done();
            }, 600);
            Tails.Job job = context.computeOnClient(client -> Tails.job(limited, 0));
            int points = context.computeOnClient(client -> job.points());
            String why = context.computeOnClient(client -> job.endReason());
            double[] contXyz = context.computeOnClient(client -> job.xyz.toDoubleArray());
            long[] contTicks = context.computeOnClient(client -> job.ticks.toLongArray());
            LOG.info("[ghostpath] client test: continuation {} points, ends {} at t+{}", points, why, contTicks[contTicks.length - 1]);
            check(points > 10 && "hit block".equals(why), "the continuation: " + points + " points, " + why);
            int frames = PathRenderer.framesDrawn;
            context.waitTicks(10);
            check(PathRenderer.framesDrawn > frames && PathRenderer.lastLines == 1 && PathRenderer.lastSegments > 5,
                "the renderer drew " + PathRenderer.framesDrawn + " frames (was " + frames + "), lines " + PathRenderer.lastLines + ", segments " + PathRenderer.lastSegments);
            Path shot1 = context.takeScreenshot("ghostpath-continued-arrow");
            LOG.info("[ghostpath] client test: screenshot {}", shot1);

            // 2. the same shot with a limit past the landing (an arrow stuck in the ground holds a recording open until it despawns
            //    after 1200 ticks, so "no limit" would take that long): the whole flight on the server
            server.runCommand("path limit 80");
            server.runCommand("path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
            server.waitFor(srv -> MachineRecorder.current() == null && PathStore.latest() != limited);
            PathSnapshot full = PathStore.latest();
            check(full.lines().size() == 1 && full.lines().getFirst().tail() == null, "full snapshot: lines " + full.lines().size());
            PathSnapshot.Line whole = full.lines().getFirst();
            LOG.info("[ghostpath] client test: full run {} points, ends {} at t+{}", whole.points(), whole.end().reason(), whole.end().tick());
            // every continued point equals the full run's, exactly, except the last one: the server's arrow is moved back 0.05 from the
            // block it hit (AbstractArrow.onHitBlock), part of the hit effects that are off on the client's copy
            double worst = 0.0;
            double worstLast = 0.0;
            int compared = 0;
            for (int i = 0; i < contTicks.length; i++) {
                long tick = contTicks[i];
                int k = java.util.Arrays.binarySearch(whole.ticks(), tick);
                if (k < 0) {
                    check(false, "the full run has no point at t+" + tick);
                }
                for (int a = 0; a < 3; a++) {
                    double d = Math.abs(whole.xyz()[3 * k + a] - contXyz[3 * i + a]);
                    if (i == contTicks.length - 1) {
                        worstLast = Math.max(worstLast, d);
                    } else {
                        worst = Math.max(worst, d);
                    }
                }
                compared++;
            }
            LOG.info("[ghostpath] client test: {} continued points compared with the full run: largest difference {} (last point, the hit: {})", compared, worst, worstLast);
            check(worst == 0.0, "the continuation differs from the full run by " + worst);
            check(worstLast < 0.0501, "the last point differs by " + worstLast);
            check(whole.end().reason().equals(why) && whole.end().tick() == contTicks[contTicks.length - 1],
                "ends: full " + whole.end().reason() + " t+" + whole.end().tick() + ", continued " + why + " t+" + contTicks[contTicks.length - 1]);
            context.waitTicks(10);
            context.takeScreenshot("ghostpath-full-arrow");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
