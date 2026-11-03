package obsidivision.ghostpath;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GhostPath implements ModInitializer {
    public static final String MOD_ID = "ghostpath";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Ghost Path loaded");
    }
}
