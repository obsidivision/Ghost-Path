package obsidivision.ghostpath.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import obsidivision.ghostpath.chat.Chat;
import obsidivision.ghostpath.inspect.PanelModel;
import obsidivision.ghostpath.inspect.PanelText;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.record.CauseTracker.FlightKind;
import obsidivision.ghostpath.style.LabelLayout;
import obsidivision.ghostpath.style.PanelLayout;
import obsidivision.ghostpath.style.Style;

/** Session D, item 5: presets, label layout, panel placement and model, chat, toast curve (pure, no rendering). */
public class StyleTest {
    private static final Style[] PRESETS = {Style.DEFAULT, Style.SOFT, Style.CONTRAST};

    @GameTest
    public void presetsAreInRangeAndDistinct(GameTestHelper helper) {
        for (Style st : PRESETS) {
            Set<Integer> colors = new HashSet<>();
            for (FlightKind k : FlightKind.values()) {
                int c = st.color(k);
                helper.assertTrue((c >>> 24) == 0xFF, st.name() + ": colour of " + k + " is not opaque");
                colors.add(c);
            }
            helper.assertTrue(colors.size() == FlightKind.values().length, st.name() + ": entity type colours are not distinct");
            helper.assertTrue(st.lineWidth() >= 1.0F && st.lineWidth() <= 8.0F && st.dotSize() > 0 && st.endDotSize() > st.dotSize() && st.outlineExtra() >= 0, st.name() + ": widths/sizes");
            helper.assertTrue(st.savedAlpha() > 0 && st.savedAlpha() < 255, st.name() + ": saved alpha");
            for (int a : new int[] {st.labelBackground() >>> 24, st.panelBackground() >>> 24, st.toastBackground() >>> 24}) {
                helper.assertTrue(a > 0 && a <= 255, st.name() + ": background alpha");
            }
            helper.assertTrue(st.fadeMinAlpha() > 0 && st.fadeMinAlpha() <= 1 && st.fadeNear() < st.fadeFar() && st.dotNear() < st.dotFar() && st.labelFadeStart() < st.labelFadeEnd()
                && st.labelNear() < st.labelFar() && st.dotMaxScale() >= 1.0, st.name() + ": ranges");
            helper.assertTrue(st.animationFor("fast") < st.animationFor("on") && st.animationFor("off") == 0.0 && st.animationFor("fast") > 0, st.name() + ": animation times");
            // curves: monotonic and bounded
            double lastLine = 2;
            double lastLabel = 2;
            float lastDot = 0;
            for (int d = 0; d <= 400; d++) {
                double la = st.lineAlpha(d);
                double lb = st.labelDistanceAlpha(d);
                float ds = st.dotSize(6.0F, d);
                helper.assertTrue(la <= lastLine && la >= st.fadeMinAlpha() - 1e-12 && lb <= lastLabel && lb >= 0 && ds >= lastDot, st.name() + ": curves at " + d);
                lastLine = la;
                lastLabel = lb;
                lastDot = ds;
            }
            helper.assertTrue(st.labelScale(0) == 1.0F && st.labelScale(1000) == 2.0F && st.labelDistanceAlpha(1000) == 0.0, st.name() + ": label scale/fade ends");
            // toast: fades in, holds, fades out
            helper.assertTrue(st.toastAlpha(0) == 0.0 && st.toastAlpha(st.toastIn() + 0.01) == 1.0 && st.toastAlpha(st.toastSeconds() + 1e-9) == 0.0 && st.toastAlpha(st.toastIn() / 2) > 0.0
                && st.toastAlpha(st.toastIn() + st.toastHold() + st.toastOut() / 2) > 0.0 && st.toastAlpha(st.toastIn() + st.toastHold() + st.toastOut() / 2) < 1.0, st.name() + ": toast curve");
            // end reason markers differ for the reasons
            Set<Integer> markers = new HashSet<>();
            for (String r : new String[] {"exploded t+1", "hit block t+1", "stuck t+1", "real: frozen at t+3; ghost: x", "limit t+1", "continued: x"}) {
                markers.add(st.marker(r));
            }
            helper.assertTrue(markers.size() == 6, st.name() + ": markers " + markers.size());
        }
        helper.assertTrue(Style.SOFT.lineWidth() < Style.DEFAULT.lineWidth() && Style.DEFAULT.lineWidth() < Style.CONTRAST.lineWidth() && Style.CONTRAST.outlineExtra() > 0 && Style.SOFT.outlineExtra() == 0
            && Style.SOFT.savedAlpha() < Style.CONTRAST.savedAlpha(), "soft is lighter and thinner, contrast bolder with outlines");
        helper.assertTrue(Style.of("soft") == Style.SOFT && Style.of("contrast") == Style.CONTRAST && Style.of("x") == Style.DEFAULT, "preset lookup");
        helper.succeed();
    }

    @GameTest
    public void labelLayoutNeverLeavesTwoVisibleLabelsOverlapping(GameTestHelper helper) {
        Random rnd = new Random(42);
        for (int round = 0; round < 300; round++) {
            List<LabelLayout.Item> items = new ArrayList<>();
            int n = 2 + rnd.nextInt(40);
            for (int i = 0; i < n; i++) {
                items.add(new LabelLayout.Item(rnd.nextInt(300), rnd.nextInt(200), 20 + rnd.nextInt(80), 10 + rnd.nextInt(14), rnd.nextDouble() * 100));
            }
            double[] t = LabelLayout.targets(items, 1.0);
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (t[i] > 0 && t[j] > 0) {
                        LabelLayout.Item a = items.get(i);
                        LabelLayout.Item b = items.get(j);
                        boolean overlap = a.x() < b.x() + b.w() + 1 && b.x() < a.x() + a.w() + 1 && a.y() < b.y() + b.h() + 1 && b.y() < a.y() + a.h() + 1;
                        helper.assertTrue(!overlap, "two visible labels overlap in round " + round);
                    }
                }
            }
            // the nearest label is always shown
            int nearest = 0;
            for (int i = 1; i < n; i++) {
                if (items.get(i).distance() < items.get(nearest).distance()) {
                    nearest = i;
                }
            }
            helper.assertTrue(t[nearest] == 1.0, "the nearest label was hidden");
        }
        // two on top of each other: the nearer one stays
        double[] t = LabelLayout.targets(List.of(new LabelLayout.Item(10, 10, 50, 12, 30), new LabelLayout.Item(12, 11, 50, 12, 5)), 1.0);
        helper.assertTrue(t[0] == 0.0 && t[1] == 1.0, "nearest should stay");
        helper.succeed();
    }

    @GameTest
    public void panelsStayInsideTheScreen(GameTestHelper helper) {
        int[][] windows = {{1280, 720}, {854, 480}, {640, 360}, {427, 240}, {320, 200}};
        Random rnd = new Random(7);
        for (int[] win : windows) {
            for (int scale = 1; scale <= 4; scale++) {
                int sw = win[0] / scale;
                int sh = win[1] / scale;
                for (int k = 0; k < 200; k++) {
                    int w = 60 + rnd.nextInt(200);
                    int h = 40 + rnd.nextInt(160);
                    int ax = rnd.nextInt(sw);
                    int ay = rnd.nextInt(sh);
                    PanelLayout.Rect r = PanelLayout.beside(sw, sh, ax, ay, w, h, 14, 2);
                    boolean fits = w <= sw - 4 && h <= sh - 4;
                    helper.assertTrue(!fits || r.inside(sw, sh), "panel " + w + "x" + h + " outside " + sw + "x" + sh + " at " + r);
                    helper.assertTrue(fits || (r.x() == 2 || w <= sw - 4) && (r.y() == 2 || h <= sh - 4), "a panel larger than the screen is not at the margin");
                    PanelLayout.Rect c = PanelLayout.clamp(sw, sh, ax, ay, w, h, 2);
                    helper.assertTrue(!fits || c.inside(sw, sh), "clamp outside");
                }
                // beside the crosshair at the right edge flips to the left
                PanelLayout.Rect edge = PanelLayout.beside(sw, sh, sw - 5, sh / 2, 60, 40, 14, 2);
                helper.assertTrue(edge.x() + edge.w() <= sw - 5, "did not flip at the right edge: " + edge);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void panelModelKeepsEveryLine(GameTestHelper helper) {
        double[] pos = {1.5, 2.25, -3.125};
        PathSnapshot.Line l = PathMorphTest.line("minecraft:tnt", 4, 5, 6, 0.1, 0.3, 70);
        List<List<String>> panels = new ArrayList<>();
        panels.add(PanelText.ghost(l, 3, 0, "recorded"));
        panels.add(PanelText.ghost(l, 5, 0, "rerun"));
        panels.add(PanelText.continued("minecraft:arrow", 12, pos, pos, true, "hit block"));
        for (List<String> lines : panels) {
            PanelModel.Model m = PanelModel.of(lines);
            helper.assertTrue(m.header().equals(lines.getFirst()), "header: " + m.header());
            List<String> flat = m.flatten();
            List<String> rest = new ArrayList<>(lines.subList(1, lines.size()));
            helper.assertTrue(flat.size() == rest.size() && new HashSet<>(flat).equals(new HashSet<>(rest)), "a line was lost or changed: " + rest + " vs " + flat);
            List<String> titles = m.sections().stream().map(PanelModel.Section::title).toList();
            helper.assertTrue(titles.contains("Position") && titles.contains("Velocity") && titles.indexOf("Position") < titles.indexOf("Velocity"), "sections: " + titles);
            for (PanelModel.Section s : m.sections()) {
                for (PanelModel.Row r : s.rows()) {
                    if (r.number()) {
                        helper.assertTrue(r.line().endsWith(r.value()) && PanelModel.pointAt(r.value()) <= r.value().length(), "number row");
                    }
                }
            }
        }
        helper.assertTrue(PanelModel.pointAt("12.5") == 2 && PanelModel.pointAt("-0.04") == 2 && PanelModel.pointAt("7") == 1 && PanelModel.pointAt("1.0E-5") == 1 && PanelModel.pointAt("1E-5") == 4, "decimal point column");
        helper.succeed();
    }

    @GameTest
    public void chatMessagesHaveOnePrefixAndTheSameText(GameTestHelper helper) {
        String text = "Rerun: 3 ticks, 20 TNT spawned in 423 ms, x -1.5";
        for (Chat.Level lv : Chat.Level.values()) {
            net.minecraft.network.chat.Component c = Chat.of(lv, text);
            helper.assertTrue(c.getString().equals(Chat.PREFIX + text), "text changed: " + c.getString());
        }
        net.minecraft.network.chat.Component c = Chat.info("Saved 'a' 10 TNT");
        boolean numberColored = false;
        for (net.minecraft.network.chat.Component part : c.getSiblings()) {
            if (part.getString().equals("10")) {
                numberColored = part.getStyle().getColor() != null && part.getStyle().getColor().getValue() == (Style.current().chatNumber() & 0xFFFFFF);
            }
        }
        helper.assertTrue(numberColored, "numbers are not highlighted");
        helper.assertTrue(Chat.error("x").getString().startsWith(Chat.PREFIX) && Chat.error("x").getStyle().getColor().getValue() == (Style.current().chatPrefix() & 0xFFFFFF), "prefix is not first, or not in the prefix colour");
        helper.assertTrue(Chat.line("Not watched: limit").getSiblings().stream().anyMatch(p -> p.getStyle().getColor() != null && p.getStyle().getColor().getValue() == (Style.current().chatWarn() & 0xFFFFFF)), "warning colour");
        helper.succeed();
    }
}
