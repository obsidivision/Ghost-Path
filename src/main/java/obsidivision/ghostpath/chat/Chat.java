package obsidivision.ghostpath.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Chat messages of the mod (session D, item 5): one prefix, one colour scheme (info, success, warning, error), numbers highlighted. Only the
 * look changes: {@link Component#getString} is the prefix plus the unchanged text, so every fact stays in the line.
 */
public final class Chat {
    public static final String PREFIX = "Ghost Path » ";
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w])[-+]?\\d+(?:\\.\\d+)?(?:ms|s)?(?![\\w])");

    public enum Level {
        INFO, SUCCESS, WARN, ERROR
    }

    private Chat() {
    }

    private static MutableComponent colored(String text, int rgb) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(rgb & 0xFFFFFF));
    }

    public static MutableComponent of(Level level, String text) {
        obsidivision.ghostpath.style.Style st = obsidivision.ghostpath.style.Style.current();
        int body = switch (level) {
            case SUCCESS -> st.chatSuccess();
            case WARN -> st.chatWarn();
            case ERROR -> st.chatError();
            default -> st.chatInfo();
        };
        MutableComponent out = colored(PREFIX, st.chatPrefix());
        Matcher m = NUMBER.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) {
                out.append(colored(text.substring(at, m.start()), body));
            }
            out.append(colored(m.group(), st.chatNumber()));
            at = m.end();
        }
        if (at < text.length()) {
            out.append(colored(text.substring(at), body));
        }
        return out;
    }

    public static MutableComponent info(String text) {
        return of(Level.INFO, text);
    }

    public static MutableComponent success(String text) {
        return of(Level.SUCCESS, text);
    }

    public static MutableComponent warn(String text) {
        return of(Level.WARN, text);
    }

    public static MutableComponent error(String text) {
        return of(Level.ERROR, text);
    }

    /** A line of a recording's summary or a rerun: a warning when it says so, else info. */
    public static MutableComponent line(String text) {
        String t = text.toLowerCase(java.util.Locale.ROOT);
        boolean warn = t.startsWith("not watched") || t.contains("warning") || t.contains("not simulated") || t.contains("first difference") || t.contains("changed since recording");
        return of(warn ? Level.WARN : Level.INFO, text);
    }
}
