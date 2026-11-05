package obsidivision.ghostpath.style;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Label declutter (session D, item 5), pure: of labels that overlap on screen the nearest stays, the others get target alpha 0. */
public final class LabelLayout {
    private LabelLayout() {
    }

    /** A label's rectangle on screen (top left, size, in GUI pixels) and its distance from the camera. */
    public record Item(double x, double y, double w, double h, double distance) {
        boolean overlaps(Item o, double margin) {
            return this.x < o.x + o.w + margin && o.x < this.x + this.w + margin && this.y < o.y + o.h + margin && o.y < this.y + this.h + margin;
        }
    }

    /** Target alpha per item, in the order of {@code items}: 1 = shown, 0 = hidden because a nearer shown label overlaps it. */
    public static double[] targets(List<Item> items, double margin) {
        double[] out = new double[items.size()];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> items.get(i).distance()));
        List<Item> shown = new ArrayList<>();
        for (int i : order) {
            Item it = items.get(i);
            boolean clash = false;
            for (Item s : shown) {
                if (it.overlaps(s, margin)) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                shown.add(it);
                out[i] = 1.0;
            }
        }
        return out;
    }
}
