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

    private PathSettings() {
    }
}
