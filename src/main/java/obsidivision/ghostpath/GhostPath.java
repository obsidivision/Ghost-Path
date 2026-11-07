package obsidivision.ghostpath;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.TicketType;
import obsidivision.ghostpath.command.PathCommand;
import obsidivision.ghostpath.record.MachineRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GhostPath implements ModInitializer {
    public static final String MOD_ID = "ghostpath";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /**
     * Ticket that loads a chunk for the undo-log restore (chunks that unloaded during a recording). Loading only
     * ({@code FLAG_LOADING}, TicketType.java:13), no simulation, so the half-run machine does not tick while it waits;
     * not persistent, no timeout. Registered like vanilla's (TicketType.java:26-28).
     */
    public static final TicketType RESTORE_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
        Identifier.fromNamespaceAndPath(MOD_ID, "restore"), new TicketType(0L, TicketType.FLAG_LOADING));
    /**
     * Ghost copies of the real chunk ticket levels (ruling R2: copied, not forced): one loading and one simulation ticket
     * per copied chunk, at the real chunk's loading and simulation level (TicketStorage.java:158-203 keeps the two apart
     * by these flags). No timeout, not persistent; the ghost's ticket storage is throwaway anyway.
     */
    public static final TicketType GHOST_LOADING_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
        Identifier.fromNamespaceAndPath(MOD_ID, "ghost_loading"), new TicketType(0L, TicketType.FLAG_LOADING));
    public static final TicketType GHOST_SIMULATION_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
        Identifier.fromNamespaceAndPath(MOD_ID, "ghost_simulation"), new TicketType(0L, TicketType.FLAG_SIMULATION));

    @Override
    public void onInitialize() {
        Config.load();
        obsidivision.ghostpath.network.ServerSnapshots.init();
        ServerTickEvents.END_SERVER_TICK.register(obsidivision.ghostpath.network.ServerSnapshots::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> obsidivision.ghostpath.network.ServerSnapshots.clear());
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> obsidivision.ghostpath.network.ServerSnapshots.queue(handler.getPlayer()));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PathCommand.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(MachineRecorder::tick);
        ServerTickEvents.END_SERVER_TICK.register(obsidivision.ghostpath.watch.LiveWatch::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(obsidivision.ghostpath.watch.LiveWatch::shutdown);
        ServerLifecycleEvents.SERVER_STOPPING.register(MachineRecorder::abort);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> obsidivision.ghostpath.saved.SavedStore.close());
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> PathCommand.warnSavedFiles(server, handler.getPlayer()));
        LOGGER.info("Ghost Path loaded");
    }
}
