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

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PathCommand.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(MachineRecorder::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(MachineRecorder::abort);
        LOGGER.info("Ghost Path loaded");
    }
}
