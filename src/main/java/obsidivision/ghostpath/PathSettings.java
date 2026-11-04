package obsidivision.ghostpath;

/** Session settings of the {@code /path} command (not saved). Read when a recording starts. */
public final class PathSettings {
    public static final int DEFAULT_TICK_LIMIT = 2000;

    /** {@code /path kick on|off}: whether the TNT kick is applied (off: drawn, discarded, horizontal part 0). */
    public static volatile boolean kick = true;
    /** {@code /path limit <ticks>}: the most ticks a recording may run. */
    public static volatile int tickLimit = DEFAULT_TICK_LIMIT;

    private PathSettings() {
    }
}
