package obsidivision.ghostpath.test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.ghost.GhostCheck;
import obsidivision.ghostpath.record.CauseTracker;
import obsidivision.ghostpath.record.ChainEvent;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loot rolls during chain work are seeded: a TNT in a bed of gravel (flint chance) drops the same in the recording and in the ghost. */
public class LootDropsTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    private static final BlockPos TNT = new BlockPos(4, 2, 4);
    private static final BlockPos STONE = new BlockPos(4, 2, 5);
    private static final BlockPos LEVER = new BlockPos(4, 3, 5);
    private static final Identifier GRAVEL = Identifier.withDefaultNamespace("blocks/gravel");

    /** The saved state of the world's gravel loot sequence, as text (other tables' entries are created on first use, which is harmless). */
    private static String sequencesState(ServerLevel level) {
        net.minecraft.nbt.CompoundTag root = (net.minecraft.nbt.CompoundTag) net.minecraft.world.RandomSequences.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, level.getServer().getRandomSequences()).getOrThrow();
        return String.valueOf(root.getCompoundOrEmpty("sequences").get(GRAVEL.toString()));
    }

    /** Items of the chain by index, from the entity trace: how many distinct item entities carry {@code item}. */
    private static int items(Recording rec, String item) {
        Set<Integer> idx = new HashSet<>();
        rec.entityTrace().stream().filter(t -> t.type().equals("minecraft:item") && t.state().contains(item)).forEach(t -> idx.add(t.index()));
        return idx.size();
    }

    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:loot_gravel")
    public void gravelDropsAreTheSameInTheRealRunAndTheGhost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        for (int x = -4; x <= 12; x++) {
            for (int z = -4; z <= 12; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        // a 9x9 layer of gravel on the floor around the TNT; gravel on stone does not fall
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.GRAVEL);
            }
        }
        helper.setBlock(TNT, Blocks.TNT);
        helper.setBlock(STONE, Blocks.STONE);
        helper.setBlock(LEVER, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        PathSettings.kick = true;
        BlockPos lever = helper.absolutePos(LEVER);
        level.getServer().getRandomSequence(GRAVEL); // make sure the world's sequence exists, so that "untouched" can be compared
        String before = sequencesState(level);
        helper.assertTrue(!before.equals("null"), "the gravel sequence does not exist");
        CauseTrackingTest.whenEntityTicking(helper, () -> TestSupport.whenSavesFree(helper, () -> {
            Recording[] out = new Recording[1];
            try {
                MachineRecorder.start(level, lever, PathSettings.DEFAULT_TICK_LIMIT, true, new MachineRecorder.Listener() {
                    @Override
                    public void done(Recording recording) {
                        out[0] = recording;
                    }

                    @Override
                    public void failed(String message) {
                    }
                });
            } catch (MachineRecorder.RejectedException e) {
                helper.fail("recording rejected: " + e.getMessage());
                return;
            }
            LiveRerunTest.waitFor(helper, "the recording", 6000, () -> { }, () -> MachineRecorder.current() == null, () -> {
                try {
                    Recording rec = out[0];
                    helper.assertTrue(rec != null, "no recording");
                    int flint = items(rec, "minecraft:flint");
                    int gravel = items(rec, "minecraft:gravel");
                    LOG.info("[ghostpath] loot: {} events, {} gravel items, {} flint items, unseeded warnings {} (+{} allowed)", rec.events().size(), gravel, flint, rec.unseededDrawsNotAllowed(), rec.unseededDrawsAllowed());
                    helper.assertTrue(gravel >= 1 && flint >= 1, "the explosion did not drop both gravel and flint: " + gravel + "/" + flint);
                    helper.assertTrue(rec.unseededDrawsNotAllowed() == 0, "unseeded draws: " + rec.unseededDrawsNotAllowed());
                    helper.assertTrue(rec.events().stream().noneMatch(e -> e.kind() == ChainEvent.Kind.UNSEEDED_DRAW && e.detail().contains("WARNING")), "an unseeded draw warning");
                    GhostCheck.Result r = GhostCheck.run(level, rec);
                    LOG.info("[ghostpath] loot ghost check:\n{}", String.join("\n", r.lines()));
                    helper.assertTrue(r.ran() && r.exact(), "the ghost's drops differ from the real run's:\n" + String.join("\n", r.lines()));
                    helper.assertTrue(items(rec, "minecraft:flint") == flint, "recording changed");
                    // the world's own loot sequence was not advanced by the recording or by the ghost
                    helper.assertTrue(sequencesState(level).equals(before), "the world's gravel sequence changed during the recording and the ghost run: " + before + " -> " + sequencesState(level));
                } finally {
                    CauseTrackingTest.forceChunks(helper, false);
                }
                helper.succeed();
            });
        }));
    }
}
