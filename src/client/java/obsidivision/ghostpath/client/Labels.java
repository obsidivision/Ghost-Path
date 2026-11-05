package obsidivision.ghostpath.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.style.LabelLayout;
import obsidivision.ghostpath.style.Style;
import org.joml.Vector3fc;

/**
 * The labels of the overlay (session D, item 5), drawn in the HUD pass at the screen position of their world point: a dark translucent
 * pill with the text and a small coloured marker (the end reason, or the path's colour). Labels that overlap on screen: the nearest stays,
 * the others fade out ({@link LabelLayout}, pure); beyond a distance they fade out; the text grows with distance (1x to 2x, {@link Style#labelScale}).
 * Collected during the level pass ({@link PathRenderer}, {@link #add}), drawn by {@link #hud}. Client thread only.
 */
final class Labels {
    private record Req(Vec3 pos, String text, int color, int marker, int weight) {
    }

    private static final List<Req> REQ = new ArrayList<>();
    /** Alpha of each label as it fades towards its target (keyed by text and rounded position), so declutter does not flicker. */
    private static final Map<String, Float> ALPHA = new HashMap<>();
    private static long lastNanos;
    /** Texts of the labels requested in the last frame (client test). */
    static volatile List<String> lastTexts = List.of();

    private Labels() {
    }

    static void begin() {
        lastTexts = merged().stream().map(Req::text).toList();
        REQ.clear();
    }

    static void add(String text, Vec3 pos, int color, int marker) {
        REQ.add(new Req(pos, text, color, marker, 0));
    }

    /** An end label standing for {@code weight} entities. */
    static void add(String text, Vec3 pos, int color, int marker, int weight) {
        REQ.add(new Req(pos, text, color, marker, weight));
    }

    /**
     * Labels with weight and the same text within 0.15 blocks of each other become one, "exploded t+81 ×60": a batch of TNT that ends at one place
     * has one label, not sixty on top of each other.
     */
    private static List<Req> merged() {
        Map<String, Req> by = new java.util.LinkedHashMap<>();
        List<Req> out = new ArrayList<>();
        for (Req r : REQ) {
            if (r.weight == 0) {
                out.add(r);
                continue;
            }
            String k = r.text + "@" + Math.round(r.pos.x * 6) + "," + Math.round(r.pos.y * 6) + "," + Math.round(r.pos.z * 6);
            Req old = by.get(k);
            Req now = old == null ? r : new Req(old.pos, old.text, old.color, old.marker, old.weight + r.weight);
            by.put(k, now);
        }
        for (Req r : by.values()) {
            out.add(r.weight > 1 ? new Req(r.pos, r.text + " ×" + r.weight, r.color, r.marker, r.weight) : r);
        }
        return out;
    }

    private static String key(Req r) {
        return r.text + "@" + Math.round(r.pos.x * 4) + "," + Math.round(r.pos.y * 4) + "," + Math.round(r.pos.z * 4);
    }

    static void hud(GuiGraphicsExtractor g) {
        if (REQ.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Style st = Style.current();
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        Vector3fc fwd = mc.gameRenderer.mainCamera().forwardVector();
        List<Req> reqs = new ArrayList<>();
        List<LabelLayout.Item> items = new ArrayList<>();
        List<double[]> info = new ArrayList<>(); // distance, scale, x, y, w, h
        for (Req r : merged()) {
            Vec3 d = r.pos.subtract(cam);
            double dist = d.length();
            if (dist > 0.0 && d.x * fwd.x() + d.y * fwd.y() + d.z * fwd.z() <= 0.0 || st.labelDistanceAlpha(dist) <= 0.0) {
                continue; // behind the camera, or too far
            }
            Vec3 ndc = mc.gameRenderer.projectPointToScreen(r.pos);
            if (Math.abs(ndc.x) > 1.3 || Math.abs(ndc.y) > 1.3) {
                continue;
            }
            double sx = (ndc.x * 0.5 + 0.5) * g.guiWidth();
            double sy = (0.5 - ndc.y * 0.5) * g.guiHeight();
            float scale = st.labelScale(dist);
            double w = (font.width(r.text) + 2 * st.labelPad() + 4) * scale;
            double h = (font.lineHeight + 2 * st.labelPad()) * scale;
            reqs.add(r);
            items.add(new LabelLayout.Item(sx - w / 2, sy - h, w, h, dist));
            info.add(new double[] {dist, scale});
        }
        double[] target = LabelLayout.targets(items, 1.0);
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0.0 : Math.min(0.25, (now - lastNanos) / 1.0E9);
        lastNanos = now;
        Map<String, Float> seen = new HashMap<>();
        // far to near: the nearest is drawn last, on top
        Integer[] order = new Integer[reqs.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(items.get(b).distance(), items.get(a).distance()));
        for (int idx : order) {
            Req r = reqs.get(idx);
            String k = key(r);
            float cur = ALPHA.getOrDefault(k, (float) target[idx]);
            float step = st.labelFadeSeconds() <= 0.0 ? 1.0F : (float) (dt / st.labelFadeSeconds());
            cur = cur < target[idx] ? Math.min((float) target[idx], cur + step) : Math.max((float) target[idx], cur - step);
            seen.put(k, cur);
            double a = cur * st.labelDistanceAlpha(items.get(idx).distance());
            if (a <= 0.01) {
                continue;
            }
            LabelLayout.Item it = items.get(idx);
            float scale = (float) info.get(idx)[1];
            int pad = st.labelPad();
            int tw = font.width(r.text);
            int w = tw + 2 * pad + 4;
            int h = font.lineHeight + 2 * pad;
            g.pose().pushMatrix();
            g.pose().translate((float) it.x(), (float) it.y());
            g.pose().scale(scale, scale);
            g.fill(0, 0, w, h, Style.withAlpha(st.labelBackground(), a));
            if ((st.labelBorder() >>> 24) != 0) {
                g.outline(0, 0, w, h, Style.withAlpha(st.labelBorder(), a));
            }
            g.fill(0, 0, 2, h, Style.withAlpha(r.marker | 0xFF000000, a));
            g.text(font, r.text, 4 + pad, pad, Style.withAlpha(st.labelText(), a), false);
            g.pose().popMatrix();
        }
        ALPHA.clear();
        ALPHA.putAll(seen);
    }
}
