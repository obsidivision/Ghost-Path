package obsidivision.ghostpath;

/** Session settings of the {@code /path} command (not saved). Read when a recording starts. */
public final class PathSettings {
    public static final int DEFAULT_TICK_LIMIT = 2000;

    /** {@code /path kick on|off}: whether the TNT kick is applied (off: drawn, discarded, horizontal part 0). */
    public static volatile boolean kick = true;
    /** {@code /path limit <ticks>}: the most ticks a recording may run. */
    public static volatile int tickLimit = DEFAULT_TICK_LIMIT;

    /** Live rerun (step 4): server-thread time per server tick a rerun in the ghost may use (at least one ghost tick runs anyway; the copy of the chunks uses the same budget, at least one chunk per tick; 0 means one unit per server tick). */
    public static volatile int rerunBudgetMs = 10;

    /**
     * Whether a successful {@code /path machine} starts a watch (live rerun, step 4). On by default. The game tests that run
     * the command for other reasons switch it off: a watch that outlives its test would rerun when the next batch clears the
     * test area.
     */
    public static volatile boolean watchAfterMachine = true;

    /**
     * {@code /path watch margin <n>}: a real block change within this many blocks (each axis) of a block the chain wrote counts as a
     * change of the watched machine, besides the footprint and the neighbours of written blocks (which always count). 0 = off.
     */
    public static volatile int watchMargin = 2;

    /** Ghost area overlay ({@code /path area on|off}, an unbound key): the machine's footprint boxes. Off by default. */
    public static volatile boolean showArea = false;
    /** {@code /path area watched on|off}: the watched area (margin around the machine's written blocks). */
    public static volatile boolean areaWatched = false;
    /** {@code /path area chunks on|off}: the chunks the last run copied, widened ones in a second colour. */
    public static volatile boolean areaChunks = false;

    /**
     * Flight preview (step 5), read by the client in singleplayer (same JVM as the built-in server): whether the paths are
     * drawn ({@code /path show on|off}, the toggle key).
     */
    public static volatile boolean showPaths = true;
    /** {@code /path interval <ticks> [offset <n>]}: a dot every this many ticks (ticks since the recording began), start and end always. */
    public static volatile int dotInterval = 5;
    /** The dots sit at ticks where (tick - offset) is a multiple of the interval. */
    public static volatile int dotOffset = 0;

    /** {@code /path inspect hold|toggle}: whether the inspect key works while it is held (default) or each press switches it on or off. */
    public static volatile boolean inspectToggle = false;
    /** {@code /path pins clear}: raised each time; the client clears its pins when it sees a new value. */
    public static volatile int pinsClearSerial = 0;

    /**
     * {@code /path beyond-loaded on|off}: whether a live rerun whose chain reaches beyond the loaded area may take the chunks that are
     * not loaded from the save (default on) and so keep drawing past the loaded area. Off: only loaded chunks are copied, as before.
     */
    public static volatile boolean beyondLoaded = true;

    /** {@code /path animation on|off|fast} (saved in the config): how a rerun's new lines come in. */
    public static volatile String animation = "on";
    /** {@code /path style default|soft|contrast} (saved in the config). */
    public static volatile String style = "default";

    public static boolean validAnimation(String s) {
        return s.equals("on") || s.equals("off") || s.equals("fast");
    }

    public static boolean validStyle(String s) {
        return s.equals("default") || s.equals("soft") || s.equals("contrast");
    }

    /** Multiplies the animation time; only the client test raises it, so it can catch the morph while it runs. */
    public static volatile double animationTestScale = 1.0;

    /** Seconds the morph from the old to the new prediction lasts; 0 = no animation. */
    public static double animationSeconds() {
        return obsidivision.ghostpath.style.Style.current().animationFor(animation) * animationTestScale;
    }

    private PathSettings() {
    }
}
