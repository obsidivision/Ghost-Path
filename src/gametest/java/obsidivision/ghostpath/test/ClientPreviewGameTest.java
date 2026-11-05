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
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.client.Inspector;
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
 * <li>the renderer submitted lines in the frames (counters), and the path is in a capture of the real render target
 *     ({@link ClientShots}; Fabric's own {@code takeScreenshot} leaves gizmos out, vanilla's too: that was the "no gizmo shows"
 *     finding, see {@code GizmoVisibilityGameTest}).</li>
 * </ol>
 */
public class ClientPreviewGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    @Override
    public void runTest(ClientGameTestContext context) {
        obsidivision.ghostpath.PathSettings.hoverDetail = "full"; // the tests read the exact lines
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
            check(points > 10 && "stuck".equals(why), "the continuation: " + points + " points, " + why);
            int frames = PathRenderer.framesDrawn;
            context.waitTicks(10);
            check(PathRenderer.framesDrawn > frames && PathRenderer.lastLines == 1 && PathRenderer.lastSegments > 5,
                "the renderer drew " + PathRenderer.framesDrawn + " frames (was " + frames + "), lines " + PathRenderer.lastLines + ", segments " + PathRenderer.lastSegments);
            // the path in a capture of the real main render target (ClientShots: Fabric's takeScreenshot has no gizmos): the same view
            // with the paths hidden is the baseline, and the coloured pixels of the line and its labels are the difference
            PathSettings.showPaths = false;
            context.waitTicks(3);
            Path hidden = ClientShots.capture(context, "ghostpath-path-hidden");
            PathSettings.showPaths = true;
            context.waitTicks(3);
            Path shot1 = ClientShots.capture(context, "ghostpath-continued-arrow");
            int changed = ClientShots.countDifferent(hidden, shot1, 60);
            LOG.info("[ghostpath] client test: screenshot {} ({} pixels differ from the same view with the paths hidden)", shot1, changed);
            check(changed > 150, "the path is not in the capture: " + changed + " pixels differ");

            // 1b. a continued point is hovered like a ghost point (camera 6 blocks north of it, looking south)
            int pick = contTicks.length / 2;
            net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(contXyz[3 * pick], contXyz[3 * pick + 1] + PathRenderer.lift(line.kind()), contXyz[3 * pick + 2]);
            server.runCommand("tp @a " + at.x + " " + (at.y - 1.62) + " " + (at.z - 6.0) + " 0 0");
            context.waitTicks(5);
            context.waitFor(c -> Inspector.hoveredTail() != null, 200);
            Inspector.TailHover th = context.computeOnClient(c -> Inspector.hoveredTail());
            check(th.point() == pick && Inspector.hoveredGhost() == null, "hovered continued point " + th.point() + ", wanted " + pick);
            context.waitTicks(2);
            java.util.List<String> panel = Inspector.lastHoverPanel;
            LOG.info("[ghostpath] client test: hovering continued point {} (t+{}): {}", pick, contTicks[pick], panel);
            check(panel.getFirst().equals("minecraft:arrow  t+" + contTicks[pick] + "  (continued, client-side)") && panel.contains("pos x " + contXyz[3 * pick])
                && panel.contains("pos y " + contXyz[3 * pick + 1]) && panel.stream().anyMatch(l -> l.startsWith("vel x ")), "continued panel: " + panel);
            ClientShots.capture(context, "inspector-continued");

            // 1c. the inspect key as a toggle: one press switches it on (the ray then goes through blocks), another off
            net.minecraft.core.BlockPos wall = net.minecraft.core.BlockPos.containing(at.x, at.y, at.z - 3.0);
            server.runOnServer(srv -> srv.overworld().setBlock(wall, Blocks.STONE.defaultBlockState(), 3));
            context.waitTicks(5);
            check(context.computeOnClient(c -> Inspector.hoveredTail()) == null, "hovered through a block");
            server.runCommand("path inspect toggle");
            context.getInput().pressKey(obsidivision.ghostpath.client.GhostPathClient.INSPECT);
            context.waitTicks(5);
            check(context.computeOnClient(c -> Inspector.inspecting() && Inspector.hoveredTail() != null), "toggled on: the point behind the block must be hovered");
            context.waitTicks(5);
            check(context.computeOnClient(c -> Inspector.inspecting()), "toggled on: still on after the key was released");
            context.getInput().pressKey(obsidivision.ghostpath.client.GhostPathClient.INSPECT);
            context.waitTicks(5);
            check(context.computeOnClient(c -> !Inspector.inspecting() && Inspector.hoveredTail() == null), "toggled off");
            server.runCommand("path inspect hold");
            context.getInput().holdKey(obsidivision.ghostpath.client.GhostPathClient.INSPECT);
            context.waitTicks(4);
            check(context.computeOnClient(c -> Inspector.inspecting()), "hold mode: held");
            context.getInput().releaseKey(obsidivision.ghostpath.client.GhostPathClient.INSPECT);
            context.waitTicks(4);
            check(context.computeOnClient(c -> !Inspector.inspecting()), "hold mode: released");
            server.runOnServer(srv -> srv.overworld().setBlock(wall, Blocks.AIR.defaultBlockState(), 3));

            // 1d. /path pins clear
            context.getInput().holdKey(obsidivision.ghostpath.client.GhostPathClient.INSPECT);
            context.waitTicks(3);
            context.getInput().pressMouse(0); // clicking a continued point pins nothing and attacks nothing
            context.waitTicks(3);
            context.getInput().releaseKey(obsidivision.ghostpath.client.GhostPathClient.INSPECT);
            check(Inspector.pins().isEmpty(), "a continued point was pinned");
            context.computeOnClient(c -> {
                Inspector.addPinForTest(new obsidivision.ghostpath.inspect.Pin("minecraft:arrow", 0, 3));
                return null;
            });
            check(Inspector.pins().size() == 1, "test pin");
            server.runCommand("path pins clear");
            context.waitTicks(3);
            check(Inspector.pins().isEmpty(), "pins after /path pins clear: " + Inspector.pins());

            // 2. the same shot with the default limit: a stuck arrow is settled (ruling after step 5), so the recording ends a few
            //    ticks after it stuck and no limit is needed; the whole flight is on the server
            server.runCommand("path limit " + PathSettings.DEFAULT_TICK_LIMIT);
            server.runCommand("path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
            server.waitFor(srv -> MachineRecorder.current() == null && PathStore.latest() != limited);
            PathSnapshot full = PathStore.latest();
            check(!full.limitHit() && full.lines().size() == 1 && full.lines().getFirst().tail() == null, "full snapshot: limit hit " + full.limitHit() + ", lines " + full.lines().size());
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
            ClientShots.capture(context, "ghostpath-full-arrow");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
