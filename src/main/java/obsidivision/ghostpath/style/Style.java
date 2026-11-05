package obsidivision.ghostpath.style;

import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;

/**
 * Every colour, width, size, alpha and timing of the overlay in one place (session D, item 5), in three presets chosen by
 * {@code /path style default|soft|contrast} (saved in the config). Colours are ARGB. Nothing in here touches recorded or predicted data.
 * To change the look, change a number here.
 */
public record Style(
    String name,
    // lines and dots
    int tnt, int projectile, int item, int fallingBlock, int vehicle, int other,
    float lineWidth, float dotSize, float endDotSize, float outlineExtra, int outlineColor,
    /** Line alpha at the far end: from {@code fadeNear} blocks (full) to {@code fadeFar} blocks ({@code fadeMinAlpha}). */
    double fadeNear, double fadeFar, double fadeMinAlpha,
    /** Dots grow with distance from 1x at {@code dotNear} blocks to {@code dotMaxScale}x at {@code dotFar}. */
    double dotNear, double dotFar, double dotMaxScale,
    int savedAlpha,
    // labels
    int labelBackground, int labelBorder, int labelText, int labelPad, double labelNear, double labelFar, double labelFadeStart, double labelFadeEnd,
    double labelFadeSeconds, int markerExploded, int markerHit, int markerStuck, int markerFrozen, int markerLimit, int markerContinued, int markerOther,
    // panels, toasts
    int panelBackground, int panelBorder, int panelHeader, int panelText, int panelLabel, int panelPad, int panelRowGap,
    int toastBackground, double toastIn, double toastHold, double toastOut,
    // area overlay
    int areaWrite, int areaWriteFill, int areaRead, int areaWatched, int areaChunk, int areaWidened, int areaTrigger,
    double animationSeconds, double animationFastSeconds,
    // chat
    int chatPrefix, int chatInfo, int chatSuccess, int chatWarn, int chatError, int chatNumber) {

    public static final Style DEFAULT = new Style("default",
        0xFFE8605A, 0xFF58C8E8, 0xFFE8CC58, 0xFFE89048, 0xFF72D072, 0xFFE0E0E0,
        3.0F, 6.0F, 10.0F, 2.0F, 0xFF101010,
        24.0, 160.0, 0.35,
        8.0, 64.0, 1.6,
        0x80,
        0xB0101018, 0xFF404048, 0xFFF0F0F0, 2, 12.0, 36.0, 48.0, 128.0,
        0.2, 0xFFE8605A, 0xFF58C8E8, 0xFF9AA0A8, 0xFF9FE4FF, 0xFFE8CC58, 0xFFB8E0F8, 0xFFE0E0E0,
        0xD0101018, 0xFF58C8E8, 0xFFFFFFFF, 0xFFE0E0E0, 0xFF9AA0A8, 5, 1,
        0xB0101018, 0.15, 1.8, 0.5,
        0xFFE89048, 0x30E89048, 0xFF58A8D0, 0xFF70C070, 0xFF808890, 0xFFD060D0, 0xFFFFE040,
        0.4, 0.15,
        0xFF6EC8E8, 0xFFE0E0E0, 0xFF72D072, 0xFFE8CC58, 0xFFE8605A, 0xFFFFFFFF);

    /** Lighter and thinner: for calm scenes. */
    public static final Style SOFT = new Style("soft",
        0xFFEE8C86, 0xFF8CD8EE, 0xFFEEDC8C, 0xFFEEAE80, 0xFF9CDC9C, 0xFFE8E8E8,
        2.0F, 4.0F, 8.0F, 0.0F, 0x00000000,
        16.0, 128.0, 0.25,
        8.0, 64.0, 1.4,
        0x60,
        0x80181820, 0x00000000, 0xFFE8E8E8, 2, 12.0, 36.0, 40.0, 96.0,
        0.25, 0xFFEE8C86, 0xFF8CD8EE, 0xFFB0B4BA, 0xFFB8ECFF, 0xFFEEDC8C, 0xFFD0ECFC, 0xFFE8E8E8,
        0xA0181820, 0x8058C8E8, 0xFFF0F0F0, 0xFFD8D8D8, 0xFFA8ACB2, 5, 1,
        0x90181820, 0.2, 1.6, 0.6,
        0xFFEEAE80, 0x20EEAE80, 0xFF8CC0DC, 0xFF98D098, 0xFF9AA0A8, 0xFFDC98DC, 0xFFF0E070,
        0.5, 0.2,
        0xFF8CD0E8, 0xFFD8D8D8, 0xFF98D898, 0xFFEEDC8C, 0xFFEE8C86, 0xFFF0F0F0);

    /** Bolder with outlines: for bright or busy scenes. */
    public static final Style CONTRAST = new Style("contrast",
        0xFFFF3030, 0xFF20D0FF, 0xFFFFE020, 0xFFFF8000, 0xFF30E030, 0xFFFFFFFF,
        4.0F, 8.0F, 12.0F, 3.0F, 0xFF000000,
        32.0, 192.0, 0.5,
        8.0, 64.0, 1.8,
        0xA0,
        0xE0000000, 0xFFFFFFFF, 0xFFFFFFFF, 3, 12.0, 36.0, 64.0, 160.0,
        0.15, 0xFFFF3030, 0xFF20D0FF, 0xFFC0C0C0, 0xFF80E8FF, 0xFFFFE020, 0xFFA0E0FF, 0xFFFFFFFF,
        0xF0000000, 0xFFFFFFFF, 0xFFFFFF40, 0xFFFFFFFF, 0xFFC0C0C0, 6, 1,
        0xE0000000, 0.1, 2.0, 0.4,
        0xFFFF8000, 0x40FF8000, 0xFF30B0FF, 0xFF30E030, 0xFFA0A0A0, 0xFFFF40FF, 0xFFFFFF40,
        0.3, 0.1,
        0xFF40D0FF, 0xFFFFFFFF, 0xFF40FF40, 0xFFFFE020, 0xFFFF4040, 0xFFFFFF40);

    /** Colours shared by all presets (meaning, not look): where the real world freezes a path, a stretch frozen in the run, a pin, a real entity. */
    public static final int FREEZE_COLOR = 0xFFFFB030;
    public static final int STASIS_COLOR = 0xFF9FE4FF;
    public static final int PIN_COLOR = 0xFFFFFFFF;
    public static final int REAL_COLOR = 0xFFFFFF40;

    public static Style of(String name) {
        return switch (name) {
            case "soft" -> SOFT;
            case "contrast" -> CONTRAST;
            default -> DEFAULT;
        };
    }

    /** The style in force ({@code /path style}). */
    public static Style current() {
        return of(PathSettings.style);
    }

    public int color(FlightKind kind) {
        return switch (kind) {
            case TNT -> this.tnt;
            case PROJECTILE -> this.projectile;
            case ITEM -> this.item;
            case FALLING_BLOCK -> this.fallingBlock;
            case VEHICLE -> this.vehicle;
            case OTHER -> this.other;
        };
    }

    /** Marker colour of an end reason: "exploded t+89" etc. (a reason's first word decides). */
    public int marker(String endText) {
        String t = endText.startsWith("real:") ? "frozen" : endText;
        if (t.startsWith("exploded")) {
            return this.markerExploded;
        } else if (t.startsWith("hit")) {
            return this.markerHit;
        } else if (t.startsWith("stuck") || t.startsWith("settled") || t.startsWith("landed")) {
            return this.markerStuck;
        } else if (t.startsWith("frozen")) {
            return this.markerFrozen;
        } else if (t.startsWith("limit")) {
            return this.markerLimit;
        } else if (t.startsWith("continued")) {
            return this.markerContinued;
        }
        return this.markerOther;
    }

    /** Alpha (0..1) of a line segment {@code dist} blocks from the camera: full up to {@code fadeNear}, falling to {@code fadeMinAlpha} at {@code fadeFar}. */
    public double lineAlpha(double dist) {
        if (dist <= this.fadeNear) {
            return 1.0;
        }
        if (dist >= this.fadeFar) {
            return this.fadeMinAlpha;
        }
        return 1.0 - (1.0 - this.fadeMinAlpha) * (dist - this.fadeNear) / (this.fadeFar - this.fadeNear);
    }

    /** Dot size at {@code dist} blocks: grows a little with distance so it stays readable. */
    public float dotSize(float base, double dist) {
        double f = dist <= this.dotNear ? 0.0 : dist >= this.dotFar ? 1.0 : (dist - this.dotNear) / (this.dotFar - this.dotNear);
        return (float) (base * (1.0 + (this.dotMaxScale - 1.0) * f));
    }

    /** Alpha (0..1) of a label {@code dist} blocks away: full up to {@code labelFadeStart}, 0 at {@code labelFadeEnd}. */
    public double labelDistanceAlpha(double dist) {
        if (dist <= this.labelFadeStart) {
            return 1.0;
        }
        return dist >= this.labelFadeEnd ? 0.0 : 1.0 - (dist - this.labelFadeStart) / (this.labelFadeEnd - this.labelFadeStart);
    }

    /** Scale of a label's text at {@code dist}: 1 up to {@code labelNear} blocks, growing to 1.25x at {@code labelFar}, times {@code /path labels size}. */
    public float labelScale(double dist) {
        double f = dist <= this.labelNear ? 0.0 : dist >= this.labelFar ? 1.0 : (dist - this.labelNear) / (this.labelFar - this.labelNear);
        return (float) ((1.0 + 0.25 * f) * PathSettings.labelPercent / 100.0);
    }

    /** Alpha (0..1) of a toast {@code elapsed} seconds after it appeared: fades in, holds, fades out, then 0. */
    public double toastAlpha(double elapsed) {
        if (elapsed < 0.0) {
            return 0.0;
        }
        if (elapsed < this.toastIn) {
            return elapsed / this.toastIn;
        }
        if (elapsed < this.toastIn + this.toastHold) {
            return 1.0;
        }
        double out = elapsed - this.toastIn - this.toastHold;
        return out >= this.toastOut ? 0.0 : 1.0 - out / this.toastOut;
    }

    public double toastSeconds() {
        return this.toastIn + this.toastHold + this.toastOut;
    }

    /** Seconds of the morph for the animation mode in force. */
    public double animationFor(String mode) {
        return switch (mode) {
            case "off" -> 0.0;
            case "fast" -> this.animationFastSeconds;
            default -> this.animationSeconds;
        };
    }

    public static int withAlpha(int argb, double alpha) {
        int a = (int) Math.round((argb >>> 24) * Math.max(0.0, Math.min(1.0, alpha)));
        return (argb & 0x00FFFFFF) | a << 24;
    }
}
