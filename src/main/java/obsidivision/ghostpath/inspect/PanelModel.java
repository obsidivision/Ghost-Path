package obsidivision.ghostpath.inspect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The panel's layout as data (session D, item 5): a header line (entity type and tick) and sections (entity, position, velocity, state, chunk
 * status), each with rows of a label and a value. Built from {@link PanelText}'s lines, which stay the source of every fact: no line is
 * dropped or changed ({@link #flatten} gives them back). Pure; the client draws it.
 */
public final class PanelModel {
    private static final Pattern VECTOR = Pattern.compile("^(pos|vel|in block) ([xyz]) (.+)$");

    /** A row: {@code label} (may be empty), {@code value}; {@code number} when the value is a number to align on its decimal point. */
    public record Row(String label, String value, boolean number, String line) {
    }

    public record Section(String title, List<Row> rows) {
    }

    public record Model(String header, List<Section> sections) {
        /** Every row's original line, in section order (for the "nothing lost" check and for text copies). */
        public List<String> flatten() {
            List<String> out = new ArrayList<>();
            for (Section s : this.sections) {
                for (Row r : s.rows()) {
                    out.add(r.line());
                }
            }
            return out;
        }
    }

    private PanelModel() {
    }

    private static final List<String> ORDER = List.of("Entity", "Position", "Velocity", "State", "Chunk");

    private static String section(String line) {
        if (line.startsWith("pos ") || line.startsWith("block ") || line.startsWith("in block ")) {
            return "Position";
        }
        if (line.startsWith("vel ")) {
            return "Velocity";
        }
        if (line.startsWith("FROZEN") || line.startsWith("BEYOND") || line.startsWith("real:") || line.startsWith("moved while frozen") || line.startsWith("... ")
            || line.matches("^t\\+\\d.*")) {
            return "Chunk";
        }
        if (line.startsWith("fuse") || line.startsWith("ends:") || line.equals("in flight") || line.equals("on ground")) {
            return "State";
        }
        return "Entity";
    }

    /** {@code lines}: a {@link PanelText} result, possibly with a leading "PIN" line. */
    public static Model of(List<String> lines) {
        String header = lines.isEmpty() ? "" : lines.getFirst();
        int from = 1;
        if (header.equals("PIN") && lines.size() > 1) { // a pinned panel: "PIN" then the usual lines
            header = "PIN  " + lines.get(1);
            from = 2;
        }
        Map<String, List<Row>> by = new LinkedHashMap<>();
        for (String t : ORDER) {
            by.put(t, new ArrayList<>());
        }
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = VECTOR.matcher(line);
            Row row;
            String sec;
            if (m.matches()) {
                row = new Row(m.group(1).equals("in block") ? "in-block " + m.group(2) : m.group(2), m.group(3), true, line);
                sec = section(line);
            } else if (line.startsWith("block ")) {
                row = new Row("block", line.substring(6), false, line);
                sec = "Position";
            } else {
                row = new Row("", line, false, line);
                sec = section(line);
            }
            by.get(sec).add(row);
        }
        List<Section> out = new ArrayList<>();
        for (Map.Entry<String, List<Row>> e : by.entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.add(new Section(e.getKey(), List.copyOf(e.getValue())));
            }
        }
        return new Model(header, List.copyOf(out));
    }

    /** Where to break a number for aligned columns: the index of the '.', or the length when there is none or the number has an exponent. */
    public static int pointAt(String number) {
        int e = number.indexOf('E');
        int p = number.indexOf('.');
        return p < 0 || e >= 0 && e < p ? number.length() : p;
    }
}
