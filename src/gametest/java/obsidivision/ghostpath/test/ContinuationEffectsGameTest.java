package obsidivision.ghostpath.test;

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
import obsidivision.ghostpath.client.Tails;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.preview.PreviewSim;
import obsidivision.ghostpath.record.MachineRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's continuation of a projectile makes no particles and no sounds (user request, step 6 session). The projectile is an
 * arrow that a dispenser shoots straight up into a 13x13 pool of water; the recording is limited to 10 ticks, so the client
 * continues the fall: the arrow enters the water, which in vanilla spawns splash and bubble particles and a splash sound
 * ({@code Entity.doWaterSplashEffect}, Entity.java:1671, and bubbles every tick in the water, AbstractArrow.java:218).
 * <ul>
 * <li>positive control: the continuation did ask for particles ({@code PreviewSim.suppressedParticles} counts what the client level
 *     dropped: 26 here), so the scenario does exercise the effects. Sounds cannot be controlled this way: the client level plays
 *     an entity's sound only for the local player ({@code ClientLevel.playSeededSound}), so the arrow's splash sound never gets
 *     as far as the funnel we drop at ({@code suppressedSounds} is 0); the sound guard is a safety net;</li>
 * <li>nothing reached the particle engine or the sound manager ({@code PreviewSim.leaked*} stay 0).</li>
 * </ul>
 */
public class ContinuationEffectsGameTest implements FabricClientGameTest {
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
                // a pool 13x13, three deep, its surface level with the dispenser's base; the dispenser stands in its middle
                for (int dx = -6; dx <= 6; dx++) {
                    for (int dz = -6; dz <= 6; dz++) {
                        level.setBlock(new BlockPos(x + dx, y - 3, z + dz), Blocks.STONE.defaultBlockState(), 3);
                        for (int dy = 1; dy <= 2; dy++) {
                            level.setBlock(new BlockPos(x + dx, y - dy, z + dz), Blocks.WATER.defaultBlockState(), 3);
                        }
                    }
                }
                BlockPos disp = new BlockPos(x, y, z);
                level.setBlock(disp.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(disp, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.UP), 3);
                ((DispenserBlockEntity) level.getBlockEntity(disp)).setItem(0, new ItemStack(Items.ARROW));
                BlockPos lever = disp.west();
                level.setBlock(lever.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR), 3);
                machine[0] = disp;
                machine[1] = lever;
            });
            BlockPos disp = machine[0];
            BlockPos lever = machine[1];
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a " + (disp.getX() - 12) + " " + (disp.getY() + 10) + " " + disp.getZ() + " -90 25");
            context.waitTicks(40);

            server.runCommand("path limit 10");
            server.runCommand("path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
            server.waitFor(srv -> MachineRecorder.current() == null && PathStore.latest() != null);
            PathSnapshot snapshot = PathStore.latest();
            PathSnapshot.Line line = snapshot.lines().getFirst();
            check(snapshot.limitHit() && snapshot.lines().size() == 1 && line.end().reason().equals("limit") && line.tail() != null,
                "limited snapshot: " + snapshot.lines().stream().map(l -> l.type() + " " + l.end()).toList());
            context.waitFor(client -> {
                Tails.Job j = Tails.job(snapshot, 0);
                return j != null && j.done();
            }, 600);
            String why = context.computeOnClient(client -> Tails.job(snapshot, 0).endReason());
            int points = context.computeOnClient(client -> Tails.job(snapshot, 0).points());
            double minY = context.computeOnClient(client -> {
                double[] xyz = Tails.job(snapshot, 0).xyz.toDoubleArray();
                double m = Double.MAX_VALUE;
                for (int i = 1; i < xyz.length; i += 3) {
                    m = Math.min(m, xyz[i]);
                }
                return m;
            });
            int particles = PreviewSim.suppressedParticles;
            int sounds = PreviewSim.suppressedSounds;
            int events = PreviewSim.suppressedLevelEvents;
            int leakedParticles = PreviewSim.leakedParticles;
            int leakedSounds = PreviewSim.leakedSounds;
            LOG.info("[ghostpath] effects test: continuation {} points, lowest y {}, ends {}; the client level dropped {} particles, {} sounds, {} level events; leaked: {} particles, {} sounds",
                points, minY, why, particles, sounds, events, leakedParticles, leakedSounds);
            check(points > 10 && why.equals("stuck"), "the continuation: " + points + " points, " + why);
            check(minY < disp.getY() - 0.5, "the arrow did not get into the water: lowest y " + minY + ", the surface is at y " + disp.getY());
            check(particles > 0, "positive control: the continuation asked for " + particles + " particles");
            check(leakedParticles == 0 && leakedSounds == 0, "effects got through: " + leakedParticles + " particles, " + leakedSounds + " sounds");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
