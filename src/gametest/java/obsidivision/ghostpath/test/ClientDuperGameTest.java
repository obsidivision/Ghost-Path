package obsidivision.ghostpath.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.client.PathRenderer;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.watch.LiveWatch;
import obsidivision.ghostpath.watch.Prediction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The user's dust duper ({@link DustDuperTest}) on the real client: {@code /path machine}, the frame cost of drawing its paths (the same view with
 * the paths hidden is the baseline: software OpenGL, so only the difference means something), and a live rerun in a real game (a piston next to an
 * observer): its time, in real ticks of 50 ms.
 */
public class ClientDuperGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    private static int frames;

    @Override
    public void runTest(ClientGameTestContext context) {
        LevelRenderEvents.BEFORE_GIZMOS.register(c -> frames++);
        Litematic lit = DustDuperTest.load();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            BlockPos[] at = new BlockPos[1];
            server.runOnServer(srv -> {
                ServerLevel level = srv.overworld();
                BlockPos spawn = level.getRespawnData().pos();
                int x = spawn.getX() + 4;
                int z = spawn.getZ() + 12;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                // a flat stone floor under and around the machine, then the machine, then clear air above it
                for (int dx = -8; dx < 24; dx++) {
                    for (int dz = -8; dz < 12; dz++) {
                        for (int dy = 1; dy < 14; dy++) {
                            level.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.defaultBlockState(), 2 | 16);
                        }
                        for (int dy = -3; dy <= 0; dy++) { // deep enough that no grass above dirt dies under random ticks (that would change the footprint)
                            level.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.STONE.defaultBlockState(), 2 | 16);
                        }
                    }
                }
                at[0] = new BlockPos(x, y + 41, z); // 40 blocks above the floor: the TNT fall and fly like in the game tests
                for (int dy = -40; dy <= 6; dy++) { // the compression column; the TNT fly east into it
                    level.setBlock(at[0].offset(24, dy, 1), Blocks.STONE.defaultBlockState(), 2 | 16);
                }
                for (Litematic.Block b : lit.blocks) {
                    level.setBlock(at[0].offset(b.pos()), b.state(), 2 | 16);
                }
                level.setBlock(at[0].offset(DustDuperTest.LEVER).below(), Blocks.STONE.defaultBlockState(), 2 | 16);
                level.setBlock(at[0].offset(DustDuperTest.OBSERVER), Blocks.OBSERVER.defaultBlockState().setValue(net.minecraft.world.level.block.ObserverBlock.FACING, DustDuperTest.OBSERVER_FACING), 2 | 16);
                level.setBlock(at[0].offset(DustDuperTest.LEVER), Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 2 | 16);
            });
            BlockPos origin = at[0];
            BlockPos lever = origin.offset(DustDuperTest.LEVER);
            server.runCommand("gamemode spectator @a");
            // 14 blocks south-east of the middle of the machine, a little above, looking at it
            server.runCommand("tp @a " + (origin.getX() + 7.5) + " " + (origin.getY() + 6) + " " + (origin.getZ() + 16) + " 180 20");
            context.waitTicks(300); // the saved mid-cycle state settles (DustDuperTest.SETTLE_TICKS)
            // wide view: the machine, the column 24 blocks east and the 40 blocks the TNT fall
            double cx = origin.getX() + 12.5;
            String wide = "tp @a " + cx + " " + (origin.getY() + 12) + " " + (origin.getZ() + 50) + " 180 24";
            server.runCommand(wide);
            context.waitTicks(30);
            docs(ClientShots.capture(context, "duper-1-idle"), "duper-1-idle.png");
            server.runCommand("path limit " + PathSettings.DEFAULT_TICK_LIMIT);
            long t0 = System.nanoTime();
            server.runCommand("path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
            server.waitFor(srv -> MachineRecorder.current() == null && PathStore.latest() != null, 6000);
            long recordMs = (System.nanoTime() - t0) / 1_000_000;
            PathSnapshot snap = PathStore.latest();
            LOG.info("[ghostpath] client duper: recorded {} ticks, {} lines, in {} ms real time (recording + ghost check; client and server in lockstep)", snap.ticks(), snap.lines().size(), recordMs);
            context.waitTicks(40);

            // frame cost: shown against hidden, 100 client ticks each
            PathSettings.showPaths = false;
            context.waitTicks(10);
            int f0 = frames;
            long w0 = System.nanoTime();
            context.waitTicks(100);
            double hiddenFps = (frames - f0) / ((System.nanoTime() - w0) / 1e9);
            PathSettings.showPaths = true;
            context.waitTicks(10);
            int f1 = frames;
            long w1 = System.nanoTime();
            int drawn0 = PathRenderer.framesDrawn;
            long nanos0 = PathRenderer.totalNanos;
            context.waitTicks(100);
            double shownFps = (frames - f1) / ((System.nanoTime() - w1) / 1e9);
            int drawn = PathRenderer.framesDrawn - drawn0;
            double msPerFrame = (PathRenderer.totalNanos - nanos0) / 1e6 / Math.max(1, drawn);
            LOG.info("[ghostpath] client duper: {} lines, {} segments drawn per frame; frame rate {} fps with the paths hidden, {} fps shown (software OpenGL); drawing the paths takes {} ms per frame on the client thread",
                PathRenderer.lastLines, PathRenderer.lastSegments, String.format("%.1f", hiddenFps), String.format("%.1f", shownFps), String.format("%.3f", msPerFrame));
            ClientShots.capture(context, "duper-paths");
            docs(ClientShots.capture(context, "duper-2-recorded"), "duper-2-recorded.png");
            // close view of the machine itself with the paths
            server.runCommand("tp @a " + (origin.getX() + 9.0) + " " + (origin.getY() + 7) + " " + (origin.getZ() + 14) + " 180 25");
            context.waitTicks(30);
            docs(ClientShots.capture(context, "duper-3-machine"), "duper-3-machine.png");
            // hover the stack: the camera 6 blocks south of the compressed TNT at t+40, looking north
            PathSnapshot snap2 = PathStore.latest();
            PathSnapshot.Line tnt = snap2.lines().stream().filter(l -> l.type().equals("minecraft:tnt") && l.indexOfTick(40) >= 0).findFirst().orElse(null);
            if (tnt != null) {
                int k = tnt.indexOfTick(40);
                double hx = tnt.xyz()[3 * k];
                double hy = tnt.xyz()[3 * k + 1] + PathRenderer.lift(tnt.kind());
                double hz = tnt.xyz()[3 * k + 2];
                server.runCommand("tp @a " + hx + " " + (hy - 1.62 + 0.3) + " " + (hz + 7.0) + " 180 0");
                context.waitTicks(30);
                docs(ClientShots.capture(context, "duper-4-hover-stack"), "duper-4-hover-stack.png");
            }
            server.runCommand(wide);
            context.waitTicks(20);

            // a live rerun in the real game: a piston next to an observer
            PathSnapshot before = PathStore.latest();
            BlockPos piston = origin.offset(DustDuperTest.NEW_PISTON);
            long p0 = System.nanoTime();
            server.runOnServer(srv -> srv.overworld().setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST), 3));
            for (int i = 0; i < 40 && PathStore.latest().serial() == before.serial(); i++) {
                context.waitTicks(25);
                server.runOnServer(srv -> LOG.info("[ghostpath] client duper: waiting for the rerun, {} ms: {}", (System.nanoTime() - p0) / 1_000_000, LiveWatch.list()));
            }
            long rerunMs = (System.nanoTime() - p0) / 1_000_000;
            Prediction[] p = new Prediction[1];
            server.runOnServer(srv -> p[0] = LiveWatch.watches().getFirst().latest());
            LOG.info("[ghostpath] client duper: rerun after the piston: {} ms from the block change to the new paths (10 quiet ticks = 500 ms of that); prediction {} ticks, {} server ticks, run {} ms, ghost created in {} ms, copy {} ms, longest slice {} ms",
                rerunMs, p[0].ticks(), p[0].serverTicks(), p[0].millis(), p[0].createMillis(), p[0].copyMillis(), p[0].maxSliceMillis());
            context.waitTicks(20);
            docs(ClientShots.capture(context, "duper-5-after-piston"), "duper-5-after-piston.png");
        }
    }

    /** Copies a capture into docs/img of the repository (the client run directory is build/run/clientGameTest). */
    private static void docs(java.nio.file.Path shot, String name) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("../../../docs/img").toAbsolutePath().normalize();
            if (java.nio.file.Files.isDirectory(dir)) {
                java.nio.file.Files.copy(shot, dir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
