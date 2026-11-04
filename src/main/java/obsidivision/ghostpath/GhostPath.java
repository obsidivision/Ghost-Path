package obsidivision.ghostpath;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import obsidivision.ghostpath.command.PathCommand;
import obsidivision.ghostpath.record.MachineRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GhostPath implements ModInitializer {
    public static final String MOD_ID = "ghostpath";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PathCommand.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(MachineRecorder::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(MachineRecorder::abort);
        LOGGER.info("Ghost Path loaded");
    }
}
