package obsidivision.ghostpath.style;

/** Where a panel goes on screen (session D, item 5), pure: next to an anchor, flipped to the other side at the screen edge, always inside the screen if it fits. */
public final class PanelLayout {
    private PanelLayout() {
    }

    public record Rect(int x, int y, int w, int h) {
        public boolean inside(int screenW, int screenH) {
            return this.x >= 0 && this.y >= 0 && this.x + this.w <= screenW && this.y + this.h <= screenH;
        }
    }

    /**
     * A panel of {@code w} x {@code h} beside the point ({@code ax}, {@code ay}) (to its right, vertically centred), on the left of it when
     * it does not fit on the right, and then moved inside the screen {@code margin} pixels from each edge. A panel larger than the screen
     * sits at the margin (top left).
     */
    public static Rect beside(int screenW, int screenH, int ax, int ay, int w, int h, int gap, int margin) {
        int x = ax + gap;
        if (x + w > screenW - margin) {
            x = ax - gap - w;
        }
        return clamp(screenW, screenH, x, ay - h / 2, w, h, margin);
    }

    public static Rect clamp(int screenW, int screenH, int x, int y, int w, int h, int margin) {
        int cx = Math.max(margin, Math.min(x, screenW - w - margin));
        int cy = Math.max(margin, Math.min(y, screenH - h - margin));
        if (w > screenW - 2 * margin) {
            cx = margin;
        }
        if (h > screenH - 2 * margin) {
            cy = margin;
        }
        return new Rect(cx, cy, w, h);
    }
}
