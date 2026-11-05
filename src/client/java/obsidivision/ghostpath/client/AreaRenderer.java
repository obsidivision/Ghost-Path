package obsidivision.ghostpath.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.preview.AreaData;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;

/**
 * The ghost area overlay: the footprint of the machine as merged boxes (written blocks in a strong colour and a faint fill, read
 * blocks thin and pale), and, if switched on, the watched margin and the copied chunks (widened ones in a second colour). The block
 * change that started the last rerun is highlighted for a few seconds with its label. Drawn from the latest {@link PathSnapshot}'s
 * {@link AreaData}, so it updates with the paths after every recording and rerun. Gizmos, like the paths.
 */
public final class AreaRenderer {
    /** Milliseconds the last trigger stays highlighted after the rerun's result was published. */
    public static final long HIGHLIGHT_MILLIS = 6000L;
    static final int WRITE_STROKE = 0xFFFF8A30;
    static final int WRITE_FILL = 0x30FF8A30;
    static final int READ_STROKE = 0xB05AA0FF;
    static final int WATCHED_STROKE = 0x9040E080;
    static final int CHUNK_STROKE = 0xA040D0FF;
    static final int WIDENED_STROKE = 0xB0FF50E0;
    static final int TRIGGER_COLOR = 0xFFFFE040;

    /** Frames in which the area was drawn, and the boxes of the last such frame (read by the client game test). */
    public static volatile int framesDrawn;
    public static volatile int lastBoxes;

    private AreaRenderer() {
    }

    static void render() {
        if (!PathSettings.showArea || !PathSettings.showPaths) {
            return;
        }
        AreaData a = area();
        if (a == null) {
            return;
        }
        framesDrawn++;
        int boxes = 0;
        for (AreaData.Box b : a.boxes()) {
            AABB bb = box(b);
            if (b.write()) {
                Gizmos.cuboid(bb, GizmoStyle.strokeAndFill(WRITE_STROKE, 3.0F, WRITE_FILL));
            } else {
                Gizmos.cuboid(bb, GizmoStyle.stroke(READ_STROKE, 1.5F));
            }
            boxes++;
        }
        if (PathSettings.areaWatched) {
            for (AreaData.Box b : a.watched()) {
                Gizmos.cuboid(box(b), GizmoStyle.stroke(WATCHED_STROKE, 1.0F));
                boxes++;
            }
        }
        if (PathSettings.areaChunks) {
            double y0 = a.minY() - 2;
            double y1 = a.maxY() + 3;
            for (AreaData.ChunkCell c : a.chunkCells()) {
                Gizmos.cuboid(new AABB(c.x() * 16.0, y0, c.z() * 16.0, c.x() * 16.0 + 16.0, y1, c.z() * 16.0 + 16.0), GizmoStyle.stroke(c.widened() ? WIDENED_STROKE : CHUNK_STROKE, 1.0F));
                boxes++;
            }
        }
        AreaData.Change ch = a.change();
        if (ch != null && ch.pos() != null) {
            long age = System.currentTimeMillis() - ch.millis();
            if (age >= 0 && age < HIGHLIGHT_MILLIS) {
                // pulses: the box breathes (±0.08) twice a second; fades out over the last second
                double pulse = 0.04 + 0.04 * Math.sin(age / 1000.0 * Math.PI * 4);
                int alpha = age > HIGHLIGHT_MILLIS - 1000 ? (int) (255 * (HIGHLIGHT_MILLIS - age) / 1000.0) : 255;
                int color = alpha << 24 | (TRIGGER_COLOR & 0xFFFFFF);
                Gizmos.cuboid(new AABB(ch.pos()).inflate(pulse), GizmoStyle.strokeAndFill(color, 4.0F, (alpha / 4) << 24 | (TRIGGER_COLOR & 0xFFFFFF)));
                PathRenderer.label(ch.label(), Vec3.atCenterOf(ch.pos()).add(0.0, 1.0, 0.0), color);
                boxes++;
            }
        }
        lastBoxes = boxes;
    }

    private static AABB box(AreaData.Box b) {
        return new AABB(b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1());
    }

    /** The latest snapshot's area, if it is for the dimension the player is in; else null. */
    static AreaData area() {
        PathSnapshot s = PathStore.latest();
        ClientLevel level = Minecraft.getInstance().level;
        if (s == null || s.area() == null || level == null || !level.dimension().identifier().toString().equals(s.dimension())) {
            return null;
        }
        return s.area();
    }

    /** The lines of the HUD text (empty when nothing is shown): box counts, then the layers; the cap is said when the footprint had more boxes than are drawn. */
    public static List<String> hudLines() {
        if (!PathSettings.showArea || !PathSettings.showPaths) {
            return List.of();
        }
        AreaData a = area();
        if (a == null) {
            return List.of("Ghost area: no machine yet");
        }
        List<String> out = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder("Ghost area: ");
        if (a.capped()) {
            sb.append("CAPPED, ").append(a.boxes().size()).append(" of ").append(a.totalBoxes()).append(" boxes drawn (").append(a.totalBoxes() - a.boxes().size()).append(" read boxes not drawn)");
        } else {
            sb.append(a.boxes().size()).append(" boxes");
        }
        sb.append(" (").append(a.writeBlocks()).append(" blocks written, ").append(a.readBlocks()).append(" read)");
        out.add(sb.toString());
        if (PathSettings.areaWatched) {
            StringBuilder w = new StringBuilder("watched margin ").append(a.margin());
            if (a.watchedSkipped()) {
                w.append(" too big to draw");
            } else if (a.watchedCapped()) {
                w.append(" CAPPED ").append(a.watched().size()).append(" of ").append(a.watchedTotal());
            } else {
                w.append(": ").append(a.watched().size()).append(" boxes");
            }
            out.add(w.toString());
        }
        if (PathSettings.areaChunks) {
            out.add("chunks " + (a.copied() == null ? "none copied" : a.chunkCells().size() + (a.widenedChunks() > 0 ? " (" + a.widenedChunks() + " widened)" : "")));
        }
        return out;
    }

    /** The HUD text as one line, or null when nothing is shown. */
    public static String hudLine() {
        List<String> lines = hudLines();
        return lines.isEmpty() ? null : String.join(", ", lines);
    }

    static void hud(GuiGraphicsExtractor g) {
        List<String> lines = hudLines();
        Minecraft mc = Minecraft.getInstance();
        if (lines.isEmpty() || mc.level == null) {
            return;
        }
        Font font = mc.font;
        AreaData a = area();
        boolean capped = a != null && (a.capped() || PathSettings.areaWatched && (a.watchedCapped() || a.watchedSkipped()));
        int w = 0;
        for (String l : lines) {
            w = Math.max(w, font.width(l));
        }
        g.fill(4, 4, 4 + w + 8, 4 + lines.size() * 10 + 4, 0xA0101010);
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), 8, 6 + i * 10, capped && i == 0 ? 0xFFFFB030 : 0xFFE0E0E0, false);
        }
    }
}
