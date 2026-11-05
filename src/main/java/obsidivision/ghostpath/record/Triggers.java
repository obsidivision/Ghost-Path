package obsidivision.ghostpath.record;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import obsidivision.ghostpath.mixin.ComparatorBlockInvoker;
import obsidivision.ghostpath.mixin.NoteBlockInvoker;
import obsidivision.ghostpath.mixin.TrapDoorBlockAccessor;
import org.jspecify.annotations.Nullable;

/**
 * What {@code /path machine} can press, and what a press does (26.2 sources, a click without a player and without a held item):
 * <ul>
 *   <li>lever {@code LeverBlock.pull}, button {@code ButtonBlock.press} (since step 1d);</li>
 *   <li>note block {@code NoteBlock.useWithoutItem} (:124-133): {@code cycle(NOTE)}, {@code setBlock(.., 3)}, {@code playNote(null, ..)};</li>
 *   <li>trapdoor {@code TrapDoorBlock.useWithoutItem} (:92-99) = {@code toggle} (:112): {@code cycle(OPEN)}, {@code setBlock(.., 2)}, a water tick if waterlogged, the sound; only if the block set type opens by hand (not iron);</li>
 *   <li>door {@code DoorBlock.useWithoutItem} (:206-216): {@code cycle(OPEN)}, {@code setBlock(.., 10)}, sound, game event; = {@code setOpen(null, ..)} (:222); only if it opens by hand (not iron);</li>
 *   <li>fence gate {@code FenceGateBlock.useWithoutItem} (:143-163): toggles OPEN with {@code setBlock(.., 10)}, game event (the sound is left out, see the code); the facing flip of a player standing in front of a closed gate (:148-151, {@code player.getDirection()}) is not simulated (no player);</li>
 *   <li>repeater {@code RepeaterBlock.useWithoutItem} (:44-51): {@code setBlock(pos, cycle(DELAY), 3)};</li>
 *   <li>comparator {@code ComparatorBlock.useWithoutItem} (:130-144): {@code cycle(MODE)}, the click sound, {@code setBlock(.., 2)}, {@code refreshOutputState}.</li>
 * </ul>
 * The checks of the player ({@code getAbilities().mayBuild}) and the stats are left out. Used by the recording, the ghost check and
 * the live rerun, so all three press the same way.
 */
public final class Triggers {
    /** For the error message. */
    public static final String SUPPORTED = "lever, button, note block, trapdoor, door and fence gate (not iron), repeater, comparator";

    private Triggers() {
    }

    /** Null if {@code state} can be pressed now, else the reason (for the player). */
    public static @Nullable String reject(BlockPos pos, BlockState state) {
        Block b = state.getBlock();
        String name = b.getName().getString();
        if (b instanceof ButtonBlock) {
            return state.getValue(ButtonBlock.POWERED) ? "the button at " + pos.toShortString() + " is pressed already; try again when it has popped back" : null;
        }
        if (b instanceof DoorBlock door && !door.type().canOpenByHand()) {
            return "the " + name + " at " + pos.toShortString() + " does not open by hand";
        }
        if (b instanceof TrapDoorBlock t && !((TrapDoorBlockAccessor) t).ghostpath$type().canOpenByHand()) {
            return "the " + name + " at " + pos.toShortString() + " does not open by hand";
        }
        if (b instanceof LeverBlock || b instanceof NoteBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
            || b instanceof RepeaterBlock || b instanceof ComparatorBlock) {
            return null;
        }
        return "the block at " + pos.toShortString() + " is " + name + ", not a supported trigger (supported: " + SUPPORTED + ")";
    }

    /** Does what a player's right click does. Chain work: the caller wraps it in {@code runAsChain}. */
    public static void press(ServerLevel level, BlockPos pos, BlockState state) {
        Block b = state.getBlock();
        if (b instanceof LeverBlock lever) {
            lever.pull(state, level, pos, null);
        } else if (b instanceof ButtonBlock button) {
            button.press(state, level, pos, null);
        } else if (b instanceof NoteBlock note) {
            BlockState tuned = state.cycle(NoteBlock.NOTE);
            level.setBlock(pos, tuned, Block.UPDATE_ALL);
            ((NoteBlockInvoker) note).ghostpath$playNote(null, tuned, level, pos);
        } else if (b instanceof TrapDoorBlock trapdoor) {
            ((TrapDoorBlockAccessor) trapdoor).ghostpath$toggle(state, level, pos, null);
        } else if (b instanceof DoorBlock door) {
            door.setOpen(null, level, state, pos, !state.getValue(DoorBlock.OPEN));
        } else if (b instanceof FenceGateBlock) {
            BlockState toggled = state.setValue(FenceGateBlock.OPEN, !state.getValue(FenceGateBlock.OPEN));
            level.setBlock(pos, toggled, Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
            boolean opens = toggled.getValue(FenceGateBlock.OPEN);
            // the click sound (FenceGateBlock.java:158-159, pitch from the level random) is left out: it changes nothing a machine can see,
            // and the draw would be attributed to whoever called press (the allowlist is keyed by the first caller outside this mod)
            level.gameEvent(null, opens ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
        } else if (b instanceof RepeaterBlock) {
            level.setBlock(pos, state.cycle(RepeaterBlock.DELAY), Block.UPDATE_ALL);
        } else if (b instanceof ComparatorBlock comparator) {
            BlockState toggled = state.cycle(ComparatorBlock.MODE);
            float pitch = toggled.getValue(ComparatorBlock.MODE) == net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT ? 0.55F : 0.5F;
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 0.3F, pitch);
            level.setBlock(pos, toggled, Block.UPDATE_CLIENTS);
            if (level.getBlockState(pos).is(comparator)) {
                ((ComparatorBlockInvoker) comparator).ghostpath$refreshOutputState(level, pos, toggled);
            }
        } else {
            throw new IllegalArgumentException("not a supported trigger: " + state);
        }
    }

    /** The state of a trigger as a few words: "on", "pressed", "note 5", "open", "delay 3", "compare". */
    public static String stateText(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof LeverBlock) {
            return s.getValue(LeverBlock.POWERED) ? "on" : "off";
        }
        if (b instanceof ButtonBlock) {
            return s.getValue(ButtonBlock.POWERED) ? "pressed" : "not pressed";
        }
        if (b instanceof NoteBlock) {
            return "note " + s.getValue(NoteBlock.NOTE);
        }
        if (b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock) {
            return s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN) ? "open" : "closed";
        }
        if (b instanceof RepeaterBlock) {
            return "delay " + s.getValue(RepeaterBlock.DELAY);
        }
        if (b instanceof ComparatorBlock) {
            return s.getValue(ComparatorBlock.MODE).getSerializedName();
        }
        return s.toString();
    }

    /** What a press does to a trigger that is in state {@code before}: "off → on", "note 5 → 6", "delay 4 → 1". */
    public static String pressText(BlockState before) {
        Block b = before.getBlock();
        BlockState after;
        if (b instanceof ButtonBlock) {
            return "not pressed → pressed";
        } else if (b instanceof LeverBlock) {
            after = before.cycle(LeverBlock.POWERED);
        } else if (b instanceof NoteBlock) {
            after = before.cycle(NoteBlock.NOTE);
        } else if (b instanceof RepeaterBlock) {
            after = before.cycle(RepeaterBlock.DELAY);
        } else if (b instanceof ComparatorBlock) {
            after = before.cycle(ComparatorBlock.MODE);
        } else {
            after = before.cycle(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
        }
        return stateText(before) + " → " + stateText(after);
    }
}
