package obsidivision.ghostpath.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import obsidivision.ghostpath.PathSettings;

/**
 * Client side of the flight preview (build step 5). Singleplayer only: the latest prediction is read straight from the
 * built-in server ({@code PathStore}, no packets). Registers the toggle key (default G, "Ghost Path" in the controls), draws
 * the paths just before the frame's gizmos are collected, and runs the continuations of projectiles that were still flying
 * when the ghost run stopped at its tick limit.
 */
public class GhostPathClient implements ClientModInitializer {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("ghostpath", "main"));
    public static final KeyMapping TOGGLE = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ghostpath.toggle", InputConstants.KEY_G, CATEGORY));
    /** Hold: the inspector's ray goes through blocks; with it, a click pins or unpins the hovered path point and the scroll wheel steps through a stack. */
    public static final KeyMapping INSPECT = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ghostpath.inspect", InputConstants.KEY_V, CATEGORY));

    @Override
    public void onInitializeClient() {
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            PathRenderer.render();
            Inspector.render();
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath("ghostpath", "inspector"), (graphics, deltaTracker) -> Inspector.hud(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE.consumeClick()) {
                PathSettings.showPaths = !PathSettings.showPaths;
                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.literal("Flight paths " + (PathSettings.showPaths ? "shown" : "hidden")));
                }
            }
            Tails.tick(Minecraft.getInstance());
            Inspector.tick(Minecraft.getInstance());
        });
    }
}
