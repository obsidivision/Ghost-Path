package obsidivision.ghostpath.test;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.item.PrimedTnt;
import obsidivision.ghostpath.client.Estimates;
import obsidivision.ghostpath.client.RemotePaths;
import obsidivision.ghostpath.preview.*;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;

public class ServerModesClientTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var server = context.worldBuilder().createServer(); var connection = server.connect()) {
            context.waitFor(mc -> mc.level != null && mc.getSingleplayerServer() == null);
            long before = RemotePaths.receivedSnapshots;
            var sentLine = new PathSnapshot.Line("minecraft:tnt", FlightKind.TNT, 1, 0, new long[]{0}, new double[]{0.1+0.2, -0.0, 3}, new double[]{0,0,0}, new double[]{0,0,0,1,1,1}, new int[]{12}, new int[]{7}, new PathSnapshot.End("exploded",0,0,0,0),null,null);
            server.runOnServer(srv -> PathStore.publish(id -> new PathSnapshot(id,"rerun","minecraft:overworld",73,false,List.of(sentLine))));
            context.waitFor(mc -> RemotePaths.receivedSnapshots > before && PathStore.clientLatest() != null && PathStore.clientLatest().ticks()==73);
            context.runOnClient(mc -> {
                if (!java.util.Arrays.equals(PathStore.clientLatest().lines().getFirst().xyz(), sentLine.xyz())) throw new AssertionError("remote coordinates changed");
                var pos = mc.player.position();
                PrimedTnt original = new PrimedTnt(mc.level,pos.x,pos.y+4,pos.z,null);
                original.setId(123456789);
                original.setFuse(12);
                var velocity = original.getDeltaMovement(); var position = original.position();
                var line = Estimates.predict(mc.level,original);
                if(original.getFuse()!=12 || !original.position().equals(position) || !original.getDeltaMovement().equals(velocity)) throw new AssertionError("estimate changed original TNT");
                if(!line.end().reason().startsWith("estimate:") || line.points()<1 || line.points()>161) throw new AssertionError("unlabelled or unbounded estimate");
                var arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(net.minecraft.world.entity.EntityTypes.ARROW, mc.level);
                arrow.setId(123456788); arrow.setPos(pos.x,pos.y+8,pos.z); arrow.setDeltaMovement(0,1,0);
                mc.level.addEntity(arrow);
                try {
                    var path = Estimates.predict(mc.level,arrow);
                    obsidivision.ghostpath.GhostPath.LOGGER.info("Projectile estimate fixture: {} points, {}", path.points(), path.end());
                    if (path.points()<2 || arrow.getUUID().equals(PreviewSim.lastHitEntity)) throw new AssertionError("projectile estimate hit its original");
                    if (mc.level.getEntity(arrow.getId())!=arrow || arrow.getDeltaMovement().y!=1.0) throw new AssertionError("projectile copy changed original");
                } finally { mc.level.removeEntity(arrow.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED); }
                if(PreviewSim.active() || PreviewSim.leakedParticles!=0 || PreviewSim.leakedSounds!=0) throw new AssertionError("estimate effects leaked");
            });
        }
        context.waitFor(mc -> mc.level == null);
        if(PathStore.clientLatest()!=PathStore.latest()) throw new AssertionError("remote snapshot survived disconnect");
    }
}
