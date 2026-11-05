package obsidivision.ghostpath.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.preview.PathBuilder;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;
import obsidivision.ghostpath.record.CauseTracker.FlightNote;
import obsidivision.ghostpath.record.CauseTracker.FlightPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real client, real capture: a rerun snapshot with one TNT path that crosses into a chunk the real world does not tick (flagged "lazy"
 * in the snapshot, as {@code Prediction.notTicking} would). The capture must show the TNT colour (red) before the chunk border, the
 * faded dashed part after it, and the amber freeze dot; the label text itself is not read from pixels.
 */
public class FreezeDrawGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    @Override
    public void runTest(ClientGameTestContext context) {
        PathSettings.showPaths = true;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            double[] where = new double[3];
            server.runOnServer(srv -> {
                var level = srv.overworld();
                var spawn = level.getRespawnData().pos();
                int cx = (spawn.getX() >> 4) + 1;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, cx * 16, spawn.getZ()) + 2;
                where[0] = cx * 16;
                where[1] = y;
                where[2] = spawn.getZ() + 0.5;
                List<FlightPoint> pts = new ArrayList<>();
                // a TNT flying east 1 block a tick for 40 ticks, starting 20 blocks before the chunk border: the border is at point 20
                for (int k = 0; k < 40; k++) {
                    pts.add(new FlightPoint(5 + k, 0, "minecraft:tnt", FlightKind.TNT, where[0] - 20 + 1.0 * k, y, where[2], 1.0, 0.0, 0.0, false, k == 39, false));
                }
                List<FlightNote> notes = List.of(new FlightNote(5 + 39, 0, "removed DISCARDED"));
                PathSnapshot snap = PathStore.publish(serial -> PathBuilder.build(serial, "rerun", "minecraft:overworld", 60, false, pts, notes, List.of(),
                    Map.of(ChunkPos.pack(cx, spawn.getZ() >> 4), "lazy")));
                if (snap.lines().getFirst().freeze() == null) {
                    throw new AssertionError("no freeze in the snapshot (the line is in chunk z " + (spawn.getZ() >> 4) + ")");
                }
            });
            server.runCommand("gamemode creative @a");
            // south of the middle of the line, looking north, a little downward
            server.runCommand("tp @a " + (where[0] - 0.0) + " " + (where[1] - 1.0) + " " + (where[2] + 45) + " 180 -3");
            context.waitTicks(60);
            Path shot = ClientShots.capture(context, "freeze-draw");
            int red = ClientShots.count(shot, c -> ClientShots.r(c) > 235 && ClientShots.g(c) < 110 && ClientShots.b(c) < 110);
            int faded = ClientShots.count(shot, c -> ClientShots.r(c) > 150 && ClientShots.r(c) < 210 && ClientShots.g(c) > 80 && ClientShots.g(c) < 125 && ClientShots.b(c) > 80 && ClientShots.b(c) < 125);
            int amber = ClientShots.count(shot, c -> ClientShots.r(c) > 235 && ClientShots.g(c) > 150 && ClientShots.g(c) < 200 && ClientShots.b(c) < 90);
            LOG.info("[ghostpath] freeze drawing: red pixels {}, faded {}, amber {}", red, faded, amber);
            if (red < 40 || faded < 40 || amber < 20) {
                throw new AssertionError("freeze style not in the capture: red " + red + ", faded " + faded + ", amber " + amber);
            }
        }
    }
}
