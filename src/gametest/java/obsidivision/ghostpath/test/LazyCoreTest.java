package obsidivision.ghostpath.test;

import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.ghost.GhostCheck;
import obsidivision.ghostpath.ghost.GhostCopy;
import obsidivision.ghostpath.ghost.GhostLevel;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ruling: lazy chunks stay lazy inside the machine's own copied area; only chunks that widening added are made "as if loaded". Setup: one
 * chunk A is forced (entity ticking, level 31); the chunk east of it, B, is loaded by the ticket spreading at level 32 (block ticking:
 * entities do not tick) and C east of B at 33.
 * <ol>
 * <li>{@code aProjectileThatEntersALazyChunkFreezesAndTheGhostCheckIsExact}: a dispenser at the east edge of A shoots a snowball east into
 *     B (a dispenser drops an ender pearl as an item in 26.2, so the recorded machine uses a snowball; the second test uses real pearls). In the real world it stops being ticked there. The ghost check (real levels, no widening) must be exact: the ghost's snowball
 *     freezes at the same tick and place.</li>
 * <li>{@code widenedRunKeepsTheCoreLazyChunkFrozen}: a copy of A..C with core = A..B: a pearl lying in B (core) stays frozen in the ghost,
 *     a pearl in C (added by widening, also lazy in the real world) ticks and flies on.</li>
 * </ol>
 */
public class LazyCoreTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");

    private static boolean ready(ServerLevel level, ChunkPos a, ChunkPos... lazy) {
        if (!level.isPositionEntityTicking(new BlockPos(a.getMiddleBlockX(), level.getMinY() + 1, a.getMiddleBlockZ()))) {
            return false;
        }
        for (ChunkPos c : lazy) {
            if (level.getChunkSource().getChunkNow(c.x(), c.z()) == null || !level.areEntitiesLoaded(c.pack())) {
                return false;
            }
        }
        return true;
    }

    private static void when(GameTestHelper helper, java.util.function.BooleanSupplier cond, int waited, Runnable then) {
        if (cond.getAsBoolean()) {
            then.run();
        } else if (waited > 300000) {
            helper.fail("chunks not ready");
        } else {
            helper.runAfterDelay(1, () -> when(helper, cond, waited + 1, then));
        }
    }

    @GameTest(maxTicks = 400000, environment = "ghostpath-gametest:lazy_core_check")
    public void aProjectileThatEntersALazyChunkFreezesAndTheGhostCheckIsExact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathSettings.kick = true;
        PathSettings.tickLimit = 40;
        BlockPos base = helper.absolutePos(BlockPos.ZERO).offset(1700, 0, -1700);
        ChunkPos a = new ChunkPos(base.getX() >> 4, base.getZ() >> 4);
        ChunkPos b = new ChunkPos(a.x() + 1, a.z());
        level.setChunkForced(a.x(), a.z(), true);
        when(helper, () -> ready(level, a, b), 0, () -> TestSupport.whenSavesFree(helper, TestSupport.once(() -> {
            int x = a.getMinBlockX() + 14;
            int z = a.getMinBlockZ() + 8;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            BlockPos disp = new BlockPos(x, ground + 3, z);
            BlockPos lever = disp.west();
            for (BlockPos p : BlockPos.betweenClosed(disp.offset(-2, -1, -2), disp.offset(1, 1, 2))) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
            for (BlockPos p : BlockPos.betweenClosed(disp.offset(-2, -1, -2), disp.offset(1, -1, 2))) {
                level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
            }
            level.setBlock(disp, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST), 3);
            ((DispenserBlockEntity) level.getBlockEntity(disp)).setItem(0, new ItemStack(Items.SNOWBALL));
            level.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR), 3);
            helper.assertTrue(!level.isPositionEntityTicking(new BlockPos(b.getMiddleBlockX(), disp.getY(), b.getMiddleBlockZ())), "B is entity ticking: it is not lazy");
            helper.runAfterDelay(2, () -> {
                Recording[] out = new Recording[1];
                String[] failed = new String[1];
                try {
                    MachineRecorder.start(level, lever, PathSettings.tickLimit, PathSettings.kick, new MachineRecorder.Listener() {
                        @Override
                        public void done(Recording recording) {
                            out[0] = recording;
                        }

                        @Override
                        public void failed(String message) {
                            failed[0] = message;
                        }
                    });
                } catch (MachineRecorder.RejectedException e) {
                    helper.fail("recording rejected: " + e.getMessage());
                    return;
                }
                waitIdle(helper, 0, () -> {
                    try {
                        helper.assertTrue(out[0] != null, "no recording: " + failed[0]);
                        Recording rec = out[0];
                        List<CauseTracker.FlightPoint> pearl = rec.flightPoints().stream().filter(p -> p.type().equals("minecraft:snowball")).toList();
                        helper.assertTrue(!pearl.isEmpty(), "no snowball in the recording: " + rec.flightPoints().stream().map(CauseTracker.FlightPoint::type).distinct().toList() + "; " + rec.summary());
                        CauseTracker.FlightPoint last = pearl.getLast();
                        helper.assertTrue((Math.floor(last.x()) >= b.getMinBlockX()) && Math.floor(last.x()) <= b.getMaxBlockX(), "the snowball did not end in B: last point x " + last.x() + ", B x " + b.getMinBlockX());
                        helper.assertTrue(last.tick() < rec.ticks() - 5, "the snowball was ticked to the end (not frozen in B): last point t+" + last.tick() + " of " + rec.ticks());
                        GhostCheck.Result r = GhostCheck.run(level, rec);
                        LOG.info("[ghostpath] lazy core check: snowball last point t+{} at x {} (B from {}), recording {} ticks:\n{}", last.tick(), last.x(), b.getMinBlockX(), rec.ticks(), String.join("\n", r.lines()));
                        helper.assertTrue(r.ran(), "ghost check did not run: " + r.note());
                        helper.assertTrue(r.exact(), "ghost check not exact:\n" + String.join("\n", r.lines()));
                        helper.assertTrue(GhostLevel.strayPackets() == 0, "packets were sent while the ghost worked");
                        helper.succeed();
                    } finally {
                        level.setChunkForced(a.x(), a.z(), false);
                    }
                });
            });
        })));
    }

    private static void waitIdle(GameTestHelper helper, int waited, Runnable then) {
        if (MachineRecorder.current() == null) {
            then.run();
        } else if (waited > 6000) {
            helper.fail("recording did not finish");
        } else {
            helper.runAfterDelay(1, () -> waitIdle(helper, waited + 1, then));
        }
    }

    private static Entity pearl(ServerLevel level, double x, double y, double z) {
        Entity e = EntityTypes.ENDER_PEARL.create(level, EntitySpawnReason.COMMAND);
        e.setPos(x, y, z);
        e.setNoGravity(true);
        e.setDeltaMovement(0.5, 0.0, 0.0);
        level.addFreshEntity(e);
        return e;
    }

    @GameTest(maxTicks = 400000, environment = "ghostpath-gametest:lazy_core_widen")
    public void widenedRunKeepsTheCoreLazyChunkFrozen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(BlockPos.ZERO).offset(-1700, 0, 1700);
        ChunkPos a = new ChunkPos(base.getX() >> 4, base.getZ() >> 4);
        ChunkPos b = new ChunkPos(a.x() + 1, a.z());
        ChunkPos c = new ChunkPos(a.x() + 2, a.z());
        level.setChunkForced(a.x(), a.z(), true);
        when(helper, () -> ready(level, a, b, c), 0, () -> TestSupport.whenSavesFree(helper, TestSupport.once(() -> {
            LOG.info("[ghostpath] lazy core widen: A {} B {} C {}", a, b, c);
            double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, b.getMiddleBlockX(), b.getMiddleBlockZ()) + 3.0;
            Entity pb = pearl(level, b.getMinBlockX() + 4.5, y, b.getMiddleBlockZ() + 0.5);
            Entity pc = pearl(level, c.getMinBlockX() + 4.5, y, c.getMiddleBlockZ() + 0.5);
            Vec3 pbStart = pb.position();
            Vec3 pcStart = pc.position();
            GhostLevel ghost = null;
            try {
                ghost = GhostLevel.create(level);
                GhostCopy.Prepared prepared = GhostCopy.prepare(level, ghost, a, c, GhostCopy.Start.NOW, true, Set.of(), new int[] {a.x(), a.z(), b.x(), b.z()});
                poll(helper, level, ghost, prepared, a, b, c, pb, pc, pbStart, pcStart, 0);
            } catch (java.io.IOException | RuntimeException e) {
                if (ghost != null) {
                    ghost.discard();
                }
                level.setChunkForced(a.x(), a.z(), false);
                throw new IllegalStateException(e);
            }
        })));
    }

    private static void poll(GameTestHelper helper, ServerLevel level, GhostLevel ghost, GhostCopy.Prepared prepared, ChunkPos a, ChunkPos b, ChunkPos c, Entity pb, Entity pc,
                             Vec3 pbStart, Vec3 pcStart, int polls) {
        boolean[] more = {false};
        try {
            if (!prepared.poll()) {
                helper.assertTrue(polls < 3000, "the ghost chunks were not ready: " + prepared.describeNotReady());
                more[0] = true;
                helper.runAfterDelay(1, () -> poll(helper, level, ghost, prepared, a, b, c, pb, pc, pbStart, pcStart, polls + 1));
                return;
            }
            prepared.finish();
            helper.assertTrue("lazy".equals(prepared.realNotTicking().get(b.pack())) && "lazy".equals(prepared.realNotTicking().get(c.pack())) && !prepared.realNotTicking().containsKey(a.pack()),
                "real status: " + prepared.realNotTicking());
            helper.assertTrue(pb.position().equals(pbStart) && pc.position().equals(pcStart), "a real pearl moved: the chunks are not lazy");
            for (int i = 0; i < 10; i++) {
                ghost.tickGhost();
            }
            Entity gb = ghost.getEntitiesOfClass(Entity.class, box(b)).stream().filter(e -> e.getType() == EntityTypes.ENDER_PEARL).findFirst().orElse(null);
            Entity gc = ghost.getEntitiesOfClass(Entity.class, box(c, 8)).stream().filter(e -> e.getType() == EntityTypes.ENDER_PEARL).findFirst().orElse(null);
            helper.assertTrue(gb != null && gc != null, "pearls in the ghost: B " + gb + ", C " + gc);
            LOG.info("[ghostpath] lazy core widen: pearl in B (core): {} -> {}; pearl in C (added): {} -> {}", pbStart, gb.position(), pcStart, gc.position());
            helper.assertTrue(gb.position().equals(pbStart), "the pearl in the core lazy chunk moved in the ghost: " + pbStart + " -> " + gb.position());
            helper.assertTrue(gc.position().x - pcStart.x > 3.0, "the pearl in the chunk added by widening did not fly: " + pcStart + " -> " + gc.position());
            helper.succeed();
        } finally {
            if (!more[0]) {
                ghost.discard();
                pb.discard();
                pc.discard();
                level.setChunkForced(a.x(), a.z(), false);
            }
        }
    }

    private static AABB box(ChunkPos c) {
        return box(c, 0);
    }

    private static AABB box(ChunkPos c, int grow) {
        return new AABB(c.getMinBlockX() - grow, -100, c.getMinBlockZ() - grow, c.getMaxBlockX() + 1 + grow, 400, c.getMaxBlockZ() + 1 + grow);
    }
}
