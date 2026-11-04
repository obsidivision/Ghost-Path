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
import obsidivision.ghostpath.inspect.PanelText;
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
    private static final int PANEL_BACKGROUND = 0xC0101018;
    private static final int PANEL_TEXT = 0xFFE8E8E8;
    private static final int REAL_COLOR = 0xFFFFFF40;
    private static final int PIN_COLOR = 0xFFFFFFFF;

    /**
     * A hovered path point. It carries the snapshot it was found in: the indices mean nothing in a newer one (a rerun can publish
     * between the frame that hovered and the HUD draw, a click or a scroll).
     */
    public record GhostHover(PathSnapshot snapshot, int line, int point, double along) {
        PathSnapshot.Line path() {
            return this.snapshot.lines().get(this.line);
        }
    }

    /** A hovered real entity (the client's id, which is the server's). */
    public record RealHover(int id, double along) {
    }

    /** What the server said about the hovered real entity: its stack, at {@code serverTick}. */
    public record RealData(int hoveredId, long serverTick, EntityInspector.Stack stack) {
    }

    private static @Nullable GhostHover hoverGhost;
    private static @Nullable RealHover hoverReal;
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
        return GhostPathClient.INSPECT.isDown() && PathSettings.showPaths && mc.gui.screen() == null && mc.level != null;
    }

    public static @Nullable GhostHover hoveredGhost() {
        return hoverGhost;
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

    public static void clearPins() {
        PINS.clear();
    }

    // ---- every frame (render thread = client thread), from LevelRenderEvents.BEFORE_GIZMOS --------------------------------------

    static void render() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        hoverGhost = null;
        hoverReal = null;
        PathSnapshot s = PathStore.latest();
        if (!PathSettings.showPaths || level == null) {
            return;
        }
        boolean here = s != null && level.dimension().identifier().toString().equals(s.dimension());
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 origin = camera.position();
        Vector3fc f = camera.forwardVector();
        Vec3 dir = new Vec3(f.x(), f.y(), f.z());
        double max = Picker.MAX_DISTANCE;
        if (!inspecting()) { // a block in the way hides what is behind it
            HitResult block = level.clip(new ClipContext(origin, origin.add(dir.scale(max)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (block.getType() != HitResult.Type.MISS) {
                max = origin.distanceTo(block.getLocation());
            }
        }
        Picker.Hit ghost = here ? Picker.pick(s, origin, dir, max, l -> PathRenderer.lift(l.kind())) : null;
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
        if (ghost != null && (realId < 0 || ghost.along() <= realDist)) {
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
                mc.player.sendOverlayMessage(Component.literal("Too many pins (" + MAX_PINS + ")"));
            } else {
                boolean pinned = Pin.toggle(PINS, pin);
                mc.player.sendOverlayMessage(Component.literal((pinned ? "Pinned " : "Unpinned ") + l.type() + " t+" + pin.tick()));
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
        if (!inspecting() || yOffset == 0.0 || hoverGhost == null && hoverReal == null) {
            return false;
        }
        int step = yOffset > 0 ? -1 : 1;
        if (hoverGhost != null) {
            memberCursor += step;
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
        List<String> hover = hoverPanel(ghost);
        lastHoverPanel = hover;
        if (!hover.isEmpty() && !(ghost != null && PINS.contains(pinOf(ghost)))) { // a pinned point is shown by its pin
            panelsDrawn++;
            int color = ghost != null ? PathRenderer.color(ghost.path().kind()) : REAL_COLOR;
            panel(g, font, g.guiWidth() / 2 + 14, g.guiHeight() / 2 - (hover.size() * 10 + 6) / 2, hover, color);
        }
        // pins down the right edge; when the next does not fit above the bottom, a new column to the left
        int y = 6;
        int right = g.guiWidth() - 6;
        int columnWidth = 0;
        for (int i = 0; i < PINS.size(); i++) {
            List<String> lines = pinPanel(s, PINS.get(i));
            int w = width(font, lines);
            int h = lines.size() * 10 + 6;
            if (y > 6 && y + h > g.guiHeight() - 24) {
                right -= columnWidth + 6;
                y = 6;
                columnWidth = 0;
            }
            panel(g, font, right - w, y, lines, PIN_COLOR);
            columnWidth = Math.max(columnWidth, w);
            y += h + 4;
        }
    }

    private static List<String> hoverPanel(@Nullable GhostHover h) {
        if (h != null) {
            return PanelText.ghost(h.path(), h.point(), memberCursor, h.snapshot().source());
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

    private static int width(Font font, List<String> lines) {
        int w = 0;
        for (String line : lines) {
            w = Math.max(w, font.width(line));
        }
        return w + 8;
    }

    private static void panel(GuiGraphicsExtractor g, Font font, int x, int y, List<String> lines, int accent) {
        int w = width(font, lines);
        int h = lines.size() * 10 + 6;
        x = Math.max(2, Math.min(x, g.guiWidth() - w - 2));
        y = Math.max(2, Math.min(y, g.guiHeight() - h - 2));
        g.fill(x, y, x + w, y + h, PANEL_BACKGROUND);
        g.fill(x, y, x + 2, y + h, accent);
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), x + 5, y + 4 + i * 10, i == 0 ? accent : PANEL_TEXT, false);
        }
    }
}
