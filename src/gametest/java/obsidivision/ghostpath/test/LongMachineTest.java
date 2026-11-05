package obsidivision.ghostpath.test;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.test.LiveRerunTest.Capture;
import obsidivision.ghostpath.watch.LiveWatch;
import obsidivision.ghostpath.watch.Prediction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Long machines: the ghost copy spread over server ticks; player-owned ender pearls. */
public class LongMachineTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    private static final long SEED = 0x6A05_7FA7_0000_0001L;

    /**
     * The chunks' sections, heightmaps and light are copied one chunk per server tick when the budget is 0 (the run's budget; at least
     * one chunk per tick), the rest in one go. The prediction is the same as with a copy in one tick, and the chat line gives the copy
     * time and the ticks it took.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:long_copy")
    public void theCopyIsSpreadOverServerTicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Capture chat = new Capture();
        RerunTriggersTest.recordM(helper, level, chat, (rec, watch) -> RerunTriggersTest.guard(helper, () -> {
            PathSettings.rerunBudgetMs = 0;
            LiveRerunTest.command(level, chat, "path rerun");
            LiveRerunTest.waitFor(helper, "the spread rerun", 60000, () -> RerunTriggersTest.notFired(helper, level, 0), () -> watch.reruns() >= 1, () -> RerunTriggersTest.guard(helper, () -> {
                Prediction spread = watch.latest();
                String line = chat.lines.stream().filter(l -> l.startsWith("rerun:")).reduce((a, b) -> b).orElse("(none)");
                LOG.info("[ghostpath] long copy (budget 0): {} chunks copied in {} ms over {} server ticks; line: {}", spread.copiedMax().x() - spread.copiedMin().x() + 1, spread.copyMillis(), spread.copyTicks(), line);
                int chunks = (spread.copiedMax().x() - spread.copiedMin().x() + 1) * (spread.copiedMax().z() - spread.copiedMin().z() + 1);
                helper.assertTrue(spread.copyTicks() >= chunks, "the copy took " + spread.copyTicks() + " ticks for " + chunks + " chunks");
                helper.assertTrue(line.contains("copied in ") && line.contains(" ms over " + spread.copyTicks() + " ticks)"), "chat line: " + line);
                PathSettings.rerunBudgetMs = 100_000;
                LiveRerunTest.command(level, chat, "path rerun");
                LiveRerunTest.waitFor(helper, "the one-tick rerun", 60000, () -> RerunTriggersTest.notFired(helper, level, 0), () -> watch.reruns() >= 2, () -> RerunTriggersTest.guard(helper, () -> {
                    Prediction once = watch.latest();
                    LOG.info("[ghostpath] long copy (budget 100 s): copied in {} ms over {} server ticks", once.copyMillis(), once.copyTicks());
                    helper.assertTrue(once.copyTicks() == 1, "the one-go copy took " + once.copyTicks() + " ticks");
                    helper.assertTrue(once.events().equals(spread.events()) && once.tntSpawnTicks().equals(spread.tntSpawnTicks()) && once.trace().equals(spread.trace()),
                        "the prediction of the spread copy differs from the one-go copy");
                    RerunTriggersTest.end(helper);
                    PathSettings.rerunBudgetMs = 10;
                    helper.succeed();
                }));
            }));
        }));
    }

    /**
     * A chain ender pearl owned by a player is noted (the ghost has no player, so the ticket {@code ThrownEnderpearl.tick} would place
     * for it is not simulated); one without an owner is not. The recording's summary prints the note.
     */
    @GameTest(maxTicks = 600, padding = 8, environment = "ghostpath-gametest:long_pearl")
    @SuppressWarnings("deprecation")
    public void aPlayerOwnedPearlIsNotedAsNotSimulated(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 3, 2));
        CauseTracker tracker = CauseTracker.begin(level, SEED);
        try {
            tracker.runAsChain(() -> {
                ThrownEnderpearl unowned = new ThrownEnderpearl(net.minecraft.world.entity.EntityTypes.ENDER_PEARL, level);
                unowned.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                level.addFreshEntity(unowned);
            });
            helper.assertTrue(tracker.notSimulated().isEmpty(), "a pearl without an owner was noted: " + tracker.notSimulated());
            tracker.runAsChain(() -> {
                ThrownEnderpearl owned = new ThrownEnderpearl(level, player, new ItemStack(Items.ENDER_PEARL));
                owned.setPos(at.getX() + 1.5, at.getY(), at.getZ() + 0.5);
                level.addFreshEntity(owned);
            });
            helper.assertTrue(tracker.notSimulated().equals(List.of(CauseTracker.PLAYER_PEARL)), "notes: " + tracker.notSimulated());
        } finally {
            tracker.end();
            level.getEntitiesOfClass(ThrownEnderpearl.class, helper.getBounds().inflate(8)).forEach(net.minecraft.world.entity.Entity::discard);
        }
        // the summary says it (a recording made from such a tracker)
        Recording any = new Recording(level.dimension(), at, SEED, true, 0L, 1, 10, false, new CauseTracker.Pending(0, 0, 0, 0), List.of(), java.util.Set.of(), java.util.Map.of(), java.util.Set.of(), java.util.Set.of(),
            0, 0, 0, 0, java.util.Set.of(), 0, obsidivision.ghostpath.record.Weather.of(level), obsidivision.ghostpath.record.Weather.of(level), 0, 0, 0, 0, 0, 0, List.of(),
            level.clockManager().packState(), List.of(), java.util.Map.of(), level.getBlockState(at), List.of(), List.of(), List.of(), tracker.notSimulated(),
            obsidivision.ghostpath.record.LoadingLevels.capture(level, at.getX() >> 4, at.getZ() >> 4), 0);
        helper.assertTrue(any.summary().contains("Not simulated: player-owned pearl: chunk loading not simulated"), "summary: " + any.summary());
        helper.succeed();
    }
}
