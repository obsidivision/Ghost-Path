package obsidivision.ghostpath;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The mod's config file {@code config/ghostpath.json} (session D): the settings that outlive a session, {@code animation}
 * ("on", "off", "fast") and {@code style} ("default", "soft", "contrast"). Everything else in {@link PathSettings} is per session. A missing or
 * broken file leaves the defaults (and is overwritten by the next change). Colours, widths and timings are in {@code Style}, not here.
 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Where the file is; a test may point it elsewhere. */
    public static volatile Path file;

    private Config() {
    }

    private static Path file() {
        Path f = file;
        return f != null ? f : FabricLoader.getInstance().getConfigDir().resolve("ghostpath.json");
    }

    public static void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            JsonObject o = GSON.fromJson(Files.readString(f), JsonObject.class);
            if (o == null) {
                return;
            }
            if (o.has("animation") && PathSettings.validAnimation(o.get("animation").getAsString())) {
                PathSettings.animation = o.get("animation").getAsString();
            }
            if (o.has("style") && PathSettings.validStyle(o.get("style").getAsString())) {
                PathSettings.style = o.get("style").getAsString();
            }
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            GhostPath.LOGGER.warn("[ghostpath] config {} not read, defaults kept: {}", f, e.toString());
        }
    }

    public static void save() {
        Path f = file();
        JsonObject o = new JsonObject();
        o.addProperty("animation", PathSettings.animation);
        o.addProperty("style", PathSettings.style);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o));
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            GhostPath.LOGGER.warn("[ghostpath] config {} not written: {}", f, e.toString());
        }
    }
}
