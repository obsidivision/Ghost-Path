package obsidivision.ghostpath.test;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Do gizmos show up on this client? (user question, step 6 session: "why does no gizmo, not even F3+G chunk borders, show in the
 * Xvfb screenshots?"). Answer found here: gizmos are drawn fine by llvmpipe; Fabric's {@code takeScreenshot} just does not capture
 * them (see {@link ClientShots}). This test draws a magenta point, a green line, an orange box and white text straight in front of
 * the camera (through the same {@code BEFORE_GIZMOS} hook the mod uses), turns on vanilla's chunk borders and entity hitboxes, and
 * counts the pixels of each colour in a capture of the main render target. It also logs the GL renderer.
 */
public class GizmoVisibilityGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    private static volatile boolean probe;

    @Override
    public void runTest(ClientGameTestContext context) {
        LevelRenderEvents.BEFORE_GIZMOS.register(c -> {
            if (!probe) {
                return;
            }
            var player = Minecraft.getInstance().player;
            Vec3 p = player.getEyePosition().add(player.getViewVector(1.0F).scale(1.2));
            Gizmos.point(p.add(0, 0.05, 0), 0xFFFF00FF, 60.0F);
            Gizmos.line(p.add(0, -0.5, -2), p.add(0, -0.5, 2), 0xFF00FF00, 20.0F);
            Gizmos.cuboid(new AABB(p.add(-0.1, -0.1, -0.1), p.add(0.1, 0.1, 0.1)), GizmoStyle.fill(0xFFFF8000));
            Gizmos.billboardText("GIZMO TEXT", p.add(0, -0.1, 0), TextGizmo.Style.forColorAndCentered(0xFFFFFFFF).withScale(3.0F));
        });
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            DeviceInfo info = context.computeOnClient(c -> RenderSystem.getDevice().getDeviceInfo());
            LOG.info("[ghostpath] gizmo visibility: GPU '{}', driver '{}', backend '{}'", info.name(), info.driverInfo(), info.backendName());
            BlockPos[] stone = new BlockPos[1];
            server.runOnServer(srv -> {
                var level = srv.overworld();
                BlockPos spawn = level.getRespawnData().pos();
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, spawn.getX() + 6, spawn.getZ());
                stone[0] = new BlockPos(spawn.getX() + 6, y, spawn.getZ());
                level.setBlock(stone[0], Blocks.STONE.defaultBlockState(), 3);
            });
            BlockPos s = stone[0];
            server.runCommand("gamemode creative @a");
            // two blocks west of the stone, looking east and down at it; an armor stand to the side for the hitbox
            server.runCommand("tp @a " + (s.getX() - 2) + " " + s.getY() + " " + (s.getZ() + 0.5) + " -90 30");
            server.runCommand("summon armor_stand " + (s.getX() - 1.0) + " " + s.getY() + " " + (s.getZ() + 2.5));
            context.waitTicks(40);

            Path none = ClientShots.capture(context, "gizmos-0-none");
            probe = true;
            context.waitTicks(5);
            Path ours = ClientShots.capture(context, "gizmos-1-ours");
            probe = false;
            int magenta = ClientShots.count(ours, c -> ClientShots.r(c) > 230 && ClientShots.g(c) < 30 && ClientShots.b(c) > 230);
            int green = ClientShots.count(ours, c -> ClientShots.g(c) > 230 && ClientShots.r(c) < 30 && ClientShots.b(c) < 30);
            int orange = ClientShots.count(ours, c -> ClientShots.r(c) > 230 && ClientShots.g(c) > 110 && ClientShots.g(c) < 150 && ClientShots.b(c) < 20);
            int white = ClientShots.count(ours, c -> ClientShots.r(c) > 250 && ClientShots.g(c) > 250 && ClientShots.b(c) > 250);
            int magentaBefore = ClientShots.count(none, c -> ClientShots.r(c) > 230 && ClientShots.g(c) < 30 && ClientShots.b(c) > 230);
            LOG.info("[ghostpath] gizmo visibility: own gizmos in the capture: magenta {} (before {}), green {}, orange {}, white {}", magenta, magentaBefore, green, orange, white);
            check(magentaBefore == 0, "magenta pixels without the probe: " + magentaBefore);
            check(magenta > 100 && green > 100 && orange > 50 && white > 100, "gizmos not in the capture: magenta " + magenta + ", green " + green + ", orange " + orange + ", white " + white);

            // vanilla's own: F3+G chunk borders (yellow and teal lines) and F3+B hitboxes
            context.runOnClient(c -> {
                c.debugEntries.setStatus(DebugScreenEntries.CHUNK_BORDERS, DebugScreenEntryStatus.ALWAYS_ON);
                c.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.ALWAYS_ON);
            });
            context.waitTicks(5);
            Path vanilla = ClientShots.capture(context, "gizmos-2-vanilla-debug");
            int yellow = ClientShots.count(vanilla, c -> ClientShots.r(c) > 200 && ClientShots.g(c) > 200 && ClientShots.b(c) < 60);
            int yellowBefore = ClientShots.count(none, c -> ClientShots.r(c) > 200 && ClientShots.g(c) > 200 && ClientShots.b(c) < 60);
            LOG.info("[ghostpath] gizmo visibility: vanilla F3+G / F3+B: yellow pixels {} (before {})", yellow, yellowBefore);
            check(yellow > yellowBefore + 50, "vanilla's chunk borders are not in the capture: yellow " + yellow + " (before " + yellowBefore + ")");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
