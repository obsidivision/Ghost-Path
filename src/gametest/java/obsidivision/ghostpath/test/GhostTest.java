package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.ghost.GhostCopy;
import obsidivision.ghostpath.ghost.GhostLevel;
import obsidivision.ghostpath.mixin.GhostServerLevelAccessor;
import obsidivision.ghostpath.record.SaveGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Build step 2: the ghost level and the copy into it. */
public class GhostTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    /** Repeater delays along the chain (x = 1..10), signal going east. */
    private static final int[] DELAYS = {1, 3, 2, 4, 1, 2, 3, 1, 4, 2};
    private static final BlockPos LAMP = new BlockPos(11, 2, 1);
    private static final BlockPos LIGHT_PROBE = new BlockPos(11, 3, 1); // above the lamp: its block light

    /**
     * The key test for step 2 (DESIGN "Build order"): a repeater chain copied mid-pulse fires on the same tick in the
     * ghost and in the real world. A redstone block is placed at the west end for 2 ticks; 7 ticks later, with the pulse
     * inside the chain (repeaters powered, ticks pending), the area is copied. Then the real world and the ghost are
     * ticked side by side (the ghost once after each real tick) for 60 ticks: the game time, every block state and the
     * pending tick count in the chain must match on every tick, and the lamp at the end must light and go out on the
     * same game tick in both. Light above the lamp is compared too and logged (DESIGN step 2, light).
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:ghost_repeater")
    public void repeaterChainCopiedMidPulseFiresOnSameTick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSupport.removeBarriers(helper);
        for (int x = -1; x <= 13; x++) {
            for (int z = 0; z <= 2; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        for (int i = 0; i < DELAYS.length; i++) {
            helper.setBlock(new BlockPos(1 + i, 2, 1), Blocks.REPEATER.defaultBlockState()
                .setValue(RepeaterBlock.FACING, Direction.WEST).setValue(RepeaterBlock.DELAY, DELAYS[i]));
        }
        helper.setBlock(LAMP, Blocks.REDSTONE_LAMP);
        ChunkRect rect = ChunkRect.around(helper, new BlockPos(-1, 0, 0), new BlockPos(13, 0, 2));
        rect.force(level, true);
        whenLoaded(helper, rect, 0, () -> {
            helper.setBlock(new BlockPos(0, 2, 1), Blocks.REDSTONE_BLOCK);
            helper.runAfterDelay(2, () -> helper.setBlock(new BlockPos(0, 2, 1), Blocks.AIR));
            helper.runAfterDelay(9, () -> {
                GhostLevel ghost = newGhost(helper, level);
                GhostCopy.Report report = GhostCopy.copy(level, ghost, rect.copyMin(), rect.copyMax());
                helper.assertTrue(report.blockTicks() > 0, "no pending ticks at the copy: not mid-pulse, test proves nothing");
                helper.assertTrue(ghost.getGameTime() == level.getGameTime(), "ghost game time " + ghost.getGameTime() + " != real " + level.getGameTime());
                helper.assertTrue(!level.getServer().levelKeys().contains(ghost.dimension()), "ghost registered with the server");
                helper.assertTrue(SaveGuard.active() != null, "autosave not held while the ghost exists");
                int lit = 0;
                for (int i = 1; i <= DELAYS.length; i++) {
                    lit += level.getBlockState(helper.absolutePos(new BlockPos(i, 2, 1))).getValue(RepeaterBlock.POWERED) ? 1 : 0;
                }
                LOG.info("[ghostpath] ghost repeater test: copied with {} pending block ticks, {} repeaters powered", report.blockTicks(), lit);
                int[] lightDiff = {0};
                int[] lampOn = {-1, -1};
                int[] lampOff = {-1, -1};
                BlockPos lamp = helper.absolutePos(LAMP);
                BlockPos probe = helper.absolutePos(LIGHT_PROBE);
                checkLightCopy(helper, level, ghost, rect);
                sideBySide(helper, 0, 60, () -> {
                    ghost.tickGhost();
                    long t = level.getGameTime();
                    helper.assertTrue(ghost.getGameTime() == t, "game time differs: ghost " + ghost.getGameTime() + ", real " + t);
                    for (int x = -1; x <= 13; x++) {
                        for (int y = 1; y <= 3; y++) {
                            BlockPos p = helper.absolutePos(new BlockPos(x, y, 1));
                            BlockState r = level.getBlockState(p);
                            BlockState g = ghost.getBlockState(p);
                            if (r != g) {
                                ghost.discard();
                                helper.fail("block differs at t=" + t + " " + p.toShortString() + ": real " + r + ", ghost " + g);
                            }
                        }
                    }
                    int rt = level.getBlockTicks().count();
                    int gt = ghost.getBlockTicks().count();
                    long rtArea = countTicks(level, rect);
                    if (rtArea != gt) {
                        ghost.discard();
                        helper.fail("pending ticks differ at t=" + t + ": real " + rtArea + " (level " + rt + "), ghost " + gt);
                    }
                    boolean rOn = level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT);
                    boolean gOn = ghost.getBlockState(lamp).getValue(RedstoneLampBlock.LIT);
                    int tick = (int) (t - report.gameTime());
                    if (rOn && lampOn[0] < 0) lampOn[0] = tick;
                    if (gOn && lampOn[1] < 0) lampOn[1] = tick;
                    if (!rOn && lampOn[0] >= 0 && lampOff[0] < 0) lampOff[0] = tick;
                    if (!gOn && lampOn[1] >= 0 && lampOff[1] < 0) lampOff[1] = tick;
                    if (level.getBrightness(LightLayer.BLOCK, probe) != ghost.getBrightness(LightLayer.BLOCK, probe)) {
                        lightDiff[0]++;
                    }
                }, () -> {
                    ghost.discard();
                    rect.force(level, false);
                    LOG.info("[ghostpath] ghost repeater test: lamp on at +{} real / +{} ghost, off at +{} real / +{} ghost; block light above the lamp differed on {} of 60 ticks",
                        lampOn[0], lampOn[1], lampOff[0], lampOff[1], lightDiff[0]);
                    helper.assertTrue(lampOn[0] > 0 && lampOff[0] > lampOn[0], "the pulse did not reach the lamp after the copy: on " + lampOn[0] + ", off " + lampOff[0]);
                    helper.assertTrue(lampOn[0] == lampOn[1] && lampOff[0] == lampOff[1],
                        "lamp fired on different ticks: real on +" + lampOn[0] + " off +" + lampOff[0] + ", ghost on +" + lampOn[1] + " off +" + lampOff[1]);
                    helper.assertTrue(SaveGuard.active() == null, "autosave still held after the ghost was discarded");
                    helper.succeed();
                });
            });
        });
    }

    /**
     * Entities, weather and ids: a primed TNT in flight (long fuse), an arrow in flight and an item dropped onto the
     * floor are copied a few ticks after they were spawned, in rain. The ghost has the same ids, UUIDs, positions,
     * velocities (exact doubles) and tick-list order; the weather timers and rain level match. Then both are ticked
     * side by side for 40 ticks and every entity's position and velocity must stay bit-identical.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:ghost_entities")
    public void entitiesAndWeatherCopiedExactly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSupport.removeBarriers(helper);
        for (int x = -1; x <= 13; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        ChunkRect rect = ChunkRect.around(helper, new BlockPos(-1, 0, 0), new BlockPos(13, 0, 6));
        rect.force(level, true);
        // weather is server-wide: remember it and put it back exactly (other tests read it)
        net.minecraft.world.level.saveddata.WeatherData w = level.getWeatherData();
        int[] weatherBefore = {w.getClearWeatherTime(), w.getRainTime(), w.getThunderTime(), w.isRaining() ? 1 : 0, w.isThundering() ? 1 : 0};
        obsidivision.ghostpath.mixin.LevelWeatherAccessor lw = (obsidivision.ghostpath.mixin.LevelWeatherAccessor) level;
        float[] levelsBefore = {lw.ghostpath$oRainLevel(), lw.ghostpath$rainLevel(), lw.ghostpath$oThunderLevel(), lw.ghostpath$thunderLevel()};
        Runnable restoreWeather = () -> {
            w.setClearWeatherTime(weatherBefore[0]);
            w.setRainTime(weatherBefore[1]);
            w.setThunderTime(weatherBefore[2]);
            w.setRaining(weatherBefore[3] == 1);
            w.setThundering(weatherBefore[4] == 1);
            lw.ghostpath$setORainLevel(levelsBefore[0]);
            lw.ghostpath$setRainLevel(levelsBefore[1]);
            lw.ghostpath$setOThunderLevel(levelsBefore[2]);
            lw.ghostpath$setThunderLevel(levelsBefore[3]);
        };
        w.setClearWeatherTime(0);
        w.setRaining(true);
        w.setRainTime(50000);
        lw.ghostpath$setORainLevel(1.0F);
        lw.ghostpath$setRainLevel(1.0F);
        whenLoaded(helper, rect, 0, () -> {
            List<Entity> spawned = new ArrayList<>();
            Vec3 tntPos = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 3, 3)));
            PrimedTnt tnt = new PrimedTnt(level, tntPos.x, tntPos.y, tntPos.z, null);
            tnt.setFuse(200);
            tnt.setDeltaMovement(0.31, 0.42, 0.07);
            level.addFreshEntity(tnt);
            spawned.add(tnt);
            Vec3 arrowPos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 4, 5)));
            Arrow arrow = new Arrow(net.minecraft.world.entity.EntityTypes.ARROW, level);
            arrow.setPos(arrowPos);
            arrow.setDeltaMovement(0.9, 0.25, -0.05);
            level.addFreshEntity(arrow);
            spawned.add(arrow);
            Vec3 itemPos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(8, 3, 2)));
            ItemEntity item = new ItemEntity(level, itemPos.x, itemPos.y, itemPos.z, new ItemStack(Items.STICK));
            level.addFreshEntity(item);
            spawned.add(item);
            helper.runAfterDelay(5, () -> {
                GhostLevel ghost = newGhost(helper, level);
                GhostCopy.Report report = GhostCopy.copy(level, ghost, rect.copyMin(), rect.copyMax());
                helper.assertTrue(report.entities() >= 3 && report.randomsNotCopied() == 0, "entities not copied: " + report.lines());
                for (Entity r : spawned) {
                    Entity g = ghost.getEntity(r.getUUID());
                    if (g == null || g.getId() != r.getId()) {
                        ghost.discard();
                        restoreWeather.run();
                        helper.fail("entity " + r + " missing in the ghost or id differs: " + g);
                        return;
                    }
                }
                List<java.util.UUID> realOrder = new ArrayList<>();
                List<java.util.UUID> ghostOrder = new ArrayList<>();
                ((GhostServerLevelAccessor) level).ghostpath$entityTickList().forEach(e -> {
                    if (spawned.contains(e)) realOrder.add(e.getUUID());
                });
                ((GhostServerLevelAccessor) (Object) ghost).ghostpath$entityTickList().forEach(e -> {
                    if (realOrder.contains(e.getUUID())) ghostOrder.add(e.getUUID());
                });
                helper.assertTrue(realOrder.equals(ghostOrder) && realOrder.size() == 3, "tick order differs: " + realOrder + " vs " + ghostOrder);
                helper.assertTrue(ghost.isRaining() && ghost.getRainLevel(1.0F) == level.getRainLevel(1.0F)
                    && ghost.getWeatherData().getRainTime() == level.getWeatherData().getRainTime(), "weather not copied");
                helper.assertTrue(ghost.getWeatherData() != level.getWeatherData(), "ghost shares the server's weather data");
                int rain = level.getWeatherData().getRainTime();
                sideBySide(helper, 0, 40, () -> {
                    ghost.tickGhost();
                    for (Entity r : spawned) {
                        Entity g = ghost.getEntity(r.getUUID());
                        if (g == null || !r.position().equals(g.position()) || !r.getDeltaMovement().equals(g.getDeltaMovement()) || r.isRemoved() != g.isRemoved()) {
                            String msg = "entity differs at t=" + level.getGameTime() + ": real " + r + " v=" + r.getDeltaMovement() + ", ghost " + g
                                + (g == null ? "" : " v=" + g.getDeltaMovement());
                            ghost.discard();
                            restoreWeather.run();
                            helper.fail(msg);
                        }
                    }
                }, () -> {
                    helper.assertTrue(level.getWeatherData().getRainTime() == rain - 40, "real rain timer not advanced by the server: " + level.getWeatherData().getRainTime());
                    helper.assertTrue(ghost.getWeatherData().getRainTime() == rain - 40, "ghost rain timer not advanced by its ticks: " + ghost.getWeatherData().getRainTime());
                    for (Entity r : spawned) {
                        // the own random continues the same sequence (state copied at the copy, same draws since)
                        long rr = r.getRandom().nextLong();
                        long gr = ghost.getEntity(r.getUUID()).getRandom().nextLong();
                        if (rr != gr) {
                            ghost.discard();
                            restoreWeather.run();
                            helper.fail("own random differs for " + r + ": " + rr + " vs " + gr);
                        }
                    }
                    LOG.info("[ghostpath] ghost entity test: {} entities identical for 40 ticks; tnt at {}", spawned.size(), tnt.position());
                    ghost.discard();
                    spawned.forEach(Entity::discard);
                    restoreWeather.run();
                    rect.force(level, false);
                    helper.succeed();
                });
            });
        });
    }

    // ---- helpers ----

    /**
     * Found on the real client (step 6 session): a live rerun waits for its ghost chunks without blocking, one {@code poll()} per
     * server tick, and at the real 20 ticks a second the loader's chain of main-thread tasks (one task per poll then) took longer
     * than the 20 s wait limit: "rerun failed: timed out waiting for the ghost chunks". The game tests tick as fast as they can, so
     * they never saw it. A poll now runs the ghost's pending tasks for a few milliseconds: the copy of a 3x3 chunk area must be
     * ready within a few dozen polls (ticks; about 42 here). With one task per poll it was not ready after 3000 (break-it checked).
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:ghost_poll")
    public void ghostChunksAreReadyWithinAFewPolls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkRect rect = ChunkRect.around(helper, new BlockPos(0, 0, 0), new BlockPos(4, 0, 4));
        rect.force(level, true);
        whenLoaded(helper, rect, 0, () -> TestSupport.whenSavesFree(helper, () -> {
            GhostLevel ghost = newGhost(helper, level);
            GhostCopy.Prepared prepared;
            try {
                prepared = GhostCopy.prepare(level, ghost, rect.copyMin(), rect.copyMax(), GhostCopy.Start.NOW);
            } catch (RuntimeException e) {
                ghost.discard();
                throw e;
            }
            pollEachTick(helper, ghost, prepared, new int[] {0}, rect);
        }));
    }

    private static void pollEachTick(GameTestHelper helper, GhostLevel ghost, GhostCopy.Prepared prepared, int[] polls, ChunkRect rect) {
        polls[0]++;
        boolean ready;
        try {
            ready = prepared.poll();
        } catch (RuntimeException e) {
            ghost.discard();
            rect.force(helper.getLevel(), false);
            throw e;
        }
        if (ready) {
            ghost.discard();
            rect.force(helper.getLevel(), false);
            LOG.info("[ghostpath] ghost chunks ready after {} polls (one per tick)", polls[0]);
            helper.assertTrue(polls[0] <= 150, "the ghost chunks needed " + polls[0] + " polls (one per server tick); at 20 ticks a second the wait limit (20 s) is 400, and the old one-task-per-poll code did not finish in 3000");
            helper.succeed();
        } else if (TestSupport.overRealTime(helper)) {
            ghost.discard();
            rect.force(helper.getLevel(), false);
            helper.fail("the ghost chunks were not ready after " + polls[0] + " polls");
        } else {
            helper.runAfterDelay(1, () -> pollEachTick(helper, ghost, prepared, polls, rect));
        }
    }

    private static GhostLevel newGhost(GameTestHelper helper, ServerLevel level) {
        try {
            return GhostLevel.create(level);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares every light value in the copied chunks between y = test floor - 8 and + 24 right after the copy (logged). */
    private static void checkLightCopy(GameTestHelper helper, ServerLevel level, GhostLevel ghost, ChunkRect rect) {
        int y0 = helper.absolutePos(BlockPos.ZERO).getY();
        int[] diff = new int[2];
        int n = 0;
        String first = null;
        for (int cx = rect.copyMin().x(); cx <= rect.copyMax().x(); cx++) {
            for (int cz = rect.copyMin().z(); cz <= rect.copyMax().z(); cz++) {
                for (BlockPos p : BlockPos.betweenClosed(cx * 16, y0 - 8, cz * 16, cx * 16 + 15, y0 + 24, cz * 16 + 15)) {
                    n++;
                    for (LightLayer layer : LightLayer.values()) {
                        if (level.getBrightness(layer, p) != ghost.getBrightness(layer, p)) {
                            diff[layer.ordinal()]++;
                            if (first == null) {
                                first = layer + " at " + p.toShortString() + ": real " + level.getBrightness(layer, p) + ", ghost " + ghost.getBrightness(layer, p);
                            }
                        }
                    }
                }
            }
        }
        LOG.info("[ghostpath] ghost light after copy: {} positions, sky differs at {}, block differs at {}{}", n, diff[LightLayer.SKY.ordinal()],
            diff[LightLayer.BLOCK.ordinal()], first == null ? "" : "; first: " + first);
    }

    private static long countTicks(ServerLevel level, ChunkRect rect) {
        long n = 0;
        for (int cx = rect.copyMin().x(); cx <= rect.copyMax().x(); cx++) {
            for (int cz = rect.copyMin().z(); cz <= rect.copyMax().z(); cz++) {
                n += ((obsidivision.ghostpath.mixin.LevelTicksAccessor<?>) level.getBlockTicks()).ghostpath$allContainers().get(ChunkPos.pack(cx, cz)).count();
            }
        }
        return n;
    }

    /** Runs {@code each} once per real tick (after the levels ticked) for {@code ticks} ticks, then {@code done}. */
    private static void sideBySide(GameTestHelper helper, int i, int ticks, Runnable each, Runnable done) {
        helper.runAfterDelay(1, () -> {
            each.run();
            if (i + 1 < ticks) {
                sideBySide(helper, i + 1, ticks, each, done);
            } else {
                done.run();
            }
        });
    }

    private static void whenLoaded(GameTestHelper helper, ChunkRect rect, int waited, Runnable then) {
        if (rect.loaded(helper.getLevel())) {
            LOG.info("[ghostpath] ghost test area loaded after {} ticks", waited);
            then.run();
        } else {
            helper.runAfterDelay(1, () -> whenLoaded(helper, rect, waited + 1, then));
        }
    }

    /** The chunks under the test area (forced in the test), and the copy rectangle: one more ring (real level 32). */
    private record ChunkRect(ChunkPos min, ChunkPos max) {
        static ChunkRect around(GameTestHelper helper, BlockPos a, BlockPos b) {
            BlockPos pa = helper.absolutePos(a);
            BlockPos pb = helper.absolutePos(b);
            return new ChunkRect(new ChunkPos(Math.min(pa.getX(), pb.getX()) >> 4, Math.min(pa.getZ(), pb.getZ()) >> 4),
                new ChunkPos(Math.max(pa.getX(), pb.getX()) >> 4, Math.max(pa.getZ(), pb.getZ()) >> 4));
        }

        ChunkPos copyMin() {
            return new ChunkPos(this.min.x() - 1, this.min.z() - 1);
        }

        ChunkPos copyMax() {
            return new ChunkPos(this.max.x() + 1, this.max.z() + 1);
        }

        void force(ServerLevel level, boolean on) {
            this.each(c -> level.setChunkForced(c.x(), c.z(), on), false);
        }

        boolean loaded(ServerLevel level) {
            boolean[] ok = {true};
            this.each(c -> ok[0] &= level.getChunkSource().getChunkNow(c.x(), c.z()) != null && level.areEntitiesLoaded(c.pack()), true);
            this.each(c -> ok[0] &= level.areEntitiesActuallyLoadedAndTicking(c), false);
            return ok[0];
        }

        private void each(Consumer<ChunkPos> f, boolean ring) {
            ChunkPos lo = ring ? this.copyMin() : this.min;
            ChunkPos hi = ring ? this.copyMax() : this.max;
            for (int x = lo.x(); x <= hi.x(); x++) {
                for (int z = lo.z(); z <= hi.z(); z++) {
                    f.accept(new ChunkPos(x, z));
                }
            }
        }
    }
}
