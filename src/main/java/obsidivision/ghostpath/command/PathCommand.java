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
import obsidivision.ghostpath.ghost.GhostCheck;
import obsidivision.ghostpath.record.MachineRecorder;
import obsidivision.ghostpath.record.Recording;
import obsidivision.ghostpath.watch.LiveWatch;
import obsidivision.ghostpath.watch.Watched;

/**
 * {@code /path machine <x y z>}, {@code /path kick on|off}, {@code /path limit <ticks>}, {@code /path show on|off}, {@code /path interval <ticks> [offset <n>]}, {@code /path watch list|stop [x y z]}. Allowed for the owner of a
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
            .then(Commands.literal("watch")
                .then(Commands.literal("list").executes(ctx -> watchList(ctx.getSource())))
                .then(Commands.literal("stop")
                    .executes(ctx -> watchStop(ctx.getSource(), null))
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> watchStop(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"))))))
            .then(Commands.literal("show")
                .then(Commands.literal("on").executes(ctx -> show(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> show(ctx.getSource(), false))))
            .then(Commands.literal("interval")
                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 10_000))
                    .executes(ctx -> interval(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ticks"), 0))
                    .then(Commands.literal("offset")
                        .then(Commands.argument("offset", IntegerArgumentType.integer(0, 10_000))
                            .executes(ctx -> interval(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ticks"), IntegerArgumentType.getInteger(ctx, "offset")))))))
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

    private static int show(CommandSourceStack source, boolean on) {
        PathSettings.showPaths = on;
        source.sendSuccess(() -> Component.literal("Flight paths " + (on ? "shown" : "hidden")), false);
        return 1;
    }

    private static int interval(CommandSourceStack source, int ticks, int offset) {
        PathSettings.dotInterval = ticks;
        PathSettings.dotOffset = offset;
        source.sendSuccess(() -> Component.literal("Flight path dots: every " + ticks + " ticks, offset " + offset + " (ticks since the recording began; start and end always)"), false);
        return ticks;
    }

    private static int limit(CommandSourceStack source, int ticks) {
        PathSettings.tickLimit = ticks;
        source.sendSuccess(() -> Component.literal("Recording tick limit: " + ticks), false);
        return ticks;
    }

    private static int watchList(CommandSourceStack source) {
        for (String line : LiveWatch.list()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return LiveWatch.watches().size();
    }

    /** {@code /path watch stop [x y z]}: stops every watch, or the one with that trigger. */
    private static int watchStop(CommandSourceStack source, BlockPos pos) {
        int n = 0;
        for (Watched w : LiveWatch.watches()) {
            if (pos == null || w.trigger().equals(pos)) {
                LiveWatch.stop(w);
                n++;
            }
        }
        int stopped = n;
        source.sendSuccess(() -> Component.literal(stopped == 0 ? "No such watch." : "Stopped " + stopped + " watch" + (stopped == 1 ? "" : "es") + "."), false);
        return stopped;
    }

    private static int machine(CommandSourceStack source, BlockPos pos) throws CommandSyntaxException {
        try {
            MachineRecorder.start(source.getLevel(), pos, PathSettings.tickLimit, PathSettings.kick, new MachineRecorder.Listener() {
                @Override
                public void done(Recording recording) {
                    for (String line : recording.summary()) {
                        source.sendSuccess(() -> Component.literal(line), false);
                    }
                    // step 3: the same machine in a ghost, compared with what was just recorded
                    for (String line : GhostCheck.run(source.getLevel(), recording).lines()) {
                        source.sendSuccess(() -> Component.literal(line), false);
                    }
                    // step 4: the machine is watched; a real change at its footprint reruns it in a fresh ghost
                    if (recording.limitHit()) {
                        source.sendSuccess(() -> Component.literal("Not watched: the recording stopped at the tick limit."), false);
                    } else if (PathSettings.watchAfterMachine) {
                        LiveWatch.watch(recording, line -> source.sendSuccess(() -> Component.literal(line), false));
                        source.sendSuccess(() -> Component.literal("Watching " + pos.toShortString() + ": a block change at its footprint reruns it"
                            + " in a ghost after " + LiveWatch.QUIET_TICKS + " quiet ticks (/path watch list, /path watch stop)."), false);
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
