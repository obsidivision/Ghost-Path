package obsidivision.ghostpath;

/** Session settings of the {@code /path} command (not saved). Read when a recording starts. */
public final class PathSettings {
    public static final int DEFAULT_TICK_LIMIT = 2000;

    /** {@code /path kick on|off}: whether the TNT kick is applied (off: drawn, discarded, horizontal part 0). */
    public static volatile boolean kick = true;
    /** {@code /path limit <ticks>}: the most ticks a recording may run. */
    public static volatile int tickLimit = DEFAULT_TICK_LIMIT;

    /** Live rerun (step 4): server-thread time per server tick a rerun in the ghost may use (at least one ghost tick runs anyway). */
    public static volatile int rerunBudgetMs = 10;

    /**
     * Whether a successful {@code /path machine} starts a watch (live rerun, step 4). On by default. The game tests that run
     * the command for other reasons switch it off: a watch that outlives its test would rerun when the next batch clears the
     * test area.
     */
    public static volatile boolean watchAfterMachine = true;

    /**
     * Flight preview (step 5), read by the client in singleplayer (same JVM as the built-in server): whether the paths are
     * drawn ({@code /path show on|off}, the toggle key).
     */
    public static volatile boolean showPaths = true;
    /** {@code /path interval <ticks> [offset <n>]}: a dot every this many ticks (ticks since the recording began), start and end always. */
    public static volatile int dotInterval = 5;
    /** The dots sit at ticks where (tick - offset) is a multiple of the interval. */
    public static volatile int dotOffset = 0;

    private PathSettings() {
    }
}
