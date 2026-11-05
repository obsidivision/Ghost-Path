package obsidivision.ghostpath.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import obsidivision.ghostpath.style.Style;

/**
 * The short messages above the hotbar (session D, item 5): the same pill style as the labels, fading in and out ({@link Style#toastAlpha}).
 * One at a time; a new one replaces the shown one. Real-time clock. Client thread.
 */
final class Toasts {
    private static String text = "";
    private static long shownAt;
    /** The last text shown (client test). */
    static volatile String last = "";

    private Toasts() {
    }

    static void show(String message) {
        text = message;
        last = message;
        shownAt = System.nanoTime();
    }

    static void hud(GuiGraphicsExtractor g) {
        if (text.isEmpty()) {
            return;
        }
        Style st = Style.current();
        double a = st.toastAlpha((System.nanoTime() - shownAt) / 1.0E9);
        if (a <= 0.0) {
            if ((System.nanoTime() - shownAt) / 1.0E9 > st.toastSeconds()) {
                text = "";
            }
            return;
        }
        var font = Minecraft.getInstance().font;
        int pad = st.labelPad() + 2;
        int w = font.width(text) + 2 * pad + 4;
        int h = font.lineHeight + 2 * pad;
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() - 59 - h - 4; // above the hotbar and the item name
        g.fill(x, y, x + w, y + h, Style.withAlpha(st.toastBackground(), a));
        g.fill(x, y, x + 2, y + h, Style.withAlpha(st.panelBorder(), a));
        g.text(font, text, x + 4 + pad, y + pad, Style.withAlpha(st.panelHeader(), a), false);
    }
}
