package obsidivision.ghostpath.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import obsidivision.ghostpath.inspect.EntityInspector;
import obsidivision.ghostpath.inspect.Pin;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import obsidivision.ghostpath.record.MachineRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build step 6 on the real client, with real key and mouse input (Fabric's {@code TestInput}) and captures that include gizmos and
 * the HUD ({@link ClientShots}):
 * <ol>
 * <li>a dispenser shoots an arrow east; the camera is put exactly on one recorded point looking at it: the inspector hovers that
 *     point, the panel has the point's doubles, the captured picture differs from the same view with the paths hidden;</li>
 * <li>holding the inspect key and clicking pins it, clicking again unpins; the click attacks nothing;</li>
 * <li>pinned, a block is placed in the arrow's path (a rerun ends the path before the pin's tick: the pin stays and says why)
 *     and removed again (the next rerun: the pin finds its tick again, at the same place);</li>
 * <li>three real TNT at one place: the hover reads the server's entities (count 3, identical), the scroll wheel with the key held
 *     steps through them and does not touch the fly speed, and the box drawn is the server's.</li>
 * </ol>
 */
public class InspectorGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
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
                // on a 20-block column: the arrow flies for about 28 ticks before it lands
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

            // ---- 1. hover ------------------------------------------------------------------------------------------------------
            server.runCommand("path limit 2000");
            server.runCommand("path machine " + lever.getX() + " " + lever.getY() + " " + lever.getZ());
            server.waitFor(srv -> MachineRecorder.current() == null && PathStore.latest() != null, 6000);
            PathSnapshot snap = PathStore.latest();
            check(snap.lines().size() == 1 && snap.lines().getFirst().type().equals("minecraft:arrow") && snap.lines().getFirst().points() > POINT + 8, "snapshot: " + snap.lines().stream().map(l -> l.type() + " " + l.points() + " points, " + l.end()).toList());
            PathSnapshot.Line line = snap.lines().getFirst();
            long pinTick = line.ticks()[POINT];
            Vec3 p = pointOf(line, POINT);
            // the camera 6 blocks north of the point, looking south (yaw 0): the ray passes exactly through it
            aim(server, context, p, 6.0);
            context.waitFor(c -> Inspector.hoveredGhost() != null, 200);
            Inspector.GhostHover hover = context.computeOnClient(c -> Inspector.hoveredGhost());
            check(hover.line() == 0 && hover.point() == POINT, "hovered " + hover + ", wanted point " + POINT);
            context.waitTicks(2);
            List<String> panel = Inspector.lastHoverPanel;
            LOG.info("[ghostpath] inspector test: hovering point {} (t+{}); panel {}", POINT, pinTick, panel);
            check(panel.contains("pos x " + line.xyz()[3 * POINT]) && panel.contains("pos y " + line.xyz()[3 * POINT + 1]) && panel.contains("pos z " + line.xyz()[3 * POINT + 2]),
                "panel has not the point's position: " + panel);
            check(panel.getFirst().contains("t+" + pinTick) && panel.stream().anyMatch(l -> l.startsWith("vel x ")) && panel.stream().anyMatch(l -> l.startsWith("block ")), "panel: " + panel);
            PathSettings.showPaths = false;
            context.waitTicks(3);
            Path hidden = ClientShots.capture(context, "inspector-0-hidden");
            PathSettings.showPaths = true;
            context.waitTicks(3);
            Path shown = ClientShots.capture(context, "inspector-1-hover");
            int changed = ClientShots.countDifferent(hidden, shown, 40);
            LOG.info("[ghostpath] inspector test: {} pixels differ from the same view with the paths hidden", changed);
            check(changed > 1500, "the box and panel are not in the capture: " + changed + " pixels differ");
            keepForDocs(shown, "inspector-ghost.png");

            // a block between the camera and the point hides it, unless the inspect key is held
            BlockPos wall = BlockPos.containing(p.x, p.y, p.z - 3.0);
            server.runOnServer(srv -> srv.overworld().setBlock(wall, Blocks.STONE.defaultBlockState(), 3));
            context.waitTicks(5);
            check(context.computeOnClient(c -> Inspector.hoveredGhost()) == null, "the point was hovered through a block");
            context.getInput().holdKey(GhostPathClient.INSPECT);
            context.waitTicks(3);
            Inspector.GhostHover through = context.computeOnClient(c -> Inspector.hoveredGhost());
            check(through != null && through.point() == POINT, "with the key held the ray must go through the block: " + through);
            context.getInput().releaseKey(GhostPathClient.INSPECT);
            server.runOnServer(srv -> srv.overworld().setBlock(wall, Blocks.AIR.defaultBlockState(), 3));
            context.waitTicks(5);
            check(context.computeOnClient(c -> Inspector.hoveredGhost()) != null, "the point is not hovered again without the block");

            // ---- 2. pin and unpin with the real key and mouse --------------------------------------------------------------------
            check(Inspector.pins().isEmpty(), "pins before");
            click(context);
            check(Inspector.pins().isEmpty(), "a click without the key pinned");
            context.getInput().holdKey(GhostPathClient.INSPECT);
            context.waitTicks(3);
            context.getInput().pressMouse(0);
            context.waitTicks(3);
            Pin expected = new Pin("minecraft:arrow", line.members()[0], pinTick);
            check(Inspector.pins().equals(List.of(expected)), "pins after the first click: " + Inspector.pins() + ", wanted " + expected);
            context.getInput().pressMouse(0);
            context.waitTicks(3);
            check(Inspector.pins().isEmpty(), "pins after the second click: " + Inspector.pins());
            context.getInput().pressMouse(0);
            context.waitTicks(3);
            context.getInput().releaseKey(GhostPathClient.INSPECT);
            check(Inspector.pins().equals(List.of(expected)), "pinned again: " + Inspector.pins());
            Pin.Resolution r0 = Inspector.resolve(0);
            check(r0.found() && r0.line() == 0 && r0.point() == POINT, "the pin resolves to " + r0);
            context.waitTicks(3);
            ClientShots.capture(context, "inspector-2-pinned");

            // ---- 3. the pin across reruns ----------------------------------------------------------------------------------------
            // a block in the path, 3 points before the pin's: the rerun's arrow ends there, before the pin's tick
            Vec3 early = pointOf(line, POINT - 3);
            BlockPos obstacle = BlockPos.containing(early);
            long serial = snap.serial();
            server.runOnServer(srv -> srv.overworld().setBlock(obstacle, Blocks.STONE.defaultBlockState(), 3));
            server.waitFor(srv -> PathStore.latest().serial() != serial && PathStore.latest().source().equals("rerun"), 1500);
            PathSnapshot blocked = PathStore.latest();
            Pin.Resolution r1 = Inspector.resolve(0);
            LOG.info("[ghostpath] inspector test: after the obstacle: {} lines, arrow ends {}; pin: {}", blocked.lines().size(), blocked.lines().getFirst().end(), r1.problem());
            check(Inspector.pins().size() == 1 && !r1.found() && r1.problem().contains("no point at t+" + pinTick), "the pin after the obstacle: " + r1);
            context.waitTicks(3);
            ClientShots.capture(context, "inspector-3-pin-lost");
            long serial2 = blocked.serial();
            server.runOnServer(srv -> srv.overworld().setBlock(obstacle, Blocks.AIR.defaultBlockState(), 3));
            server.waitFor(srv -> PathStore.latest().serial() != serial2 && PathStore.latest().source().equals("rerun"), 1500);
            PathSnapshot back = PathStore.latest();
            Pin.Resolution r2 = Inspector.resolve(0);
            check(r2.found() && back.lines().get(r2.line()).ticks()[r2.point()] == pinTick, "the pin after the obstacle is gone: " + r2.problem());
            PathSnapshot.Line again = back.lines().get(r2.line());
            check(again.xyz()[3 * r2.point()] == line.xyz()[3 * POINT] && again.xyz()[3 * r2.point() + 1] == line.xyz()[3 * POINT + 1] && again.xyz()[3 * r2.point() + 2] == line.xyz()[3 * POINT + 2],
                "the pin found another place at t+" + pinTick);
            Inspector.clearPins();

            // ---- 4. real TNT in a stack ---------------------------------------------------------------------------------------------
            double tx = lever.getX() - 12 + 0.5;
            double ty = lever.getY() + 4.0;
            double tz = lever.getZ() + 0.5;
            for (int i = 0; i < 3; i++) {
                server.runCommand("summon tnt " + tx + " " + ty + " " + tz + " {fuse:2000,NoGravity:1b}");
            }
            // 7 blocks north of it, eye level in the middle of its box, looking south
            aim(server, context, new Vec3(tx, ty + 0.49, tz), 7.0);
            float flySpeed = context.computeOnClient(c -> c.player.getAbilities().getFlyingSpeed());
            context.waitFor(c -> Inspector.hoveredReal() != null && Inspector.realData() != null && Inspector.realData().hoveredId() == Inspector.hoveredReal().id(), 400);
            Inspector.RealData data = context.computeOnClient(c -> Inspector.realData());
            check(data.stack().members().size() == 3 && data.stack().identical(), "stack: " + data.stack().members().size() + ", identical " + data.stack().identical());
            context.waitTicks(3);
            List<String> real = Inspector.lastHoverPanel;
            check(real.stream().anyMatch(l -> l.equals("stack x3, all identical")) && real.getFirst().contains("(real, server)") && real.stream().anyMatch(l -> l.startsWith("fuse ")), "real panel: " + real);
            // the box drawn is the server's: every member's box equals the server entity's box
            boolean[] same = {true};
            server.runOnServer(srv -> {
                for (var m : Inspector.realData().stack().members()) {
                    var fresh = EntityInspector.read(srv.overworld(), m.id());
                    same[0] &= fresh != null && fresh.box().equals(m.box());
                }
            });
            check(same[0], "a member's box is not the server entity's");
            Path realShot = ClientShots.capture(context, "inspector-4-real-stack");
            keepForDocs(realShot, "inspector-real.png");
            // scroll with the key held: steps through the stack, the fly speed does not change
            int before = Inspector.realSelected();
            List<String> panelBefore = Inspector.lastHoverPanel;
            context.getInput().holdKey(GhostPathClient.INSPECT);
            context.waitTicks(3);
            context.getInput().scroll(-1.0);
            context.waitTicks(3);
            int after = Inspector.realSelected();
            check(Math.floorMod(after - before, 3) == 1, "the scroll did not step: " + before + " -> " + after);
            context.getInput().scroll(1.0);
            context.waitTicks(3);
            check(Math.floorMod(Inspector.realSelected() - before, 3) == 0, "the scroll back did not return: " + Inspector.realSelected());
            context.getInput().releaseKey(GhostPathClient.INSPECT);
            float flyAfter = context.computeOnClient(c -> c.player.getAbilities().getFlyingSpeed());
            check(flySpeed == flyAfter, "the scroll went to the fly speed: " + flySpeed + " -> " + flyAfter);
            check(panelBefore.stream().anyMatch(l -> l.contains((before + 1) + "/3")), "the panel did not show " + (before + 1) + "/3: " + panelBefore);
            LOG.info("[ghostpath] inspector test: real TNT stack of {}, identical, scroll {} -> {} -> back", data.stack().members().size(), before, after);
        }
    }

    private static Vec3 pointOf(PathSnapshot.Line l, int i) {
        return new Vec3(l.xyz()[3 * i], l.xyz()[3 * i + 1] + PathRenderer.lift(l.kind()), l.xyz()[3 * i + 2]);
    }

    /** Puts the spectator {@code back} blocks north of {@code target}, eye at its height, looking south: the camera ray passes through it. */
    private static void aim(TestServerContext server, ClientGameTestContext context, Vec3 target, double back) {
        server.runCommand("tp @a " + target.x + " " + (target.y - 1.62) + " " + (target.z - back) + " 0 0");
        context.waitTicks(5);
    }

    private static void click(ClientGameTestContext context) {
        context.getInput().pressMouse(0);
        context.waitTicks(3);
    }

    private static void keepForDocs(Path shot, String name) {
        try {
            Path dir = Path.of(System.getProperty("ghostpath.docsImages", "")).toAbsolutePath();
            if (System.getProperty("ghostpath.docsImages") != null && Files.isDirectory(dir)) {
                Files.copy(shot, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
