package obsidivision.ghostpath.record;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Unseeded random draws by chain work that are known to be harmless (DESIGN ruling, 1b follow-up). They are still
 * logged as {@link ChainEvent.Kind#UNSEEDED_DRAW}, marked "allowed"; any site not listed here is logged as a warning.
 *
 * <p>Key: "File.java:line" of the first caller outside {@code RandomSource}, {@code Mth} and {@code Util}, in the 26.2
 * sources from genSources (the runtime jar is line-remapped to match them). A version change moves these lines; an
 * entry that no longer matches simply turns back into a warning, which is the safe direction.
 */
public final class UnseededDrawAllowlist {
    private static final Map<String, String> ALLOWED = Map.of(
        // level.playSound(..., random.nextFloat() * 0.25F + 0.6F) in triggerEvent, extend branch
        "PistonBaseBlock.java:172", "piston extend sound pitch; changes no block, entity or later draw by chain work"
    );

    private UnseededDrawAllowlist() {
    }

    /** The reason this site is allowed, or null if it is not on the list. */
    public static @Nullable String reason(String fileAndLine) {
        return ALLOWED.get(fileAndLine);
    }
}
