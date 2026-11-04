package obsidivision.ghostpath.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;

/**
 * {@code /path machine <x y z>}, {@code /path kick on|off}, {@code /path limit <ticks>}. Allowed for the owner of a
 * singleplayer world and for permission level 2 (game master) and up.
 */
public final class PathCommand {
    private PathCommand() {
    }

    /** Singleplayer owner (also without cheats), or operator level 2. */
    public static boolean allowed(CommandSourceStack source) {
        if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("path")
            .requires(PathCommand::allowed)
            .then(Commands.literal("machine")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> machine(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos")))))
            .then(Commands.literal("kick")
                .then(Commands.literal("on").executes(ctx -> kick(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> kick(ctx.getSource(), false))))
            .then(Commands.literal("limit")
                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 1_000_000))
                    .executes(ctx -> limit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ticks"))))));
    }

    private static int kick(CommandSourceStack source, boolean on) {
        PathSettings.kick = on;
        source.sendSuccess(() -> Component.literal("TNT kick " + (on ? "on" : "off")
            + (on ? "" : ": drawn and discarded, horizontal part 0 (the upward 0.2 stays)")), false);
        return 1;
    }

    private static int limit(CommandSourceStack source, int ticks) {
        PathSettings.tickLimit = ticks;
        source.sendSuccess(() -> Component.literal("Recording tick limit: " + ticks), false);
        return ticks;
    }

    private static int machine(CommandSourceStack source, BlockPos pos) throws CommandSyntaxException {
        try {
            MachineRecorder.start(source.getLevel(), pos, PathSettings.tickLimit, PathSettings.kick, new MachineRecorder.Listener() {
                @Override
                public void done(Recording recording) {
                    for (String line : recording.summary()) {
                        source.sendSuccess(() -> Component.literal(line), false);
                    }
                }

                @Override
                public void failed(String message) {
                    source.sendFailure(Component.literal("Recording failed: " + message));
                }
            });
        } catch (MachineRecorder.RejectedException e) {
            throw new SimpleCommandExceptionType(Component.literal(e.getMessage())).create();
        }
        source.sendSuccess(() -> Component.literal("Recording " + pos.toShortString() + " (tick limit " + PathSettings.tickLimit
            + ", kick " + (PathSettings.kick ? "on" : "off") + ") ..."), false);
        return 1;
    }
}
