package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.Arrays;
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
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.client.GhostPathClient;
import obsidivision.ghostpath.client.Inspector;
import obsidivision.ghostpath.client.PathRenderer;
import obsidivision.ghostpath.preview.PathMorph;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.saved.SavedStore;

/**
 * Session D on the real client, checked through data (no screenshots): after a rerun the hover and the pins read the final snapshot while the
 * morph runs (its drawn frame differs from the final data, the snapshot arrays never change), the drawn data is the new prediction once it ends;
 * a saved path and the live path are drawn together and the saved one is hoverable with the {@code saved:} line, cannot be pinned, and hides with
 * {@code /path show off}.
 */
public class SavedAnimClientGameTest implements FabricClientGameTest {
    private static final int POINT = 12;

    @Override
    public void runTest(ClientGameTestContext context) {
        obsidivision.ghostpath.PathSettings.hoverDetail = "full"; // the tests read the exact lines
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            BlockPos[] machine = new BlockPos[2];
            server.runOnServer(srv -> {
                ServerLevel level = srv.overworld();
                BlockPos spawn = level.getRespawnData().pos();
                int x = spawn.getX() + 2;
                int z = spawn.getZ() + 20;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos disp = new BlockPos(x, y + 20, z);
                for (int dy = 0; dy < 20; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(x - 1, y + dy, z), Blocks.STONE.defaultBlockState(), 3);
                }
                level.setBlock(disp, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST), 3);
                ((DispenserBlockEntity) level.getBlockEntity(disp)).setItem(0, new ItemStack(Items.ARROW, 4));
                BlockPos lever = disp.west();
                level.setBlock(lever.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR), 3);
                machine[0] = disp;
                machine[1] = lever;
            });
            BlockPos lever = machine[1];
            server.runCommand("gamemode spectator @a");
            context.waitTicks(20);
            PathSettings.animationTestScale = 12.0; // 4.8 s: long enough to look at it while it runs
            try {
                server.runCommand("path limit 2000");
                server.runCommand("path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
                server.waitFor(srv -> MachineRecorder.current() == null && PathStore.latest() != null, 6000);
                PathSnapshot first = PathStore.latest();
                PathSnapshot.Line line = first.lines().getFirst();
                check(first.lines().size() == 1 && line.points() > POINT + 8 && first.origin() != null, "snapshot: " + first.lines().size() + " lines");
                Vec3 p = pointOf(line, POINT);
                aim(server, context, p, 6.0);

                // ---- save: the saved copy is drawn next to the live path --------------------------------------------------------
                server.runCommand("path saved hide all");
                server.runCommand("path save keep");
                server.waitFor(srv -> SavedStore.of(srv).get("keep") != null, 200);
                context.waitTicks(5);
                check(SavedStore.visible().stream().anyMatch(s -> s.name().equals("keep")), "the saved path is not shown after saving");
                check(context.computeOnClient(c -> PathRenderer.lastLines) == 1, "live lines drawn");
                check(PathRenderer.capNote.isEmpty(), "cap note");

                // ---- a rerun: the morph runs; hover and the snapshot are the final data ----------------------------------------------
                double[] before = line.xyz().clone();
                Vec3 early = pointOf(line, POINT - 3);
                BlockPos obstacle = BlockPos.containing(early);
                long serial = first.serial();
                server.runOnServer(srv -> srv.overworld().setBlock(obstacle, Blocks.STONE.defaultBlockState(), 3));
                server.waitFor(srv -> PathStore.latest().serial() != serial && PathStore.latest().source().equals("rerun"), 1500);
                PathSnapshot second = PathStore.latest();
                context.waitFor(c -> PathRenderer.lastMorphing, 60);
                PathMorph.Frame drawn = PathRenderer.lastFrame;
                check(drawn != null && !drawn.lines().isEmpty(), "no morph frame was drawn after the rerun");
                PathSnapshot.Line fin = second.lines().getFirst();
                boolean differs = drawn.lines().stream().anyMatch(l -> !Arrays.equals(l.xyz(), fin.xyz()) || l.alpha() != 1.0);
                check(differs, "the drawn frame already equals the final data at the start of the morph");
                // hover (the live point near the obstacle, before the arrow's end) reads the snapshot, not the animated positions
                Vec3 pe = pointOf(fin, Math.min(fin.points() - 1, POINT - 6));
                aim(server, context, pe, 6.0);
                context.waitFor(c -> Inspector.hoveredGhost() != null, 100);
                Inspector.GhostHover h = context.computeOnClient(c -> Inspector.hoveredGhost());
                check(h != null && h.snapshot() == PathStore.latest() && h.snapshot().lines().getFirst().xyz() == fin.xyz(), "the hover is not on the final snapshot");
                List<String> panel = Inspector.lastHoverPanel;
                int hp = h.point();
                check(panel.contains("pos x " + fin.xyz()[3 * hp]) && panel.contains("pos y " + fin.xyz()[3 * hp + 1]), "the panel shows animated positions: " + panel);
                // pin while animating: it names the entity and tick of the final data
                context.getInput().holdKey(GhostPathClient.INSPECT);
                context.waitTicks(3);
                context.getInput().pressMouse(0);
                context.waitTicks(3);
                check(Inspector.pins().size() == 1 && Inspector.pins().getFirst().tick() == fin.ticks()[hp], "pin while animating: " + Inspector.pins());
                Inspector.clearPins();
                context.getInput().releaseKey(GhostPathClient.INSPECT);
                // the animation ends: the drawn data is the new prediction, and no snapshot was written to
                context.waitFor(c -> !PathRenderer.lastMorphing, 600);
                context.waitTicks(3);
                check(PathRenderer.lastFrame == null && PathStore.latest() == second, "after the morph the snapshot itself is drawn");
                check(Arrays.equals(before, first.lines().getFirst().xyz()), "the morph wrote into the old snapshot");
                check(Arrays.equals(fin.xyz(), PathStore.latest().lines().getFirst().xyz()), "the new snapshot changed");

                // ---- the saved path is hoverable with its line; cannot be pinned; hides with /path show off --------------------------
                aim(server, context, p, 6.0); // the point the live path no longer reaches
                context.waitFor(c -> Inspector.hoveredSaved() != null, 200);
                Inspector.SavedHover sv = context.computeOnClient(c -> Inspector.hoveredSaved());
                check(sv != null && sv.saved().name().equals("keep") && sv.point() == POINT, "saved hover: " + sv);
                context.waitTicks(2);
                List<String> sp = Inspector.lastHoverPanel;
                check(sp.stream().anyMatch(l -> l.startsWith("saved: keep, ")) && sp.contains("pos x " + line.xyz()[3 * POINT]), "saved panel: " + sp);
                context.getInput().holdKey(GhostPathClient.INSPECT);
                context.waitTicks(3);
                context.getInput().pressMouse(0);
                context.waitTicks(3);
                check(Inspector.pins().isEmpty(), "a saved point was pinned");
                context.getInput().releaseKey(GhostPathClient.INSPECT);
                server.runCommand("path show off");
                context.waitTicks(4);
                check(context.computeOnClient(c -> Inspector.hoveredSaved()) == null, "saved path hovered while hidden");
                server.runCommand("path show on");
                server.runCommand("path saved hide keep");
                context.waitTicks(4);
                check(context.computeOnClient(c -> Inspector.hoveredSaved()) == null, "a hidden saved path is hovered");
                server.runCommand("path saved delete keep");
                server.runCommand("path saved delete keep confirm");
                server.waitFor(srv -> SavedStore.of(srv).get("keep") == null, 200);
            } finally {
                PathSettings.animationTestScale = 1.0;
            }
        }
    }

    private static Vec3 pointOf(PathSnapshot.Line l, int i) {
        return new Vec3(l.xyz()[3 * i], l.xyz()[3 * i + 1] + PathRenderer.lift(l.kind()), l.xyz()[3 * i + 2]);
    }

    private static void aim(TestServerContext server, ClientGameTestContext context, Vec3 target, double back) {
        server.runCommand("tp @a " + target.x + " " + (target.y - 1.62) + " " + (target.z - back) + " 0 0");
        context.waitTicks(5);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
