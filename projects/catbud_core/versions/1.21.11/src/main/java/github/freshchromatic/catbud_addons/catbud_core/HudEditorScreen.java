package github.freshchromatic.catbud_addons.catbud_core;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

public final class HudEditorScreen extends Screen {

    private static final List<String> SECTIONS = List.of("position", "text", "background", "text_shadow", "box_shadow",
        "padding", "icon", "size", "formatting", "chroma", "actions", "attachment");

    private final Screen                                  parent;
    private final HudEditorSession                        session;
    private final Consumer<Map<String, HudElementConfig>> accepted;
    private final HudInspector                            inspector;

    private final Map<String, HudLayoutEngine.Bounds> bounds          = new LinkedHashMap<>();
    private final Map<String, HudElementConfig>       dragStart       = new LinkedHashMap<>();
    private final Map<String, HudLayoutEngine.Bounds> dragOrigins     = new LinkedHashMap<>();
    private final List<HudInspector.Control>          visibleControls = new ArrayList<>();

    private String      filter, query = "";
    private int         section, propertyOffset, listOffset, leftWidth, rightWidth;
    private boolean     panels = true, preview = true, dragging;
    private int         corner = -1;
    private double      startX, startY;
    private Double      guideX, guideY;
    private EditBox     search;
    private PanelButton undoButton, redoButton, acceptButton, saveButton;
    private int         propertyTotal, propertyVisible, propertiesTop;
    private Component   notice = Component.empty();

    public HudEditorScreen(Screen parent, Map<String, HudElementConfig> initial, String filter, Consumer<Map<String, HudElementConfig>> accepted) {
        super(Component.translatable("hud.core.editor"));
        this.parent = parent;
        this.session = new HudEditorSession(CatbudCore.huds(), initial);
        this.filter = filter;
        this.accepted = accepted;
        inspector = new HudInspector(this, session, this::rebuildWidgets);
        filtered().stream().findFirst().ifPresent(element -> session.select(element.id(), false));
    }

    public HudEditorSession session() {
        return session;
    }

    void anchorSelected(HudPlacement.Anchor anchor) {
        session.beginGesture();
        for (String id : session.selection()) {
            var config = session.config(id);
            if (config.locked()) {
                continue;
            }
            var allBounds = frame().bounds();
            var box = allBounds.get(id);
            if (box == null) {
                continue;
            }
            var anchored = config.placement().withAnchor(anchor);
            var next = HudLayoutEngine.at(anchored, box.x(), box.y(), box, width, height, allBounds.get(anchored
                .relativeTo()), HudRenderer.guiScale());
            session.update(id, c -> c.withPlacement(next));
        }
        session.endGesture();
    }

    private HudFrame frame() {
        Set<String> ids = new HashSet<>(filtered().stream().map(HudElement::id).toList());
        return HudFrame.measure(CatbudCore.huds(), session.snapshot(), e -> ids.contains(e.id()), preview, width,
            height, HudRenderer.guiScale());
    }

    void relativeSelected(String target) {
        var snapshot = session.snapshot();
        var allBounds = frame().bounds();
        session.beginGesture();
        for (String id : session.selection()) {
            var config = session.config(id);
            if (config.locked() || HudLayoutResolver.wouldCycle(snapshot, id, target)) {
                continue;
            }
            var placement = config.placement().withRelative(target, HudPlacement.Anchor.BOTTOM_LEFT);
            if (target.isEmpty()) {
                var box = allBounds.get(id);
                placement = HudLayoutEngine.at(placement, box.x(), box.y(), box, width, height, null, HudRenderer
                    .guiScale());
            } else {
                placement = placement.withAnchor(HudPlacement.Anchor.TOP_LEFT).withOffset(0, 4);
            }
            var next = placement;
            session.update(id, c -> c.withPlacement(next));
        }
        session.endGesture();
    }

    private List<HudElement> filtered() {
        return CatbudCore.huds().elements(session.snapshot()).stream().filter(e -> filter == null || e.addonId().equals(
            filter)).filter(e -> (e.name().getString() + " " + e.id()).toLowerCase(Locale.ROOT).contains(query
                .toLowerCase(Locale.ROOT))).toList();
    }

    @Override
    protected void init() {
        leftWidth = width >= 600 ? 136 : 0;
        rightWidth = Math.min(190, Math.max(126, width / 3));
        visibleControls.clear();
        button(5, 5, Math.min(110, width / 3), "panels", () -> {
            panels = !panels;
            rebuildWidgets();
        });
        button(Math.min(120, width / 3 + 10), 5, Math.min(120, width / 3), preview ? "sample" : "live", () -> {
            preview = !preview;
            rebuildWidgets();
        });
        int footer = height - 25;
        int buttonWidth = Math.min(86, Math.max(40, (width - 36) / 7));
        button(5, footer, buttonWidth, "reset", () -> {
            inspector.resetSelectedInputs();
            session.resetSelected();
            rebuildWidgets();
        });
        undoButton = button(9 + buttonWidth, footer, buttonWidth, "undo", () -> {
            session.undo();
            rebuildWidgets();
        });
        undoButton.active = session.canUndo();
        redoButton = button(13 + buttonWidth * 2, footer, buttonWidth, "redo", () -> {
            session.redo();
            rebuildWidgets();
        });
        redoButton.active = session.canRedo();
        button(width - buttonWidth * 3 - 13, footer, buttonWidth, "cancel", this::onClose);
        saveButton = button(width - buttonWidth * 2 - 9, footer, buttonWidth, "save", this::save);
        acceptButton = button(width - buttonWidth - 5, footer, buttonWidth, "return", () -> {
            if (inspector.valid()) {
                session.endGesture();
                accepted.accept(session.snapshot());
                ClientPlatform.show(minecraft, parent);
            }
        });
        if (!panels) {
            return;
        }
        if (leftWidth > 0) {
            button(9, 34, leftWidth - 10, filter == null ? "all_modules" : "module_filter", () -> {
                filter = filter == null && session.primary() != null ? CatbudCore.huds().get(session.primary(), session
                    .snapshot()).addonId() : null;
                listOffset = 0;
                rebuildWidgets();
            });
            search = addRenderableWidget(new EditBox(font, 9, 58, leftWidth - 10, 18, Component.translatable(
                "hud.core.search")));
            search.setHint(Component.translatable("hud.core.search"));
            search.setValue(query);
            search.setResponder(value -> {
                query = value;
                listOffset = 0;
            });
        }
        int x = width - rightWidth + 4;
        boolean compact = height < 300;
        if (compact) {
            button(x, 35, rightWidth - 12, "section." + SECTIONS.get(section), () -> {
                List<HudChoiceScreen.Choice> choices = new ArrayList<>();
                for (int i = 0; i < SECTIONS.size(); i++) {
                    int target = i;
                    choices.add(new HudChoiceScreen.Choice(Component.translatable("hud.core.section." + SECTIONS.get(
                        i)), () -> {
                            section = target;
                            propertyOffset = 0;
                        }));
                }
                ClientPlatform.show(minecraft, new HudChoiceScreen(this, Component.translatable("hud.core.sections"),
                    choices));
            });
        } else {
            int tabWidth = (rightWidth - 14) / 2;
            for (int i = 0; i < SECTIONS.size(); i++) {
                int target = i;
                var tab = button(x + i % 2 * (tabWidth + 2), 35 + i / 2 * 22, tabWidth, "section." + SECTIONS.get(i),
                    () -> {
                        section = target;
                        propertyOffset = 0;
                        rebuildWidgets();
                    });
                tab.active = section != i;
            }
        }
        int sectionsBottom = compact ? 59 : 35 + (SECTIONS.size() + 1) / 2 * 22;
        if (leftWidth == 0) {
            button(x, sectionsBottom, rightWidth - 12, "elements", () -> {
                List<HudChoiceScreen.Choice> choices = filtered().stream().map(element -> new HudChoiceScreen.Choice(
                    element.name(), () -> session.select(element.id(), false))).toList();
                ClientPlatform.show(minecraft, new HudChoiceScreen(this, Component.translatable("hud.core.elements"),
                    choices));
            });
        }
        List<HudInspector.Control> controls = inspector.controls(SECTIONS.get(section), rightWidth - 12);
        int controlTop = sectionsBottom + (leftWidth == 0 ? 26 : 3);
        int count = Math.max(1, (height - controlTop - 42) / 24);
        propertyTotal = controls.size();
        propertyVisible = count;
        propertiesTop = controlTop;
        propertyOffset = Math.clamp(propertyOffset, 0, Math.max(0, controls.size() - count));
        for (int i = propertyOffset; i < Math.min(controls.size(), propertyOffset + count); i++) {
            var control = controls.get(i);
            control.widget().setX(control.label() == null ? x : width - control.widget().getWidth() - 8);
            control.widget().setY(controlTop + (i - propertyOffset) * 24);
            addRenderableWidget(control.widget());
            visibleControls.add(control);
        }
    }

    private PanelButton button(int x, int y, int width, String key, Runnable action) {
        var button = addRenderableWidget(new PanelButton(x, y, width, 20, Component.translatable("hud.core." + key),
            b -> action.run()));
        button.setTooltip(Tooltip.create(Component.translatable("hud.core." + key)));
        return button;
    }

    @Override
    public void render(GuiGraphics graphics, int mx, int my, float delta) {
        undoButton.active = session.canUndo() && !dragging;
        redoButton.active = session.canRedo() && !dragging;
        acceptButton.active = inspector.valid();
        saveButton.active = inspector.valid() && !dragging;
        bounds.clear();
        var hud = new HudGraphics(graphics);
        var frame = frame();
        HudRenderer.renderFrame(hud, frame);
        for (var entry : frame.entries()) {
            var element = entry.element();
            var config = entry.config();
            var box = entry.bounds();
            bounds.put(element.id(), box);
            if (session.selection().contains(element.id())) {
                outline(graphics, box, config.locked() ? 0xFFFFB347 : 0xFFFFFFFF);
            } else if (!config.enabled()) {
                graphics.renderOutline((int) box.x(), (int) box.y(), (int) box.width(), (int) box.height(), 0xFF777777);
            }
        }
        if (guideX != null) {
            graphics.fill(guideX.intValue(), 0, guideX.intValue() + 1, height, 0xFF66D8E9);
        }
        if (guideY != null) {
            graphics.fill(0, guideY.intValue(), width, guideY.intValue() + 1, 0xFF66D8E9);
        }
        if (panels) {
            if (leftWidth > 0) {
                panel(graphics, 4, 30, leftWidth + 4, height - 30);
                List<HudElement> elements = filtered();
                int count = Math.max(1, (height - 110) / 24);
                listOffset = Math.clamp(listOffset, 0, Math.max(0, elements.size() - count));
                for (int i = listOffset; i < Math.min(elements.size(), listOffset + count); i++) {
                    var element = elements.get(i);
                    int y = 82 + (i - listOffset) * 24;
                    boolean selected = session.selection().contains(element.id());
                    graphics.fill(8, y, leftWidth, y + 21, selected ? 0xFF404040 : 0xA0202020);
                    String prefix = session.config(element.id()).enabled() ? "☑ " : "☐ ";
                    graphics.drawString(font, font.plainSubstrByWidth(prefix + element.name().getString(), leftWidth
                        - 16), 12, y + 6, 0xFFFFFFFF);
                }
            }
            panel(graphics, width - rightWidth, 30, width - 4, height - 30);
            if (propertyTotal > propertyVisible) {
                int track = Math.max(1, height - 46 - propertiesTop);
                int thumb = Math.max(6, track * propertyVisible / propertyTotal);
                int top = propertiesTop + (track - thumb) * propertyOffset / (propertyTotal - propertyVisible);
                graphics.fill(width - 7, propertiesTop, width - 5, propertiesTop + track, 0x60303030);
                graphics.fill(width - 7, top, width - 5, top + thumb, 0xFF888888);
            }
            for (var control : visibleControls) {
                if (control.label() != null) {
                    graphics.drawString(font, font.plainSubstrByWidth(control.label().getString(), rightWidth - control
                        .widget().getWidth() - 18), width - rightWidth + 5, control.widget().getY() + 6, 0xFFFFFFFF);
                }
            }
            if (session.primary() != null) {
                graphics.drawString(font, font.plainSubstrByWidth(CatbudCore.huds().get(session.primary(), session
                    .snapshot()).name().getString(), rightWidth - 16), width - rightWidth + 5, height - 41, 0xFFBBBBBB);
            }
        }
        super.render(graphics, mx, my, delta);
        if (!notice.getString().isEmpty()) {
            graphics.drawString(font, notice, 8, height - 51, 0xFFFFFFA0);
        }
        if (!inspector.valid()) {
            graphics.drawString(font, Component.translatable("config.catbud_core.invalid_number"), 8, height - 39,
                0xFFFF7777);
        }
    }

    private static void panel(GuiGraphics graphics, int x, int y, int right, int bottom) {
        graphics.fill(x, y, right, bottom, 0xE8080B10);
        graphics.renderOutline(x, y, right - x, bottom - y, 0xFF000000);
    }

    private static void outline(GuiGraphics graphics, HudLayoutEngine.Bounds box, int color) {
        graphics.renderOutline((int) box.x() - 1, (int) box.y() - 1, (int) box.width() + 2, (int) box.height() + 2,
            color);
        for (int i = 0; i < 4; i++) {
            int x = (int) (box.x() + (i % 2) * box.width());
            int y = (int) (box.y() + (i / 2) * box.height());
            graphics.fill(x - 2, y - 2, x + 3, y + 3, color);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (panels && leftWidth > 0 && event.x() < leftWidth + 4 && event.y() >= 82 && event.y() < height - 30) {
            int index = listOffset + (int) (event.y() - 82) / 24;
            var elements = filtered();
            if (index < elements.size()) {
                session.select(elements.get(index).id(), event.hasControlDown());
                rebuildWidgets();
            }
            return true;
        }
        if (event.y() < 30 || event.y() > height - 30 || panels && (event.x() > width - rightWidth || leftWidth > 0
            && event.x() < leftWidth + 4)) {
            return true;
        }
        var entries = new ArrayList<>(bounds.entrySet());
        Collections.reverse(entries);
        for (var entry : entries) {
            var box = entry.getValue();
            int hitCorner = cornerAt(box, event.x(), event.y());
            if (box.contains(event.x(), event.y()) || hitCorner >= 0) {
                if (!session.selection().contains(entry.getKey()) || event.hasControlDown()) {
                    session.select(entry.getKey(), event.hasControlDown());
                }
                if (!event.hasControlDown()) {
                    session.selectMergedGroup(entry.getKey());
                }
                dragStart.clear();
                dragOrigins.clear();
                for (String id : session.selection()) {
                    if (!session.config(id).locked()) {
                        dragStart.put(id, session.config(id));
                        dragOrigins.put(id, bounds.get(id));
                    }
                }
                corner = session.selection().size() == 1 ? hitCorner : -1;
                dragging = !dragStart.isEmpty();
                startX = event.x();
                startY = event.y();
                if (dragging) {
                    session.beginGesture();
                }
                rebuildWidgets();
                return true;
            }
        }
        session.selectAll(List.of());
        rebuildWidgets();
        return true;
    }

    private static int cornerAt(HudLayoutEngine.Bounds box, double x, double y) {
        for (int i = 0; i < 4; i++) {
            if (Math.abs(x - box.x() - (i % 2) * box.width()) <= 4 && Math.abs(y - box.y() - (i / 2) * box
                .height()) <= 4) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging) {
            return super.mouseDragged(event, dx, dy);
        }
        guideX = null;
        guideY = null;
        double[] movement = groupMovement(event);
        for (var entry : dragStart.entrySet()) {
            var initial = entry.getValue();
            if (corner < 0 && dragStart.containsKey(initial.placement().relativeTo())) {
                continue;
            }
            var element = CatbudCore.huds().get(entry.getKey(), session.snapshot());
            var layout = HudRenderer.measure(element.content(preview), initial.style());
            var start = dragOrigins.get(entry.getKey());
            double x = start.x() + movement[0];
            double y = start.y() + movement[1];
            HudPlacement placement = initial.placement();
            if (corner >= 0) {
                double fixedX = start.x() + (corner % 2 == 0 ? start.width() : 0);
                double fixedY = start.y() + (corner / 2 == 0 ? start.height() : 0);
                double wantedW = (event.x() - fixedX) * (corner % 2 == 0 ? -1 : 1);
                double wantedH = (event.y() - fixedY) * (corner / 2 == 0 ? -1 : 1);
                double ratio = (wantedW * start.width() + wantedH * start.height()) / (start.width() * start.width()
                    + start.height() * start.height());
                double scale = Math.clamp(initial.placement().scale() * ratio, 0.25, 5);
                placement = placement.withScale(scale);
                double factor = placement.factor(HudRenderer.guiScale());
                double w = layout.width() * scale * factor, h = layout.height() * scale * factor;
                x = fixedX - (corner % 2 == 0 ? w : 0);
                y = fixedY - (corner / 2 == 0 ? h : 0);
                start = new HudLayoutEngine.Bounds(x, y, w, h);
            }
            x = Math.clamp(x, 0, Math.max(0, width - start.width()));
            y = Math.clamp(y, 0, Math.max(0, height - start.height()));
            var next = HudLayoutEngine.at(placement, x, y, start, width, height, bounds.get(placement.relativeTo()),
                HudRenderer.guiScale());
            session.update(entry.getKey(), c -> c.withPlacement(next));
        }
        return true;
    }

    private double[] groupMovement(MouseButtonEvent event) {
        double dx = event.x() - startX, dy = event.y() - startY;
        if (corner >= 0) {
            return new double[] { dx, dy };
        }
        double left = dragOrigins.values().stream().mapToDouble(HudLayoutEngine.Bounds::x).min().orElse(0);
        double top = dragOrigins.values().stream().mapToDouble(HudLayoutEngine.Bounds::y).min().orElse(0);
        double right = dragOrigins.values().stream().mapToDouble(b -> b.x() + b.width()).max().orElse(width);
        double bottom = dragOrigins.values().stream().mapToDouble(b -> b.y() + b.height()).max().orElse(height);
        double x = left + dx, y = top + dy;
        if (!event.hasAltDown()) {
            x = snap(x, right - left, true);
            y = snap(y, bottom - top, false);
        }
        x = Math.clamp(x, 0, Math.max(0, width - right + left));
        y = Math.clamp(y, 0, Math.max(0, height - bottom + top));
        return new double[] { x - left, y - top };
    }

    private double snap(double position, double size, boolean horizontal) {
        List<Double> lines = new ArrayList<>(List.of(0.0, (horizontal ? width : height) / 2.0, (double) (horizontal
            ? width
            : height)));
        bounds.forEach((id, box) -> {
            if (!session.selection().contains(id)) {
                double start = horizontal ? box.x() : box.y();
                double length = horizontal ? box.width() : box.height();
                lines.add(start);
                lines.add(start + length / 2);
                lines.add(start + length);
            }
        });
        double closest = 4;
        double result = position;
        for (double line : lines) {
            for (double offset : new double[] { 0, size / 2, size }) {
                double distance = Math.abs(position + offset - line);
                if (distance < closest) {
                    closest = distance;
                    result = line - offset;
                    if (horizontal) {
                        guideX = line;
                    } else {
                        guideY = line;
                    }
                }
            }
        }
        return result;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            session.endGesture();
            guideX = null;
            guideY = null;
            rebuildWidgets();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (panels && x > width - rightWidth) {
            propertyOffset = Math.max(0, propertyOffset + (vertical < 0 ? 1 : -1));
            rebuildWidgets();
            return true;
        }
        if (panels && leftWidth > 0 && x < leftWidth + 4) {
            listOffset = Math.max(0, listOffset + (vertical < 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (getFocused() instanceof EditBox input && input.isFocused()) {
            return super.keyPressed(event);
        }
        if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
            session.selectAll(filtered().stream().map(HudElement::id).toList());
            rebuildWidgets();
            return true;
        }
        if (event.hasControlDown() && (event.key() == InputConstants.KEY_Z || event.key() == InputConstants.KEY_Y)) {
            if (event.key() == InputConstants.KEY_Y || event.hasShiftDown()) {
                session.redo();
            } else {
                session.undo();
            }
            rebuildWidgets();
            return true;
        }
        if (event.isLeft() || event.isRight() || event.isUp() || event.isDown()) {
            int step = event.hasShiftDown() ? 10 : 1;
            session.updateSelected(c -> c.locked() || session.selection().contains(c.placement().relativeTo()) ? c
                : c.withPlacement(c.placement().withOffset(c.placement().x() + (event.isRight() ? step : 0) - (event
                    .isLeft() ? step : 0), c.placement().y() + (event.isDown() ? step : 0) - (event.isUp() ? step
                        : 0))));
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    private void save() {
        if (!inspector.valid()) {
            return;
        }
        session.endGesture();
        try {
            CatbudCore.hudStore().apply(session.snapshot());
            accepted.accept(session.snapshot());
            session.markSaved();
            notice = Component.translatable("config.catbud_core.saved");
        } catch (java.io.IOException error) {
            notice = Component.translatable("config.catbud_core.save_failed", title);
            org.slf4j.LoggerFactory.getLogger("Catbud HUD").warn("Cannot save HUD", error);
        }
    }

    @Override
    public void onClose() {
        dragging = false;
        session.endGesture();
        List<Component> changes = new ArrayList<>(HudChanges.describe(CatbudCore.huds(), session.entry(), session
            .snapshot()));
        changes.addAll(inspector.invalidChanges());
        if (changes.isEmpty()) {
            ClientPlatform.show(minecraft, parent);
        } else {
            ClientPlatform.show(minecraft, new DiscardChangesScreen(this, changes, () -> ClientPlatform.show(minecraft,
                parent)));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
