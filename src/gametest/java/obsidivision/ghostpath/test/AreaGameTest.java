package obsidivision.ghostpath.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.client.AreaRenderer;
import obsidivision.ghostpath.preview.AreaBuilder;
import obsidivision.ghostpath.preview.AreaData;
import obsidivision.ghostpath.preview.PathBuilder;
import obsidivision.ghostpath.preview.PathStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real client, real capture of the ghost area overlay: a snapshot with a small machine (a line and two single blocks written, a slab around
 * it read), the watched margin, the copied chunks with a ring of widened ones, and a fresh "rerun: piston placed" highlight. The capture goes to
 * {@code docs/img/path-area.png}; the pixel checks look for each layer's colour, the HUD text is read from {@link AreaRenderer#hudLine()}.
 */
public class AreaGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    @Override
    public void runTest(ClientGameTestContext context) {
        PathSettings.showPaths = true;
        PathSettings.showArea = true;
        PathSettings.areaWatched = true;
        PathSettings.areaChunks = true;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            int[] at = new int[3];
            server.runOnServer(srv -> {
                var level = srv.overworld();
                var spawn = level.getRespawnData().pos();
                at[0] = spawn.getX() + 4;
                at[2] = spawn.getZ();
                at[1] = level.getHeight(Heightmap.Types.WORLD_SURFACE, at[0], at[2]) + 3;
                List<BlockPos> writes = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    writes.add(new BlockPos(at[0] + i, at[1], at[2]));
                }
                writes.add(new BlockPos(at[0] + 2, at[1] + 1, at[2]));
                writes.add(new BlockPos(at[0] + 4, at[1], at[2] + 1));
                List<BlockPos> reads = new ArrayList<>(writes);
                for (int x = -1; x <= 7; x++) {
                    for (int z = -1; z <= 2; z++) {
                        reads.add(new BlockPos(at[0] + x, at[1] - 1, at[2] + z));
                    }
                }
                int cx = at[0] >> 4;
                int cz = at[2] >> 4;
                AreaData area = AreaBuilder.build(writes, reads, 2, new int[] {cx - 1, cz - 1, cx + 1, cz + 1}, new int[] {cx, cz, cx, cz},
                    new AreaData.Change(new BlockPos(at[0] + 2, at[1] + 1, at[2]), "rerun: piston placed", System.currentTimeMillis()));
                PathStore.publish(serial -> PathBuilder.build(serial, "rerun", "minecraft:overworld", 20, false, List.of(), List.of(), List.of()).withArea(area));
            });
            server.runCommand("gamemode creative @a");
            server.runCommand("tp @a " + (at[0] + 3.0) + " " + (at[1] - 3.0) + " " + (at[2] + 11.0) + " 180 -6");
            context.waitTicks(25);
            Path shot = ClientShots.capture(context, "path-area");
            int orange = ClientShots.count(shot, c -> ClientShots.r(c) > 225 && ClientShots.g(c) > 100 && ClientShots.g(c) < 175 && ClientShots.b(c) < 90);
            int pale = ClientShots.count(shot, c -> ClientShots.r(c) > 60 && ClientShots.r(c) < 140 && ClientShots.g(c) > 120 && ClientShots.g(c) < 190 && ClientShots.b(c) > 220);
            int yellow = ClientShots.count(shot, c -> ClientShots.r(c) > 235 && ClientShots.g(c) > 205 && ClientShots.b(c) < 120);
            int green = ClientShots.count(shot, c -> ClientShots.r(c) < 150 && ClientShots.g(c) > 170 && ClientShots.b(c) < 150 && ClientShots.g(c) - ClientShots.r(c) > 40);
            int magenta = ClientShots.count(shot, c -> ClientShots.r(c) > 200 && ClientShots.g(c) < 130 && ClientShots.b(c) > 190);
            LOG.info("[ghostpath] area drawing: orange (written) {}, pale blue (read) {}, yellow (last trigger) {}, green (watched) {}, magenta (widened chunks) {}", orange, pale, yellow, green, magenta);
            String[] hud = new String[1];
            context.runOnClient(client -> hud[0] = AreaRenderer.hudLine());
            LOG.info("[ghostpath] area HUD: {}", hud[0]);
            Path docs = Path.of("..", "..", "..", "docs", "img", "path-area.png").toAbsolutePath().normalize();
            try {
                if (Files.isDirectory(docs.getParent())) {
                    Files.copy(shot, docs, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (java.io.IOException e) {
                LOG.warn("[ghostpath] could not copy the capture to docs/img: {}", e.toString());
            }
            if (orange < 100 || pale < 60 || yellow < 40 || green < 60 || magenta < 40) {
                throw new AssertionError("area layers not all in the capture: orange " + orange + ", pale " + pale + ", yellow " + yellow + ", green " + green + ", magenta " + magenta);
            }
            if (hud[0] == null || !hud[0].contains("blocks written") || !hud[0].contains("watched margin 2") || !hud[0].contains("(8 widened)")) {
                throw new AssertionError("HUD line: " + hud[0]);
            }
            // capped: the HUD says so
            server.runOnServer(srv -> {
                List<BlockPos> writes = List.of(new BlockPos(at[0], at[1], at[2]));
                List<BlockPos> reads = new ArrayList<>(writes);
                for (int i = 0; i < 3000; i++) {
                    reads.add(new BlockPos(at[0] + 2 * (i % 60), at[1] + 2 * (i / 60) - 20, at[2] + 4));
                }
                AreaData area = AreaBuilder.build(writes, reads, 2, null, null, null);
                PathStore.publish(serial -> PathBuilder.build(serial, "rerun", "minecraft:overworld", 20, false, List.of(), List.of(), List.of()).withArea(area));
            });
            context.waitTicks(5);
            context.runOnClient(client -> hud[0] = AreaRenderer.hudLine());
            LOG.info("[ghostpath] area HUD (capped): {}", hud[0]);
            if (hud[0] == null || !hud[0].contains("CAPPED") || !hud[0].contains("2000 of 3001 boxes drawn")) {
                throw new AssertionError("capped HUD line: " + hud[0]);
            }
            PathSettings.showArea = false;
            PathSettings.areaWatched = false;
            PathSettings.areaChunks = false;
        }
    }
}
