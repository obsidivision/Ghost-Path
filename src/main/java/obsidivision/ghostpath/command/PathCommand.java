package obsidivision.ghostpath.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import obsidivision.ghostpath.saved.SavedPath;
import obsidivision.ghostpath.saved.SavedStore;
import obsidivision.ghostpath.preview.PathStore;
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
 * {@code /path machine <x y z>}, {@code /path kick on|off}, {@code /path limit <ticks>}, {@code /path show on|off}, {@code /path beyond-loaded on|off}, {@code /path interval <ticks> [offset <n>]}, {@code /path inspect hold|toggle}, {@code /path pins clear}, {@code /path watch list|stop [x y z]}, {@code /path watch margin <n>}, {@code /path rerun [x y z]}, {@code /path area on|off}, {@code /path area watched|chunks on|off}. Allowed for the owner of a
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
            .then(Commands.literal("save")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> save(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))
                    .then(Commands.literal("replace").executes(ctx -> save(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true)))))
            .then(Commands.literal("saved")
                .then(Commands.literal("list").executes(ctx -> savedList(ctx.getSource(), false))
                    .then(Commands.literal("archived").executes(ctx -> savedList(ctx.getSource(), true))))
                .then(Commands.literal("show").then(namedAction("show", (src, n) -> store(src).setShown(n, true))))
                .then(Commands.literal("hide").then(namedAction("hide", (src, n) -> store(src).setShown(n, false))))
                .then(Commands.literal("archive").then(namedAction("archive", (src, n) -> store(src).archive(n))))
                .then(Commands.literal("unarchive").then(namedAction("unarchive", (src, n) -> store(src).unarchive(n))))
                .then(Commands.literal("delete")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> report(ctx.getSource(), store(ctx.getSource()).delete(StringArgumentType.getString(ctx, "name"), false)))
                        .then(Commands.literal("confirm").executes(ctx -> report(ctx.getSource(), store(ctx.getSource()).delete(StringArgumentType.getString(ctx, "name"), true)))))))
            .then(Commands.literal("kick")
                .then(Commands.literal("on").executes(ctx -> kick(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> kick(ctx.getSource(), false))))
            .then(Commands.literal("rerun")
                .executes(ctx -> rerun(ctx.getSource(), null))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> rerun(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos")))))
            .then(Commands.literal("watch")
                .then(Commands.literal("list").executes(ctx -> watchList(ctx.getSource())))
                .then(Commands.literal("margin")
                    .then(Commands.argument("n", IntegerArgumentType.integer(0, obsidivision.ghostpath.watch.Footprint.MAX_MARGIN))
                        .executes(ctx -> watchMargin(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("stop")
                    .executes(ctx -> watchStop(ctx.getSource(), null))
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> watchStop(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"))))))
            .then(Commands.literal("beyond-loaded")
                .then(Commands.literal("on").executes(ctx -> beyondLoaded(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> beyondLoaded(ctx.getSource(), false))))
            .then(Commands.literal("area")
                .then(Commands.literal("on").executes(ctx -> area(ctx.getSource(), "area", true)))
                .then(Commands.literal("off").executes(ctx -> area(ctx.getSource(), "area", false)))
                .then(Commands.literal("watched")
                    .then(Commands.literal("on").executes(ctx -> area(ctx.getSource(), "watched", true)))
                    .then(Commands.literal("off").executes(ctx -> area(ctx.getSource(), "watched", false))))
                .then(Commands.literal("chunks")
                    .then(Commands.literal("on").executes(ctx -> area(ctx.getSource(), "chunks", true)))
                    .then(Commands.literal("off").executes(ctx -> area(ctx.getSource(), "chunks", false)))))
            .then(Commands.literal("show")
                .then(Commands.literal("on").executes(ctx -> show(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> show(ctx.getSource(), false))))
            .then(Commands.literal("inspect")
                .then(Commands.literal("hold").executes(ctx -> inspectMode(ctx.getSource(), false)))
                .then(Commands.literal("toggle").executes(ctx -> inspectMode(ctx.getSource(), true))))
            .then(Commands.literal("pins")
                .then(Commands.literal("clear").executes(ctx -> pinsClear(ctx.getSource()))))
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

    private static SavedStore store(CommandSourceStack source) {
        return SavedStore.of(source.getServer());
    }

    private static int report(CommandSourceStack source, SavedStore.Result r) {
        if (r.ok()) {
            source.sendSuccess(() -> Component.literal(r.message()), false);
        } else {
            source.sendFailure(Component.literal(r.message()));
        }
        return r.ok() ? 1 : 0;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> namedAction(String what, java.util.function.BiFunction<CommandSourceStack, String, SavedStore.Result> action) {
        return Commands.argument("name", StringArgumentType.word()).executes(ctx -> report(ctx.getSource(), action.apply(ctx.getSource(), StringArgumentType.getString(ctx, "name"))));
    }

    /** {@code /path save <name> [replace]}: the latest prediction under a name (a snapshot; it never reruns). */
    private static int save(CommandSourceStack source, String name, boolean replace) {
        obsidivision.ghostpath.preview.PathSnapshot s = PathStore.latest();
        return report(source, store(source).save(name, replace, s, s == null ? java.util.Map.of() : PathStore.continuations(s)));
    }

    private static int savedList(CommandSourceStack source, boolean archived) {
        SavedStore st = store(source);
        java.util.List<SavedPath> list = st.list(archived);
        if (list.isEmpty()) {
            source.sendSuccess(() -> Component.literal(archived ? "No archived paths." : "No saved paths."), false);
        }
        for (SavedPath p : list) {
            source.sendSuccess(() -> Component.literal(p.name() + ": " + p.dimension() + " " + p.triggerText() + ", " + p.tnt() + " TNT, " + formatDate(p.date())
                + (archived ? ", archived" : p.shown() ? ", shown" : ", hidden")), false);
        }
        warnSavedFiles(source.getServer(), source.getPlayer());
        return list.size();
    }

    /** Date of a save for lists and panels: local time, minutes. */
    public static String formatDate(String iso) {
        try {
            return java.time.LocalDateTime.ofInstant(java.time.Instant.parse(iso), java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (RuntimeException e) {
            return iso;
        }
    }

    /** Chat warnings for saved-path files that were skipped (once per world join, and in {@code /path saved list}). */
    public static void warnSavedFiles(net.minecraft.server.MinecraftServer server, ServerPlayer player) {
        if (player == null) {
            return;
        }
        for (String w : SavedStore.of(server).warnings()) {
            player.sendSystemMessage(Component.literal("Ghost Path: saved path " + w));
        }
    }

    private static int kick(CommandSourceStack source, boolean on) {
        PathSettings.kick = on;
        source.sendSuccess(() -> Component.literal("TNT kick " + (on ? "on" : "off")
            + (on ? "" : ": drawn and discarded, horizontal part 0 (the upward 0.2 stays)")), false);
        return 1;
    }

    private static int beyondLoaded(CommandSourceStack source, boolean on) {
        PathSettings.beyondLoaded = on;
        source.sendSuccess(() -> Component.literal("Beyond the loaded area: " + (on ? "on (a rerun takes chunks that are not loaded from the save and draws past them, dashed where the real world would freeze)"
            : "off (a rerun uses loaded chunks only)")), false);
        return 1;
    }

    /** {@code /path area on|off}, {@code /path area watched|chunks on|off}: the ghost area overlay and its two optional layers. */
    private static int area(CommandSourceStack source, String layer, boolean on) {
        switch (layer) {
            case "watched" -> PathSettings.areaWatched = on;
            case "chunks" -> PathSettings.areaChunks = on;
            default -> PathSettings.showArea = on;
        }
        String what = switch (layer) {
            case "watched" -> "watched area layer (margin " + PathSettings.watchMargin + " around the blocks the machine wrote)";
            case "chunks" -> "copied chunks layer (widened chunks in a second colour)";
            default -> "ghost area (the machine's footprint: written blocks stronger than read ones, the block change that started the last rerun)";
        };
        source.sendSuccess(() -> Component.literal(what.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + what.substring(1) + (on ? " shown" : " hidden")
            + (on && !PathSettings.showArea && !layer.equals("area") ? " (switch /path area on to see it)" : "")), false);
        return 1;
    }

    private static int show(CommandSourceStack source, boolean on) {
        PathSettings.showPaths = on;
        source.sendSuccess(() -> Component.literal("Flight paths " + (on ? "shown" : "hidden")), false);
        return 1;
    }

    private static int inspectMode(CommandSourceStack source, boolean toggle) {
        PathSettings.inspectToggle = toggle;
        source.sendSuccess(() -> Component.literal("Inspect key: " + (toggle ? "press to switch on or off" : "hold")), false);
        return 1;
    }

    private static int pinsClear(CommandSourceStack source) {
        PathSettings.pinsClearSerial++;
        source.sendSuccess(() -> Component.literal("Pins cleared."), false);
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

    private static int watchMargin(CommandSourceStack source, int n) {
        PathSettings.watchMargin = n;
        source.sendSuccess(() -> Component.literal("Watch margin: " + n + (n == 0 ? " (off: only the footprint and the neighbours of blocks the machine wrote count)"
            : " blocks around every block the machine wrote (plus the footprint and the neighbours of written blocks)")), false);
        return n;
    }

    private static int rerun(CommandSourceStack source, BlockPos pos) {
        int n = LiveWatch.rerun(pos);
        source.sendSuccess(() -> Component.literal(n == 0 ? (LiveWatch.watches().isEmpty() ? "No machine is watched." : "No such watch, or it is running now.")
            : "Rerun queued for " + n + " watch" + (n == 1 ? "" : "es") + " (starts when the real machine is idle and one ghost is free)."), false);
        return n;
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
