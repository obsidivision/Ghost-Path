package obsidivision.ghostpath.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import obsidivision.ghostpath.PathSettings;
import obsidivision.ghostpath.inspect.EntityInspector;
import obsidivision.ghostpath.inspect.EntityReadout;
import obsidivision.ghostpath.inspect.PanelModel;
import obsidivision.ghostpath.inspect.PanelText;
import obsidivision.ghostpath.style.PanelLayout;
import obsidivision.ghostpath.style.Style;
import obsidivision.ghostpath.inspect.Picker;
import obsidivision.ghostpath.inspect.Pin;
import obsidivision.ghostpath.preview.PathSnapshot;
import obsidivision.ghostpath.preview.PathStore;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * The inspector (build step 6). All state is touched on the client thread only (rendering and client ticks run there), except
 * {@link #realData}, which the server thread publishes.
 * <ul>
 * <li><b>Hover</b>, every frame, with a ray from the camera of up to {@link Picker#MAX_DISTANCE} blocks that stops at the first
 *     block unless the inspect key is held: the nearest of a path point of the latest snapshot ({@link Picker}) and a real TNT or
 *     projectile (its box, from the client's copy of the world). A path point expands into the ghost entity's bounding box at that
 *     tick plus a panel; a real entity into the <i>server's</i> exact box plus a panel, read from the built-in server entity with the
 *     same id ({@link EntityInspector}, asked for on the server thread every client tick while it is hovered).</li>
 * <li><b>Pins</b>: the inspect key and a click on a hovered path point pin it, a click on it again unpins. A {@link Pin} names the
 *     entity and the tick, so it finds its point again in a new snapshot (a rerun).</li>
 * <li><b>Scroll</b> with the inspect key held steps through a stack: the identical paths merged into one line (the entity indices),
 *     or the real entities at the same place.</li>
 * </ul>
 * The inspect key's clicks and scrolls are taken by the mixins {@code MinecraftInspectMixin} and {@code MouseHandlerInspectMixin}
 * (no block is broken and the hotbar does not move while the key is held).
 */
public final class Inspector {
    public static final int MAX_PINS = 32;
    private static final int REAL_COLOR = Style.REAL_COLOR;
    private static final int PIN_COLOR = Style.PIN_COLOR;

    /**
     * A hovered path point. It carries the snapshot it was found in: the indices mean nothing in a newer one (a rerun can publish
     * between the frame that hovered and the HUD draw, a click or a scroll).
     */
    public record GhostHover(PathSnapshot snapshot, int line, int point, double along) {
        PathSnapshot.Line path() {
            return this.snapshot.lines().get(this.line);
        }
    }

    /**
     * A hovered point of a client-side continuation ({@link Tails}): {@code point} indexes the job's own points. The job is kept
     * (its arrays only grow), so the record stays valid when a newer snapshot starts other jobs.
     */
    public record TailHover(PathSnapshot snapshot, int line, Tails.Job job, int point, double along) {
    }

    /** A hovered real entity (the client's id, which is the server's). */
    public record RealHover(int id, double along) {
    }

    /** What the server said about the hovered real entity: its stack, at {@code serverTick}. */
    public record RealData(int hoveredId, long serverTick, EntityInspector.Stack stack) {
    }

    /** A hovered point of a saved path ({@code line} indexes the saved path's lines). Saved points cannot be pinned. */
    public record SavedHover(obsidivision.ghostpath.saved.SavedPath saved, int line, int point, double along) {
        PathSnapshot.Line path() {
            return this.saved.lines().get(this.line);
        }
    }

    private static @Nullable GhostHover hoverGhost;
    private static volatile @Nullable SavedHover hoverSaved;
    private static @Nullable TailHover hoverTail;
    private static @Nullable RealHover hoverReal;
    /** Toggle mode ({@code /path inspect toggle}): whether the inspect key is switched on. */
    private static boolean toggledOn;
    private static int seenPinsClear = PathSettings.pinsClearSerial;
    private static int memberCursor;
    private static int cursorLine = -1;
    private static int realSelected;
    private static int realLastId = -1;
    private static final List<Pin> PINS = new ArrayList<>();
    private static volatile @Nullable RealData realData;

    /** What the panels showed in the last HUD draw, and how many HUD draws had a panel (read by the client game test). */
    public static volatile List<String> lastHoverPanel = List.of();
    public static volatile int panelsDrawn;

    private Inspector() {
    }

    public static boolean inspecting() {
        Minecraft mc = Minecraft.getInstance();
        boolean on = PathSettings.inspectToggle ? toggledOn : GhostPathClient.INSPECT.isDown();
        return on && PathSettings.showPaths && mc.gui.screen() == null && mc.level != null;
    }

    public static @Nullable SavedHover hoveredSaved() {
        return hoverSaved;
    }

    public static @Nullable GhostHover hoveredGhost() {
        return hoverGhost;
    }

    public static @Nullable TailHover hoveredTail() {
        return hoverTail;
    }

    public static @Nullable RealHover hoveredReal() {
        return hoverReal;
    }

    public static int realSelected() {
        return realSelected;
    }

    public static int memberCursor() {
        return memberCursor;
    }

    public static @Nullable RealData realData() {
        return realData;
    }

    public static List<Pin> pins() {
        return Collections.unmodifiableList(PINS);
    }

    /** Where pin {@code i} points in the latest snapshot. */
    public static Pin.Resolution resolve(int i) {
        PathSnapshot s = PathStore.latest();
        return s == null ? new Pin.Resolution(-1, -1, "no prediction yet") : PINS.get(i).resolve(s);
    }

    /** For the client game test: a pin made without a click. */
    public static void addPinForTest(Pin pin) {
        PINS.add(pin);
    }

    public static void clearPins() {
        PINS.clear();
    }

    // ---- every frame (render thread = client thread), from LevelRenderEvents.BEFORE_GIZMOS --------------------------------------

    static void render() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        hoverGhost = null;
        hoverSaved = null;
        hoverReal = null;
        hoverTail = null;
        PathSnapshot s = PathStore.latest();
        if (!PathSettings.showPaths || level == null) {
            return;
        }
        if (PathSettings.hoverDetail.equals("off") && !inspecting()) {
            return;
        }
        boolean here = s != null && level.dimension().identifier().toString().equals(s.dimension());
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 origin = camera.position();
        Vector3fc f = camera.forwardVector();
        Vec3 dir = new Vec3(f.x(), f.y(), f.z());
        double max = Math.min(Picker.MAX_DISTANCE, PathSettings.hoverRange);
        if (!inspecting()) { // a block in the way hides what is behind it
            HitResult block = level.clip(new ClipContext(origin, origin.add(dir.scale(max)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (block.getType() != HitResult.Type.MISS) {
                max = origin.distanceTo(block.getLocation());
            }
        }
        Picker.Hit ghost = here ? Picker.pick(s, origin, dir, max, l -> PathRenderer.lift(l.kind())) : null;
        // the client-side continuations: their points are picked the same way, from the end of their line on
        int tailLine = -1;
        Tails.Job tailJob = null;
        Picker.Hit tail = null;
        if (here) {
            Vec3 d = dir.normalize();
            for (int i = 0; i < s.lines().size(); i++) {
                Tails.Job job = Tails.job(s, i);
                if (job == null || job.points() == 0) {
                    continue;
                }
                PathSnapshot.Line l = s.lines().get(i);
                int m = job.points();
                double[] xyz = new double[3 * (m + 1)];
                int last = l.points() - 1;
                System.arraycopy(l.xyz(), 3 * last, xyz, 0, 3);
                System.arraycopy(job.xyz.elements(), 0, xyz, 3, 3 * m);
                Picker.Hit h = Picker.pickPoints(i, xyz, m + 1, PathRenderer.lift(l.kind()), origin, d, max);
                if (h != null && h.point() >= 1 && (tail == null || h.along() < tail.along())) { // point 0 is the line's own last point
                    tail = new Picker.Hit(i, h.point() - 1, h.along(), h.lateral());
                    tailLine = i;
                    tailJob = job;
                }
            }
        }
        double realDist = Double.MAX_VALUE;
        int realId = -1;
        Vec3 end = origin.add(dir.scale(max));
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof PrimedTnt || e instanceof Projectile)) {
                continue;
            }
            AABB box = e.getBoundingBox().inflate(0.1);
            double dist;
            if (box.contains(origin)) {
                dist = 0.0;
            } else {
                Optional<Vec3> hit = box.clip(origin, end);
                if (hit.isEmpty()) {
                    continue;
                }
                dist = origin.distanceTo(hit.get());
            }
            if (dist < realDist) {
                realDist = dist;
                realId = e.getId();
            }
        }
        // saved paths (session D): from their stored data, in their own dimension
        Picker.Hit savedHit = null;
        obsidivision.ghostpath.saved.SavedPath savedPath = null;
        if (PathSettings.showPaths) {
            String dim = level.dimension().identifier().toString();
            for (obsidivision.ghostpath.saved.SavedPath sp : obsidivision.ghostpath.saved.SavedStore.inDimension(obsidivision.ghostpath.saved.SavedStore.visible(), dim)) {
                Picker.Hit h = Picker.pick(sp.asSnapshot(), origin, dir, max, l -> PathRenderer.lift(l.kind()));
                if (h != null && (savedHit == null || h.along() < savedHit.along())) {
                    savedHit = h;
                    savedPath = sp;
                }
            }
        }
        double nearestOther = Math.min(ghost == null ? Double.MAX_VALUE : ghost.along(), Math.min(tail == null ? Double.MAX_VALUE : tail.along(), realId < 0 ? Double.MAX_VALUE : realDist));
        if (savedHit != null && savedHit.along() < nearestOther) {
            hoverSaved = new SavedHover(savedPath, savedHit.line(), savedHit.point(), savedHit.along());
        } else if (tail != null && (ghost == null || tail.along() < ghost.along()) && (realId < 0 || tail.along() <= realDist)) {
            hoverTail = new TailHover(s, tailLine, tailJob, tail.point(), tail.along());
        } else if (ghost != null && (realId < 0 || ghost.along() <= realDist)) {
            hoverGhost = new GhostHover(s, ghost.line(), ghost.point(), ghost.along());
            if (ghost.line() != cursorLine) {
                cursorLine = ghost.line();
                memberCursor = 0;
            }
        } else if (realId >= 0) {
            hoverReal = new RealHover(realId, realDist);
        }
        drawWorld(s, here);
    }

    private static void drawWorld(@Nullable PathSnapshot s, boolean here) {
        GhostHover hover = hoverGhost;
        if (hover != null) {
            PathSnapshot.Line l = hover.path();
            int p = hover.point();
            Gizmos.cuboid(boxAt(l, p), GizmoStyle.stroke(PathRenderer.color(l.kind()), 3.0F));
            Gizmos.point(pointAt(l, p), PIN_COLOR, 14.0F);
        }
        SavedHover sh = hoverSaved;
        if (sh != null) {
            PathSnapshot.Line l = sh.path();
            Gizmos.cuboid(boxAt(l, sh.point()), GizmoStyle.stroke(PathRenderer.color(l.kind()), 3.0F));
            Gizmos.point(pointAt(l, sh.point()), PIN_COLOR, 14.0F);
        }
        TailHover th = hoverTail;
        if (th != null) {
            PathSnapshot.Line l = th.snapshot().lines().get(th.line());
            double[] b = th.job().box.elements();
            int p = th.point();
            Gizmos.cuboid(new AABB(b[6 * p], b[6 * p + 1], b[6 * p + 2], b[6 * p + 3], b[6 * p + 4], b[6 * p + 5]), GizmoStyle.stroke(PathRenderer.color(l.kind()), 3.0F));
            Gizmos.point(new Vec3(th.job().xyz.getDouble(3 * p), th.job().xyz.getDouble(3 * p + 1) + PathRenderer.lift(l.kind()), th.job().xyz.getDouble(3 * p + 2)), PIN_COLOR, 14.0F);
        }
        RealData data = realData;
        if (hoverReal != null && data != null && data.hoveredId() == hoverReal.id()) {
            EntityReadout m = selectedReal(data);
            Gizmos.cuboid(m.box(), GizmoStyle.stroke(REAL_COLOR, 3.0F));
        }
        if (here && s != null) {
            for (Pin pin : PINS) {
                Pin.Resolution r = pin.resolve(s);
                if (r.found()) {
                    PathSnapshot.Line l = s.lines().get(r.line());
                    AABB box = boxAt(l, r.point());
                    Gizmos.cuboid(box, GizmoStyle.stroke(PIN_COLOR, 2.0F));
                    Gizmos.point(pointAt(l, r.point()), PIN_COLOR, 12.0F);
                    PathRenderer.label("pin t+" + pin.tick(), new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.3, (box.minZ + box.maxZ) / 2), PIN_COLOR);
                }
            }
        }
    }

    private static AABB boxAt(PathSnapshot.Line l, int p) {
        double[] b = l.box();
        return new AABB(b[6 * p], b[6 * p + 1], b[6 * p + 2], b[6 * p + 3], b[6 * p + 4], b[6 * p + 5]);
    }

    private static Vec3 pointAt(PathSnapshot.Line l, int p) {
        return new Vec3(l.xyz()[3 * p], l.xyz()[3 * p + 1] + PathRenderer.lift(l.kind()), l.xyz()[3 * p + 2]);
    }

    private static EntityReadout selectedReal(RealData data) {
        List<EntityReadout> m = data.stack().members();
        return m.get(Math.floorMod(realSelected, m.size()));
    }

    // ---- every client tick ---------------------------------------------------------------------------------------------------

    static void tick(Minecraft mc) {
        if (PathSettings.inspectToggle) {
            while (GhostPathClient.INSPECT.consumeClick()) {
                toggledOn = !toggledOn;
                if (mc.player != null) {
                    Toasts.show("Inspect " + (toggledOn ? "on" : "off"));
                }
            }
        } else {
            toggledOn = false;
            while (GhostPathClient.INSPECT.consumeClick()) {
                // hold mode: the key is read with isDown
            }
        }
        if (PathSettings.pinsClearSerial != seenPinsClear) {
            seenPinsClear = PathSettings.pinsClearSerial;
            PINS.clear();
        }
        RealHover real = hoverReal;
        IntegratedServer server = mc.getSingleplayerServer();
        if (real == null || server == null || mc.level == null) {
            return;
        }
        int id = real.id();
        ResourceKey<Level> dimension = mc.level.dimension();
        // the server thread reads its own entity and publishes the result; the client uses it from the next frame
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            EntityInspector.Stack stack = level == null ? null : EntityInspector.stack(level, id);
            realData = stack == null ? null : new RealData(id, level.getGameTime(), stack);
        });
        RealData data = realData;
        if (id != realLastId) {
            realLastId = id;
            realSelected = 0;
            if (data != null && data.hoveredId() == id) {
                for (int i = 0; i < data.stack().members().size(); i++) {
                    if (data.stack().members().get(i).id() == id) {
                        realSelected = i;
                    }
                }
            }
        }
    }

    // ---- input, from the mixins ----------------------------------------------------------------------------------------------

    /** A left click while the inspect key is held (the click does not attack): pins or unpins the hovered path point. */
    public static boolean onAttack() {
        if (!inspecting()) {
            return false;
        }
        GhostHover hover = hoverGhost;
        Minecraft mc = Minecraft.getInstance();
        if (hover != null && mc.player != null) {
            PathSnapshot.Line l = hover.path();
            Pin pin = pinOf(hover);
            if (!PINS.contains(pin) && PINS.size() >= MAX_PINS) {
                Toasts.show("Too many pins (" + MAX_PINS + ")");
            } else {
                boolean pinned = Pin.toggle(PINS, pin);
                Toasts.show((pinned ? "Pinned " : "Unpinned ") + l.type() + " t+" + pin.tick());
            }
        }
        return true;
    }

    /** The pin for a hovered point: its entity (the stack member the scroll wheel is on) and tick. */
    private static Pin pinOf(GhostHover hover) {
        PathSnapshot.Line l = hover.path();
        return new Pin(l.type(), l.members()[Math.floorMod(memberCursor, l.members().length)], l.ticks()[hover.point()]);
    }

    /** A scroll while the inspect key is held and something is hovered: steps through the stack. Returns whether it was used. */
    public static boolean onScroll(double yOffset) {
        if (!inspecting() || yOffset == 0.0 || hoverGhost == null && hoverReal == null && hoverTail == null) {
            return false;
        }
        int step = yOffset > 0 ? -1 : 1;
        if (hoverGhost != null) {
            memberCursor += step;
        } else if (hoverTail != null) {
            return true; // a continuation is one entity: nothing to step through, but the wheel stays out of the hotbar
        } else {
            realSelected += step;
        }
        return true;
    }

    // ---- HUD -------------------------------------------------------------------------------------------------------------------

    static void hud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!PathSettings.showPaths || mc.level == null) {
            return;
        }
        Font font = mc.font;
        PathSnapshot s = PathStore.latest();
        GhostHover ghost = hoverGhost;
        TailHover tail = hoverTail;
        List<String> hover = hoverPanel(ghost);
        if (PathSettings.hoverDetail.equals("key") && !inspecting()) {
            hover = List.of();
        } else if (!PathSettings.hoverDetail.equals("full") && !hover.isEmpty()) {
            hover = PanelText.compact(hover);
        }
        lastHoverPanel = hover;
        if (!hover.isEmpty() && !(ghost != null && PINS.contains(pinOf(ghost)))) { // a pinned point is shown by its pin
            panelsDrawn++;
            int color = hoverSaved != null ? PathRenderer.color(hoverSaved.path().kind()) : ghost != null ? PathRenderer.color(ghost.path().kind()) : tail != null ? PathRenderer.color(tail.snapshot().lines().get(tail.line()).kind()) : Style.REAL_COLOR;
            Measured ms = measure(font, hover);
            PanelLayout.Rect r = PathSettings.hoverAnchor.equals("corner")
                ? PanelLayout.clamp(g.guiWidth(), g.guiHeight(), 6, 6, ms.w(), ms.h(), 2)
                : PanelLayout.beside(g.guiWidth(), g.guiHeight(), g.guiWidth() / 2, g.guiHeight() / 2, ms.w(), ms.h(), 14, 2); // flips to the left at the right edge
            panel(g, font, ms, r.x(), r.y(), color);
        }
        // pins down the right edge; when the next does not fit above the bottom, a new column to the left
        int y = 6;
        int right = g.guiWidth() - 6;
        int columnWidth = 0;
        for (int i = 0; i < PINS.size(); i++) {
            Measured ms = measure(font, pinPanel(s, PINS.get(i)));
            if (y > 6 && y + ms.h() > g.guiHeight() - 24) {
                right -= columnWidth + 6;
                y = 6;
                columnWidth = 0;
            }
            PanelLayout.Rect r = PanelLayout.clamp(g.guiWidth(), g.guiHeight(), right - ms.w(), y, ms.w(), ms.h(), 2);
            panel(g, font, ms, r.x(), r.y(), Style.PIN_COLOR);
            columnWidth = Math.max(columnWidth, ms.w());
            y += ms.h() + 4;
        }
    }

    private static List<String> hoverPanel(@Nullable GhostHover h) {
        SavedHover sv = hoverSaved;
        if (sv != null) {
            List<String> lines = new ArrayList<>(PanelText.ghost(sv.path(), sv.point(), 0, "saved"));
            lines.add(1, "saved: " + sv.saved().name() + ", " + obsidivision.ghostpath.command.PathCommand.formatDate(sv.saved().date()));
            return lines;
        }
        if (h != null) {
            return PanelText.ghost(h.snapshot(), h.path(), h.point(), memberCursor, h.snapshot().source());
        }
        TailHover t = hoverTail;
        if (t != null) {
            Tails.Job j = t.job();
            int p = t.point();
            double[] pos = {j.xyz.getDouble(3 * p), j.xyz.getDouble(3 * p + 1), j.xyz.getDouble(3 * p + 2)};
            double[] vel = {j.vel.getDouble(3 * p), j.vel.getDouble(3 * p + 1), j.vel.getDouble(3 * p + 2)};
            return PanelText.continued(t.snapshot().lines().get(t.line()).type(), j.ticks.getLong(p), pos, vel, j.done() && p == j.points() - 1, j.endReason());
        }
        RealHover r = hoverReal;
        RealData data = realData;
        if (r != null) {
            if (data == null || data.hoveredId() != r.id()) {
                return List.of("real entity " + r.id(), "reading the server...");
            }
            EntityInspector.Stack st = data.stack();
            int n = st.members().size();
            return PanelText.real(selectedReal(data), Math.floorMod(realSelected, n), n, st.identical());
        }
        return List.of();
    }

    private static List<String> pinPanel(@Nullable PathSnapshot s, Pin pin) {
        List<String> out = new ArrayList<>();
        Pin.Resolution r = s == null ? new Pin.Resolution(-1, -1, "no prediction yet") : pin.resolve(s);
        if (!r.found()) {
            out.add("PIN " + pin.type() + " #" + pin.member() + " t+" + pin.tick());
            out.add(r.problem());
            return out;
        }
        PathSnapshot.Line l = s.lines().get(r.line());
        int cursor = 0;
        for (int i = 0; i < l.members().length; i++) {
            if (l.members()[i] == pin.member()) {
                cursor = i;
            }
        }
        out.add("PIN");
        out.addAll(PanelText.ghost(l, r.point(), cursor, s.source()));
        return out;
    }

    /** A panel measured: its model and sizes (GUI pixels). */
    private record Measured(PanelModel.Model model, int w, int h, int labelW, int intW, int fracW) {
    }

    private static Measured measure(Font font, List<String> lines) {
        Style st = Style.current();
        PanelModel.Model m = PanelModel.of(lines);
        int labelW = 0;
        int intW = 0;
        int fracW = 0;
        int textW = font.width(m.header());
        int rows = 0;
        for (PanelModel.Section sec : m.sections()) {
            textW = Math.max(textW, font.width(sec.title()));
            rows++;
            for (PanelModel.Row r : sec.rows()) {
                rows++;
                if (r.number()) {
                    int at = PanelModel.pointAt(r.value());
                    labelW = Math.max(labelW, font.width(r.label()));
                    intW = Math.max(intW, font.width(r.value().substring(0, at)));
                    fracW = Math.max(fracW, font.width(r.value().substring(at)));
                } else {
                    textW = Math.max(textW, font.width(r.label().isEmpty() ? r.value() : r.label() + "  " + r.value()));
                }
            }
        }
        int numbersW = labelW == 0 ? 0 : labelW + 6 + intW + fracW;
        int w = Math.max(textW, numbersW) + 2 * st.panelPad() + 2;
        int rowH = font.lineHeight + st.panelRowGap();
        int h = 2 * st.panelPad() + font.lineHeight + 3 + rows * rowH + m.sections().size() * 2;
        return new Measured(m, w, h, labelW, intW, fracW);
    }

    private static void panel(GuiGraphicsExtractor g, Font font, Measured ms, int x, int y, int accent) {
        Style st = Style.current();
        int pad = st.panelPad();
        int rowH = font.lineHeight + st.panelRowGap();
        g.fill(x, y, x + ms.w(), y + ms.h(), st.panelBackground());
        if ((st.panelBorder() >>> 24) != 0) {
            g.outline(x, y, ms.w(), ms.h(), st.panelBorder());
        }
        g.fill(x, y, x + 2, y + ms.h(), accent);
        int cy = y + pad;
        int left = x + pad + 2;
        g.text(font, ms.model().header(), left, cy, accent, false);
        cy += font.lineHeight + 3;
        int valueX = left + ms.labelW() + 6;
        for (PanelModel.Section sec : ms.model().sections()) {
            cy += 2;
            g.text(font, sec.title(), left, cy, st.panelLabel(), false);
            cy += rowH;
            for (PanelModel.Row r : sec.rows()) {
                if (r.number()) {
                    int at = PanelModel.pointAt(r.value());
                    String ip = r.value().substring(0, at);
                    g.text(font, r.label(), left, cy, st.panelLabel(), false);
                    g.text(font, ip, valueX + ms.intW() - font.width(ip), cy, st.panelText(), false); // integer parts end at one column: the decimal points line up
                    g.text(font, r.value().substring(at), valueX + ms.intW(), cy, st.panelText(), false);
                } else if (r.label().isEmpty()) {
                    g.text(font, r.value(), left, cy, st.panelText(), false);
                } else {
                    g.text(font, r.label(), left, cy, st.panelLabel(), false);
                    g.text(font, r.value(), left + font.width(r.label()) + 6, cy, st.panelText(), false);
                }
                cy += rowH;
            }
        }
    }
}
