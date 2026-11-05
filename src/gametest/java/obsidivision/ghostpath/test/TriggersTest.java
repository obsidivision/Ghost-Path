package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.ghost.GhostCheck;
import obsidivision.ghostpath.record.ChainEvent;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.test.LiveRerunTest.Capture;
import obsidivision.ghostpath.watch.LiveWatch;
import obsidivision.ghostpath.watch.Prediction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Right-click triggers other than lever and button: note block, door, trapdoor, fence gate, repeater, comparator ({@code record.Triggers}). */
public class TriggersTest {
    private static final Logger LOG = LoggerFactory.getLogger("ghostpath-test");
    private static final BlockPos NOTE = new BlockPos(-1, 2, 1);
    private static final BlockPos OBSERVER = new BlockPos(0, 2, 1);

    private static void recordOnly(GameTestHelper helper, ServerLevel level, BlockPos abs, BiConsumer<Recording, GhostCheck.Result> then) {
        PathSettings.kick = true;
        PathSettings.tickLimit = PathSettings.DEFAULT_TICK_LIMIT;
        Recording[] out = new Recording[1];
        String[] failed = new String[1];
        TestSupport.whenSavesFree(helper, () -> {
            try {
                MachineRecorder.start(level, abs, PathSettings.DEFAULT_TICK_LIMIT, true, new MachineRecorder.Listener() {
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
            LiveRerunTest.waitFor(helper, "the recording", 6000, () -> { }, () -> MachineRecorder.current() == null, () -> {
                helper.assertTrue(out[0] != null, "no recording: " + failed[0]);
                then.accept(out[0], GhostCheck.run(level, out[0]));
            });
        });
    }

    /**
     * A note block next to an observer that powers the 1b machine's first repeater. {@code /path machine} on the note block tunes it
     * (note 0 to 1, as a click does), the observer fires, the machine runs, and the ghost check is exact. Then the real note block is
     * tuned to another note without updates (a footprint change): the rerun is for the recorded press (0 to 1), and the chat line
     * says what the real block is.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:trigger_note")
    public void anObserverWatchingANoteBlockFiresTheMachine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CauseTrackingTest.buildMachine(helper, BlockPos.ZERO, false);
        for (int x = -3; x <= -1; x++) {
            for (int z = 0; z <= 2; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        helper.setBlock(NOTE, Blocks.NOTE_BLOCK);
        helper.setBlock(OBSERVER, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));
        Capture chat = new Capture();
        CauseTrackingTest.whenEntityTicking(helper, () -> LiveRerunTest.recordAndWatch(helper, level, chat, NOTE, (rec, watch) -> RerunTriggersTest.guard(helper, () -> {
            LOG.info("[ghostpath] note trigger chat: {}", chat.lines);
            helper.assertTrue(chat.has("Ghost check: exact"), "the ghost check was not exact: " + chat.lines);
            helper.assertTrue(rec.tntSpawned() == 1, "TNT spawned " + rec.tntSpawned());
            BlockPos abs = helper.absolutePos(NOTE);
            ChainEvent first = rec.events().stream().filter(e -> e.kind() == ChainEvent.Kind.WRITE && abs.equals(e.pos())).findFirst().orElse(null);
            helper.assertTrue(first != null && first.detail().contains("note=0") && first.detail().contains("-> ") && first.detail().substring(first.detail().indexOf("-> ")).contains("note=1"),
                "the first write at the note block: " + first);
            helper.assertTrue(level.getBlockState(abs).getValue(NoteBlock.NOTE) == 0, "the note block was not restored");
            helper.assertTrue(rec.triggerStateAtStart().getValue(NoteBlock.NOTE) == 0, "recorded start state " + rec.triggerStateAtStart());
            // the real note block is tuned to 12 without updates (the real observer must not see it): a footprint change
            int reruns = watch.reruns();
            level.setBlock(abs, level.getBlockState(abs).setValue(NoteBlock.NOTE, 12), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            helper.assertTrue(watch.dirty(), "the tuned note block was not seen as a change");
            LiveRerunTest.waitFor(helper, "the rerun", 60000, () -> helper.assertTrue(level.getBlockState(helper.absolutePos(new BlockPos(10, 2, 1))).is(Blocks.TNT), "the real machine fired"),
                () -> watch.reruns() > reruns, () -> RerunTriggersTest.guard(helper, () -> {
                    Prediction p = watch.latest();
                    String line = chat.lines.stream().filter(l -> l.startsWith("rerun:")).reduce((a, b) -> b).orElse("(none)");
                    LOG.info("[ghostpath] note trigger rerun line: {}", line);
                    helper.assertTrue(line.contains("real note block is note 12; prediction is for note 0 → note 1"), "chat line: " + line);
                    helper.assertTrue(p.events().stream().anyMatch(e -> e.kind() == ChainEvent.Kind.WRITE && abs.equals(e.pos()) && e.detail().contains("note=0") && e.detail().substring(e.detail().indexOf("-> ")).contains("note=1")),
                        "the rerun did not press the recorded note 0 -> 1");
                    helper.assertTrue(p.tntSpawned() == 1, "the rerun lit " + p.tntSpawned() + " TNT");
                    helper.assertTrue(level.getBlockState(abs).getValue(NoteBlock.NOTE) == 12, "the real note block changed");
                    CauseTrackingTest.forceChunks(helper, false);
                    RerunTriggersTest.end(helper);
                    helper.succeed();
                }));
        })));
    }

    /** One kind of trigger: where to put it, what its first write must say. */
    private record Kind(String name, BlockState state, boolean door, String before, String after) {
    }

    /**
     * Door, trapdoor, fence gate, repeater, comparator, each on its own spot, recorded one after the other: the first write at the
     * trigger is the click's change, the ghost check is exact, the world is as before, the recorded start state is the placed one.
     * Also: an iron door and a grass block are refused, the grass block with the list of supported blocks.
     */
    @GameTest(maxTicks = TestSupport.ASYNC_MAX_TICKS, padding = 24, environment = "ghostpath-gametest:trigger_kinds")
    public void eachOtherTriggerIsPressedLikeAClick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestSupport.removeBarriers(helper);
        CauseTrackingTest.forceChunks(helper, true);
        for (int x = -1; x <= 22; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        List<Kind> kinds = List.of(
            new Kind("door", Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), true, "open=false", "open=true"),
            new Kind("trapdoor", Blocks.OAK_TRAPDOOR.defaultBlockState(), false, "open=false", "open=true"),
            new Kind("fence gate", Blocks.OAK_FENCE_GATE.defaultBlockState(), false, "open=false", "open=true"),
            new Kind("repeater", Blocks.REPEATER.defaultBlockState(), false, "delay=1", "delay=2"),
            new Kind("comparator", Blocks.COMPARATOR.defaultBlockState(), false, "mode=compare", "mode=subtract"));
        List<BlockPos> spots = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            BlockPos rel = new BlockPos(1 + 4 * i, 2, 2);
            spots.add(rel);
            BlockState s = kinds.get(i).state();
            level.setBlock(helper.absolutePos(rel), s, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            if (kinds.get(i).door()) {
                level.setBlock(helper.absolutePos(rel.above()), s.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
        helper.setBlock(new BlockPos(19, 2, 2), Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(new BlockPos(21, 2, 2), Blocks.GRASS_BLOCK);
        PathSettings.watchAfterMachine = false;
        LiveRerunTest.whenTicking(helper, List.of(new BlockPos(0, 2, 0), new BlockPos(22, 2, 4), new BlockPos(11, 2, 2)), TestSupport.once(() -> {
            try {
                for (BlockPos rel : List.of(new BlockPos(19, 2, 2), new BlockPos(21, 2, 2))) {
                    try {
                        MachineRecorder.start(level, helper.absolutePos(rel), 100, true, new MachineRecorder.Listener() {
                            @Override
                            public void done(Recording recording) {
                            }

                            @Override
                            public void failed(String message) {
                            }
                        });
                        helper.fail("accepted: " + level.getBlockState(helper.absolutePos(rel)));
                    } catch (MachineRecorder.RejectedException e) {
                        LOG.info("[ghostpath] rejected: {}", e.getMessage());
                        boolean iron = rel.getX() == 19;
                        helper.assertTrue(iron ? e.getMessage().contains("does not open by hand") : e.getMessage().contains("not a supported trigger") && e.getMessage().contains("note block")
                            && e.getMessage().contains("comparator"), "message: " + e.getMessage());
                    }
                }
            } catch (RuntimeException e) {
                CauseTrackingTest.forceChunks(helper, false);
                throw e;
            }
            step(helper, level, kinds, spots, 0);
        }), 0);
    }

    private static void step(GameTestHelper helper, ServerLevel level, List<Kind> kinds, List<BlockPos> spots, int i) {
        if (i == kinds.size()) {
            CauseTrackingTest.forceChunks(helper, false);
            helper.succeed();
            return;
        }
        Kind k = kinds.get(i);
        BlockPos abs = helper.absolutePos(spots.get(i));
        BlockState start = level.getBlockState(abs);
        recordOnly(helper, level, abs, (rec, r) -> {
            try {
                LOG.info("[ghostpath] trigger {}: {} events, {} unseeded warnings ({} allowed); ghost check:\n{}", k.name(), rec.events().size(), rec.unseededDrawsNotAllowed(),
                    rec.unseededDrawsAllowed(), String.join("\n", r.lines()));
                ChainEvent first = rec.events().stream().filter(e -> e.kind() == ChainEvent.Kind.WRITE && abs.equals(e.pos())).findFirst().orElse(null);
                helper.assertTrue(first != null, k.name() + ": no write at the trigger");
                int arrow = first.detail().indexOf("-> ");
                helper.assertTrue(first.detail().substring(0, arrow).contains(k.before()) && first.detail().substring(arrow).contains(k.after()),
                    k.name() + ": the first write: " + first);
                helper.assertTrue(rec.triggerStateAtStart().equals(start), k.name() + ": start state " + rec.triggerStateAtStart() + " vs " + start);
                helper.assertTrue(level.getBlockState(abs).equals(start), k.name() + ": not restored: " + level.getBlockState(abs));
                if (k.door()) {
                    helper.assertTrue(level.getBlockState(abs.above()).getValue(DoorBlock.OPEN) == Boolean.FALSE, "upper half not restored");
                }
                helper.assertTrue(r.ran() && r.exact(), k.name() + ": ghost check not exact: " + String.join("\n", r.lines()));
                helper.assertTrue(rec.unseededDrawsNotAllowed() == 0, k.name() + ": unseeded draws not allowed: " + rec.unseededDrawsNotAllowed());
            } catch (RuntimeException e) {
                CauseTrackingTest.forceChunks(helper, false);
                throw e;
            }
            step(helper, level, kinds, spots, i + 1);
        });
    }
}
