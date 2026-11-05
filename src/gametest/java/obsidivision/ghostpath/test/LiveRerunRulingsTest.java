package obsidivision.ghostpath.test;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.watch.LiveWatch;
import obsidivision.ghostpath.watch.Prediction;
import obsidivision.ghostpath.watch.Watched;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rulings after build step 4:
 * <ol>
 * <li>a rerun predicts the same press as the recording: the ghost's trigger is put back to the state the recording found
 *     it in, without updates, then pressed; a real trigger in another state is named in the chat line;</li>
 * <li>a change of the contents of a footprint block entity (setItem, removeItem, removeItemNoUpdate) is a change;</li>
 * <li>a non-chain entity added to or removed from the footprint, or settling at a new place in it, is a change; ordinary
 *     movement is not.</li>
 * </ol>
 */
public class LiveRerunRulingsTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    private static final BlockPos D_LEVER = new BlockPos(1, 2, 2);
    private static final BlockPos DROPPER = new BlockPos(2, 2, 2);
    private static final BlockPos CHEST = new BlockPos(6, 2, 6);

    // ---- ruling 1 ----

    /**
     * The piston machine recorded with the lever off. The player's pull is put in the real world without any update (the
     * machine does not fire): the lever is on. The rerun must still predict the recorded pull: TNT lit at the recording's
     * tick, and the chat line says "real lever is on; prediction is for off → on". Without the ruling the ghost's lever
     * (on, as copied) would be pulled off and the ghost would do nothing.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:live_rerun_press")
    public void rerunPredictsTheRecordedPress(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LiveRerunTest.build(helper);
        LiveRerunTest.Capture chat = new LiveRerunTest.Capture();
        LiveRerunTest.whenTicking(helper, LiveRerunTest.CORNERS, TestSupport.once(() -> LiveRerunTest.recordAndWatch(helper, level, chat, LiveRerunTest.LEVER, (rec, watch) -> {
            try {
                List<Integer> before = Prediction.tntSpawnTicks(rec.events(), rec.entityTrace());
                helper.assertTrue(before.size() == 1, "the recording lit " + before.size() + " TNT");
                BlockPos lever = helper.absolutePos(LiveRerunTest.LEVER);
                helper.assertTrue(rec.triggerStateAtStart().is(Blocks.LEVER) && !rec.triggerStateAtStart().getValue(LeverBlock.POWERED), "recorded trigger state: " + rec.triggerStateAtStart());
                level.setBlock(lever, level.getBlockState(lever).setValue(LeverBlock.POWERED, true), Block.UPDATE_CLIENTS);
                helper.assertTrue(watch.dirty(), "the lever change was not seen");
                LiveRerunTest.waitFor(helper, "the rerun", 60000, () -> {
                    helper.assertTrue(level.getBlockState(helper.absolutePos(LiveRerunTest.TNT)).is(Blocks.TNT), "the real TNT block is gone");
                    helper.assertTrue(level.getEntitiesOfClass(PrimedTnt.class, helper.getBounds().inflate(24)).isEmpty(), "a real TNT was lit");
                    helper.assertTrue(!level.getBlockState(helper.absolutePos(LiveRerunTest.P1)).getValue(PistonBaseBlock.EXTENDED), "the real piston extended");
                }, () -> watch.reruns() >= 1, () -> {
                    try {
                        Prediction p = watch.latest();
                        String line = chat.lines.stream().filter(l -> l.startsWith("rerun:")).findFirst().orElse("");
                        LOG.info("[ghostpath] chat: {}", line);
                        helper.assertTrue(p.tntSpawnTicks().equals(before), "TNT " + p.tntSpawnTicks() + ", recording " + before + " (the rerun did not predict the recorded pull)");
                        helper.assertTrue(line.contains("real lever is on; prediction is for off → on"), "chat line: " + line);
                        helper.assertTrue(line.contains("ghost created in "), "no ghost creation time: " + line);
                        helper.assertTrue(level.getBlockState(lever).getValue(LeverBlock.POWERED), "the real lever was changed by the rerun");
                        LiveRerunTest.finish(helper);
                        helper.succeed();
                    } catch (RuntimeException e) {
                        LiveRerunTest.finish(helper);
                        throw e;
                    }
                });
            } catch (RuntimeException e) {
                LiveRerunTest.finish(helper);
                throw e;
            }
        })), 0);
    }

    // ---- the dropper machine: lever -> dropper (east) with one stick; a chest elsewhere ----

    private static void buildDropper(GameTestHelper helper, ServerLevel level) {
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        for (int x = -1; x <= 10; x++) {
            for (int z = 0; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        helper.setBlock(DROPPER, Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        ((DispenserBlockEntity) level.getBlockEntity(helper.absolutePos(DROPPER))).setItem(0, new ItemStack(Items.STICK));
        helper.setBlock(D_LEVER, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        helper.setBlock(CHEST, Blocks.CHEST);
    }

    /**
     * Ruling 2. Recorded: the dropper throws a stick. A chest outside the footprint gets an item: no change. Then the dropper's
     * contents change by setItem (a feather): a change; the rerun drops the feather. Then removeItem, setItem and
     * removeItemNoUpdate on it: three more changes; the rerun drops nothing.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:live_rerun_contents")
    public void containerContentsAreFootprintChanges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildDropper(helper, level);
        LiveRerunTest.Capture chat = new LiveRerunTest.Capture();
        LiveRerunTest.whenTicking(helper, List.of(DROPPER), TestSupport.once(() -> LiveRerunTest.recordAndWatch(helper, level, chat, D_LEVER, (rec, watch) -> {
            try {
                DispenserBlockEntity dropper = (DispenserBlockEntity) level.getBlockEntity(helper.absolutePos(DROPPER));
                ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(helper.absolutePos(CHEST));
                helper.assertTrue(watch.footprint().containsPosition(helper.absolutePos(DROPPER)), "the dropper is not in the footprint: " + watch.footprint());
                helper.assertTrue(!watch.footprint().containsPosition(helper.absolutePos(CHEST)), "the chest is in the footprint");
                helper.assertTrue(rec.events().stream().anyMatch(e -> e.detail().contains("item=1 minecraft:stick")), "the recording dropped no stick");
                int changes = watch.changes();
                chest.setItem(0, new ItemStack(Items.DIAMOND));
                helper.assertTrue(watch.changes() == changes && !watch.dirty(), "a chest outside the footprint counted as a change");
                dropper.setItem(0, new ItemStack(Items.FEATHER));
                helper.assertTrue(watch.changes() > changes && watch.dirty(), "setItem on the dropper was not seen"); // more than one: Randomizable.setItem calls Base.setItem, both hooked
                LiveRerunTest.waitFor(helper, "the first rerun", 60000, () -> {}, () -> watch.reruns() >= 1, () -> {
                    try {
                        Prediction p = watch.latest();
                        helper.assertTrue(p.events().stream().anyMatch(e -> e.detail().contains("item=1 minecraft:feather")), "the rerun did not drop the feather");
                        helper.assertTrue(p.events().stream().noneMatch(e -> e.detail().contains("minecraft:stick")), "the rerun dropped the old stick");
                        int c0 = watch.changes();
                        dropper.removeItem(0, 1);
                        int c1 = watch.changes();
                        helper.assertTrue(c1 > c0, "removeItem was not seen");
                        dropper.setItem(1, new ItemStack(Items.STRING));
                        int c2 = watch.changes();
                        helper.assertTrue(c2 > c1, "setItem on a second slot was not seen");
                        dropper.removeItemNoUpdate(1);
                        int c3 = watch.changes();
                        helper.assertTrue(c3 > c2, "removeItemNoUpdate was not seen");
                        dropper.setItem(2, ItemStack.EMPTY);
                        helper.assertTrue(watch.changes() == c3, "setItem(empty) on an empty slot counted as a change");
                        LiveRerunTest.waitFor(helper, "the second rerun", 60000, () -> {}, () -> watch.reruns() >= 2, () -> {
                            try {
                                Prediction q = watch.latest();
                                helper.assertTrue(q.events().stream().noneMatch(e -> e.detail().contains("item=")), "the empty dropper dropped something");
                                LOG.info("[ghostpath] chat: {}", chat.lines.get(chat.lines.size() - 1));
                                LiveRerunTest.finish(helper);
                                helper.succeed();
                            } catch (RuntimeException e) {
                                LiveRerunTest.finish(helper);
                                throw e;
                            }
                        });
                    } catch (RuntimeException e) {
                        LiveRerunTest.finish(helper);
                        throw e;
                    }
                });
            } catch (RuntimeException e) {
                LiveRerunTest.finish(helper);
                throw e;
            }
        })), 0);
    }

    // ---- ruling 3 ----

    /** A spot on the floor where a feather is inside the footprint, also half a block east of it and 0.15 either side. */
    private static Vec3 spot(GameTestHelper helper, Watched watch) {
        double y = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
        for (AABB a : watch.footprint().areas()) {
            for (double x = Math.floor(a.minX) + 0.5; x <= a.maxX; x += 1.0) {
                for (double z = Math.floor(a.minZ) + 0.5; z <= a.maxZ; z += 1.0) {
                    boolean all = true;
                    for (double dx : new double[] {-0.15, 0.0, 0.15, 0.5, 0.65}) {
                        all &= watch.footprint().touchesEntity(featherBox(x + dx, y, z));
                    }
                    if (all) {
                        return new Vec3(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    private static AABB featherBox(double x, double y, double z) {
        return new AABB(x - 0.125, y, z - 0.125, x + 0.125, y + 0.25, z + 0.125);
    }

    /**
     * Ruling 3. A feather appears on the floor in an entity area of the dropper machine: added, a rerun. It is then moved
     * back and forth by 0.15 for 30 ticks: not a change. Then it is put half a block east and left: after 8 ticks it has
     * settled at a new place: a change, a rerun. Then it is removed: a change, a rerun. The counters say which rule fired.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:live_rerun_entities")
    public void entityChangesCountAndMovementDoesNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildDropper(helper, level);
        LiveRerunTest.Capture chat = new LiveRerunTest.Capture();
        LiveRerunTest.whenTicking(helper, List.of(DROPPER), TestSupport.once(() -> LiveRerunTest.recordAndWatch(helper, level, chat, D_LEVER, (rec, watch) -> {
            try {
                Vec3 base = spot(helper, watch);
                helper.assertTrue(base != null, "no floor spot inside the entity areas (" + watch.footprint() + ")");
                LOG.info("[ghostpath] entity watch: feather at {}", base);
                // one tick for the baseline scan, then the feather
                TestSupport.later(helper, 3, () -> {
                    try {
                        ItemEntity feather = new ItemEntity(level, base.x, base.y, base.z, new ItemStack(Items.FEATHER), 0.0, 0.0, 0.0);
                        feather.setNeverPickUp();
                        feather.setUnlimitedLifetime();
                        level.addFreshEntity(feather);
                        TestSupport.later(helper, 2, () -> {
                            try {
                                helper.assertTrue(watch.entitiesAdded() == 1 && watch.dirty(), "the feather was not seen as added: " + watch.entitiesAdded());
                                LiveRerunTest.waitFor(helper, "the rerun after the feather", 60000, () -> {}, () -> watch.reruns() >= 1, () -> {
                                    try {
                                        helper.assertTrue(!feather.isRemoved() && feather.position().distanceTo(base) < 1.0E-3, "the feather moved: " + feather.position());
                                        moveAbout(helper, level, watch, feather, base, chat);
                                    } catch (RuntimeException e) {
                                        LiveRerunTest.finish(helper);
                                        throw e;
                                    }
                                });
                            } catch (RuntimeException e) {
                                LiveRerunTest.finish(helper);
                                throw e;
                            }
                        });
                    } catch (RuntimeException e) {
                        LiveRerunTest.finish(helper);
                        throw e;
                    }
                });
            } catch (RuntimeException e) {
                LiveRerunTest.finish(helper);
                throw e;
            }
        })), 0);
    }

    private static void moveAbout(GameTestHelper helper, ServerLevel level, Watched watch, ItemEntity feather, Vec3 base, LiveRerunTest.Capture chat) {
        // wait one tick: the footprint was replaced, the next scan is a fresh baseline
        TestSupport.later(helper, 2, () -> {
            try {
                int changes = watch.changes();
                int reruns = watch.reruns();
                helper.assertTrue(watch.footprint().touchesEntity(feather.getBoundingBox()), "the new footprint does not hold the feather");
                shake(helper, level, watch, feather, base, 0, 30, changes, reruns, chat);
            } catch (RuntimeException e) {
                LiveRerunTest.finish(helper);
                throw e;
            }
        });
    }

    private static void shake(GameTestHelper helper, ServerLevel level, Watched watch, ItemEntity feather, Vec3 base, int i, int n, int changes, int reruns,
                              LiveRerunTest.Capture chat) {
        try {
            if (i < n) {
                feather.setPos(base.x + (i % 2 == 0 ? 0.15 : -0.15), base.y, base.z);
                TestSupport.later(helper, 1, () -> shake(helper, level, watch, feather, base, i + 1, n, changes, reruns, chat));
                return;
            }
            helper.assertTrue(watch.changes() == changes && !watch.dirty() && watch.reruns() == reruns && watch.entitiesSettled() == 0,
                "ordinary movement counted: changes " + changes + " -> " + watch.changes() + ", settled " + watch.entitiesSettled());
            // now leave it half a block east: it settles at a new place
            feather.setPos(base.x + 0.5, base.y, base.z);
            int expectReruns = reruns + 1;
            LiveRerunTest.waitFor(helper, "the settle", 400, () -> {}, () -> watch.entitiesSettled() >= 1, () ->
                LiveRerunTest.waitFor(helper, "the rerun after the settle", 60000, () -> {}, () -> watch.reruns() >= expectReruns, () -> {
                    try {
                        // let the new baseline be taken, then remove it
                        TestSupport.later(helper, 2, () -> {
                            try {
                                int removedBefore = watch.entitiesRemoved();
                                feather.discard();
                                TestSupport.later(helper, 2, () -> {
                                    try {
                                        helper.assertTrue(watch.entitiesRemoved() == removedBefore + 1 && watch.dirty(), "the removal was not seen");
                                        LiveRerunTest.waitFor(helper, "the rerun after the removal", 60000, () -> {}, () -> watch.reruns() >= expectReruns + 1, () -> {
                                            LOG.info("[ghostpath] entity watch: added {}, removed {}, settled {}; chat: {}", watch.entitiesAdded(), watch.entitiesRemoved(),
                                                watch.entitiesSettled(), chat.lines.get(chat.lines.size() - 1));
                                            LiveRerunTest.finish(helper);
                                            helper.succeed();
                                        });
                                    } catch (RuntimeException e) {
                                        LiveRerunTest.finish(helper);
                                        throw e;
                                    }
                                });
                            } catch (RuntimeException e) {
                                LiveRerunTest.finish(helper);
                                throw e;
                            }
                        });
                    } catch (RuntimeException e) {
                        LiveRerunTest.finish(helper);
                        throw e;
                    }
                }));
        } catch (RuntimeException e) {
            LiveRerunTest.finish(helper);
            throw e;
        }
    }
}
