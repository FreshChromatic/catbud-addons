package github.freshchromatic.catbud_addons.catbud_core;

import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.network.chat.Component;

/** Shared native settings surface. Drafts survive tab changes, resize and Controls. */
public final class SettingsScreen extends Screen {
    private final Screen parent;
    private final Map<String, SettingsValues> drafts = new LinkedHashMap<>();
    private String addonId = "core", category = "general", query = "";
    private Component notice = Component.empty();
    private Rows rows;
    private Button reset;
    private int contentTop;
    private int panelX, panelY, panelWidth, panelHeight;
    public SettingsScreen(Screen parent) {
        super(Component.translatable("screen.catbud_core.settings"));
        this.parent = parent;
        for (var addon : CatbudCore.addons()) {
            drafts.put(addon.id(), addon.store().current());
            if (addon.store().loadWarning() != null) notice = Component.translatable(addon.store().loadWarning());
        }
    }
    private SettingsAddon addon() { return CatbudCore.addons().stream().filter(a -> a.id().equals(addonId)).findFirst().orElse(null); }
    private Component categoryLabel(String id) { return Component.translatable("category.catbud_core." + id); }
    @Override protected void init() {
        panelWidth = Math.min(width - 20, 680);
        panelHeight = Math.min(height - 24, 420);
        panelX = (width - panelWidth) / 2; panelY = (height - panelHeight) / 2;
        int usable = panelWidth - 16, left = panelX + 8;
        var tabs = new ArrayList<String>();
        tabs.add("core");
        CatbudCore.addons().forEach(a -> tabs.add(a.id()));
        int tabsPerRow = Math.max(1, (usable - 140) / 85);
        int selectorWidth = Math.min(100, (usable - 140) / Math.min(tabs.size(), tabsPerRow));
        for (int i = 0; i < tabs.size(); i++) {
            String id = tabs.get(i);
            Component label = id.equals("core") ? Component.translatable("addon.catbud_core.core")
                : CatbudCore.addons().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow().name();
            Button button = addRenderableWidget(new PanelButton(
                left + usable - selectorWidth * Math.min(tabs.size(), tabsPerRow) + i % tabsPerRow * selectorWidth,
                panelY + 7 + i / tabsPerRow * 22, selectorWidth, 20, label, b -> {
                addonId = id; category = "general"; query = ""; rebuildWidgets();
            }, () -> addonId.equals(id), false, null));
        }
        int categoryY = panelY + 14 + ((tabs.size() + tabsPerRow - 1) / tabsPerRow) * 22;
        List<String> categories = addon() == null ? List.of("general", "about") : addon().categories();
        int categoriesPerRow = Math.max(1, usable / 48), tabX = left;
        for (int i = 0; i < categories.size(); i++) {
            String id = categories.get(i);
            var icon = usable > 500 ? switch (id) {
                case "radar" -> "compass_16";
                case "esp" -> "copper_ingot";
                case "routes" -> "ender_pearl";
                case "hints" -> "paper";
                case "status" -> "clock_00";
                default -> null;
            } : null;
            int tabWidth = font.width(categoryLabel(id)) + (icon == null ? 24 : 42);
            if (i > 0 && tabX + tabWidth > left + usable) { categoryY += 23; tabX = left; }
            addRenderableWidget(new PanelButton(tabX, categoryY, tabWidth, 23, categoryLabel(id), b -> {
                category = id; query = ""; rebuildWidgets();
            }, () -> category.equals(id) && query.isEmpty(), false, icon == null ? null : net.minecraft.resources.Identifier.withDefaultNamespace("textures/item/" + icon + ".png")));
            tabX += tabWidth + 1;
        }
        int buttonY = panelY + panelHeight - 28;
        contentTop = categoryY + 23;
        if (addon() != null) {
            EditBox search = addRenderableWidget(new EditBox(font, left + 5, buttonY - 25, usable - 10, 18,
                Component.translatable("screen.catbud_core.search")));
            search.setHint(Component.translatable("screen.catbud_core.search"));
            search.setValue(query);
            search.setResponder(s -> { query = s; populate(); });
        }
        rows = addRenderableWidget(new Rows(usable - 2, Math.max(24, buttonY - (addon() == null ? 7 : 30) - contentTop), contentTop));
        rows.setX(left + 1);
        populate();
        int small = Math.min(96, usable / 4 - 4);
        reset = addRenderableWidget(new PanelButton(left + usable - small, buttonY, small, 20, Component.translatable("screen.catbud_core.reset"), b -> {
            drafts.get(addonId).reset(category); populate();
        }));
        reset.active = addon() != null && !category.equals("status") && query.isBlank();
        addRenderableWidget(new PanelButton(left + small + 4, buttonY, small, 20, Component.translatable("gui.cancel"), b -> onClose()));
        addRenderableWidget(new PanelButton(left + small * 2 + 8, buttonY, small, 20, Component.translatable("screen.catbud_core.apply"), b -> apply()));
        addRenderableWidget(new PanelButton(left, buttonY, small, 20, Component.translatable("gui.done"), b -> { if (apply()) onClose(); }));
    }
    private boolean apply() {
        for (Row row : rows.children()) if (row.invalidNumber) {
            notice = Component.translatable("config.catbud_core.invalid_number"); return false;
        }
        try {
            var core = CatbudCore.coreStore();
            if (!core.exists() || core.loadWarning() != null) core.apply(core.current());
        } catch (IOException e) {
            notice = Component.translatable("config.catbud_core.save_failed", Component.translatable("addon.catbud_core.core")); return false;
        }
        for (var addon : CatbudCore.addons()) {
            var draft = drafts.get(addon.id());
            if (draft.json().equals(addon.store().current().json()) && addon.store().exists() && addon.store().loadWarning() == null) continue;
            try { addon.store().apply(draft); }
            catch (IOException e) {
                notice = Component.translatable("config.catbud_core.save_failed", addon.name());
                org.slf4j.LoggerFactory.getLogger("Catbud Settings").warn("Cannot save " + addon.id(), e);
                return false;
            }
        }
        notice = Component.translatable("config.catbud_core.saved");
        populate();
        return true;
    }
    private void populate() {
        if (rows == null) return;
        double scroll = rows.scrollAmount();
        rows.clear();
        if (addon() == null) {
            if (category.equals("general")) {
                rows.add(new Row(SettingsAddon.StatusRow.info(Component.translatable("key.catbud_core.open_settings"), CatbudCore::openKeyName)));
                rows.add(new Row(new SettingsAddon.StatusRow(Component.translatable("screen.catbud_core.controls"),
                    () -> Component.translatable("screen.catbud_core.open"),
                    () -> minecraft.setScreen(new ControlsScreen(this, minecraft.options)), () -> true, () -> Component.empty())));
                rows.add(new Row(SettingsAddon.StatusRow.info(Component.translatable("screen.catbud_core.language"),
                    () -> Component.translatable("screen.catbud_core.follow_language"))));
            } else {
                rows.add(new Row(SettingsAddon.StatusRow.info(Component.translatable("addon.catbud_core.core"), () -> Component.literal("1.0.0"))));
                for (var a : CatbudCore.addons()) rows.add(new Row(SettingsAddon.StatusRow.info(a.name(), () -> Component.literal(a.version()))));
            }
        } else if (category.equals("status") && query.isBlank()) {
            addon().status().get().forEach(s -> rows.add(new Row(s)));
        } else {
            String needle = query.strip().toLowerCase(Locale.ROOT);
            for (var s : addon().settings()) {
                boolean matches = needle.isEmpty() ? s.category().equals(category)
                    : (s.label(addonId).getString() + " " + s.description(addonId).getString() + " " + s.id())
                        .toLowerCase(Locale.ROOT).contains(needle);
                if (matches) rows.add(new Row(s));
            }
            if (needle.isEmpty()) addon().extraRows().apply(category).forEach(s -> rows.add(new Row(s)));
            if (needle.isEmpty() && category.equals("general")) rows.add(new Row(new SettingsAddon.StatusRow(
                Component.translatable("screen.catbud_core.controls"), () -> Component.translatable("screen.catbud_core.open"),
                () -> minecraft.setScreen(new ControlsScreen(this, minecraft.options)), () -> true,
                () -> Component.translatable("screen.catbud_core.target_controls"))));
            if (rows.children().isEmpty()) rows.add(new Row(SettingsAddon.StatusRow.info(
                Component.translatable("screen.catbud_core.no_results"), Component::empty)));
        }
        rows.setScrollAmount(scroll);
        if (reset != null) reset.active = addon() != null && !category.equals("status") && query.isBlank();
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xB8080B10);
        g.renderOutline(panelX, panelY, panelWidth, panelHeight, 0xFF000000);
        g.renderOutline(panelX + 8, contentTop, panelWidth - 16, panelHeight - (contentTop - panelY) - 7, 0xFF000000);
        super.render(g, mx, my, delta);
        g.drawString(font, "Catbud Addons", panelX + 8, panelY + 12, 0xFFFFFFFF);
        if (!notice.getString().isEmpty()) {
            int nWidth = Math.min(panelWidth - 24, font.width(notice) + 12);
            g.fill(panelX + 12, panelY - 12, panelX + 12 + nWidth, panelY - 1, 0xEF101010);
            g.drawString(font, font.plainSubstrByWidth(notice.getString(), nWidth - 12), panelX + 18, panelY - 11, 0xFFFFFFA0);
        }
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private final class Rows extends ContainerObjectSelectionList<Row> {
        Rows(int width, int height, int top) { super(SettingsScreen.this.minecraft, width, height, top, 23); }
        void clear() { clearEntries(); }
        void add(Row row) { addEntry(row); }
        void locate(Row row) { scrollToEntry(row); }
        @Override public int getRowWidth() { return getWidth() - 18; }
        @Override protected void renderListBackground(GuiGraphics g) {}
        @Override protected void renderListSeparators(GuiGraphics g) {}
    }
    private final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final Setting setting;
        private final SettingsAddon.StatusRow status;
        private final List<AbstractWidget> widgets = new ArrayList<>();
        private final SettingsValues values;
        private Button locate;
        private boolean invalidNumber;
        Row(SettingsAddon.StatusRow status) {
            this.status = status; setting = null; values = null;
            if (status.checked() != null) widgets.add(new PanelButton(0, 0, 180, 18, status.label(),
                b -> status.action().run(), status.checked(), true, null));
            else if (status.action() != null) widgets.add(Button.builder(status.value().get(), b -> {
                status.action().run();
            }).bounds(0, 0, 180, 20).build());
        }
        Row(Setting s) {
            setting = s; status = null; values = drafts.get(addonId);
            if (!query.isBlank()) {
                locate = Button.builder(Component.literal(categoryLabel(s.category()).getString() + " / " + s.label(addonId).getString()), b -> {
                    category = s.category(); query = ""; rebuildWidgets();
                    for (Row r : rows.children()) if (r.setting != null && r.setting.id().equals(s.id())) { rows.locate(r); break; }
                }).bounds(0, 0, 180, 20).build();
                widgets.add(locate);
            }
            switch (s.kind()) {
                case BOOLEAN -> widgets.add(new PanelButton(0, 0, 180, 18, s.label(addonId), b -> {
                    values.set(s, new JsonPrimitive(!values.bool(s.id())));
                }, () -> values.bool(s.id()), true, null));
                case CHOICE -> widgets.add(Button.builder(choiceLabel(values.text(s.id())), b -> {
                    int i = (s.choices().indexOf(values.text(s.id())) + 1) % s.choices().size();
                    values.set(s, new JsonPrimitive(s.choices().get(i))); b.setMessage(choiceLabel(values.text(s.id())));
                }).bounds(0, 0, 180, 20).build());
                case COLOR -> widgets.add(Button.builder(Component.literal(values.text(s.id())), b ->
                    minecraft.setScreen(new ColorScreen(SettingsScreen.this, s.label(addonId), values.text(s.id()), hex -> {
                        values.set(s, new JsonPrimitive(hex)); b.setMessage(Component.literal(hex));
                    }))).bounds(0, 0, 180, 20).build());
                case NUMBER -> {
                    widgets.add(new AbstractSliderButton(0, 0, 125, 20, Component.empty(),
                        (values.number(s.id()) - s.min()) / (s.max() - s.min())) {
                        { updateMessage(); }
                        protected void updateMessage() { setMessage(numberLabel(s, values.number(s.id()))); }
                        @Override public void renderWidget(GuiGraphics g, int mx, int my, float delta) {
                            value = (values.number(s.id()) - s.min()) / (s.max() - s.min());
                            super.renderWidget(g, mx, my, delta);
                        }
                        protected void applyValue() {
                            values.set(s, new JsonPrimitive(s.min() + value * (s.max() - s.min())));
                            updateMessage();
                            ((EditBox) widgets.getLast()).setValue(format(displayNumber(s, values.number(s.id()))));
                        }
                    });
                    EditBox input = new EditBox(font, 0, 0, 55, 20, s.label(addonId));
                    input.setValue(format(displayNumber(s, values.number(s.id()))));
                    input.setResponder(text -> {
                        try {
                            double n = Double.parseDouble(text) / (s.id().endsWith("opacity") ? 100 : 1);
                            boolean valid = Double.isFinite(n) && n >= s.min() && n <= s.max();
                            invalidNumber = !valid;
                            input.setTextColor(valid ? 0xFFE0E0E0 : 0xFFFF7777);
                            if (valid) {
                                values.set(s, new JsonPrimitive(n));
                                AbstractSliderButton slider = (AbstractSliderButton) widgets.get(locate == null ? 0 : 1);
                                // Recreate after finishing text editing; the displayed value remains authoritative.
                                slider.setMessage(numberLabel(s, values.number(s.id())));
                            }
                        } catch (NumberFormatException e) { invalidNumber = true; input.setTextColor(0xFFFF7777); }
                    });
                    widgets.add(input);
                }
            }
            widgets.forEach(w -> w.setTooltip(Tooltip.create(Component.empty().append(s.label(addonId)).append("\n").append(s.description(addonId)))));
        }
        @Override public void renderContent(GuiGraphics g, int mx, int my, boolean hovered, float delta) {
            int x = getContentX(), y = getContentY(), w = getContentWidth();
            Component label = setting == null ? status.label() : setting.label(addonId);
            Component desc = setting == null ? status.explanation().get() : setting.description(addonId);
            boolean active = setting == null ? status.available().getAsBoolean() : setting.available().test(values);
            int controlWidth = Math.min(200, Math.max(130, w / 2));
            boolean checkbox = setting != null && setting.kind() == Setting.Kind.BOOLEAN || status != null && status.checked() != null;
            boolean actionOnly = status != null && status.action() != null && status.value().get().equals(status.label());
            if (locate == null && !checkbox && !actionOnly) g.drawString(font, font.plainSubstrByWidth(label.getString(), w - controlWidth - 8), x, y + 7, active ? 0xFFFFFFFF : 0xFF929292);
            else if (locate != null) { locate.setX(x); locate.setY(y + 2); locate.setWidth(w - controlWidth - 8); locate.render(g, mx, my, delta); }
            int valueX = x + w - controlWidth;
            if (setting == null && status.action() == null) {
                g.drawString(font, font.plainSubstrByWidth(status.value().get().getString(), controlWidth), valueX, y + 9, 0xFFFFFFFF);
            }
            int index = 0;
            for (AbstractWidget widget : widgets) {
                if (widget == locate) continue;
                widget.active = active;
                widget.setY(y + 2);
                widget.setX((checkbox && locate == null) || actionOnly ? x : valueX + (index == 0 ? 0 : controlWidth - 56));
                widget.setWidth((checkbox && locate == null) || actionOnly ? w : setting != null && setting.kind() == Setting.Kind.NUMBER ? (index == 0 ? controlWidth - 60 : 56) : controlWidth);
                if (setting == null) { if (!checkbox) widget.setMessage(status.value().get()); widget.setTooltip(Tooltip.create(Component.empty().append(desc).append("\n").append(status.value().get()))); }
                widget.render(g, mx, my, delta);
                if (setting != null && setting.kind() == Setting.Kind.COLOR) {
                    g.fill(valueX + 4, y + 5, valueX + 17, y + 18, 0xFF000000 | Integer.parseInt(values.text(setting.id()).substring(1), 16));
                    g.renderOutline(valueX + 4, y + 5, 13, 13, 0xFF000000);
                }
                index++;
            }
            if (hovered && mx < valueX) {
                var lines = new ArrayList<net.minecraft.util.FormattedCharSequence>();
                int tooltipWidth = Math.min(250, width - 24);
                lines.addAll(font.split(label, tooltipWidth));
                if (!desc.getString().isBlank()) lines.addAll(font.split(desc, tooltipWidth));
                if (status != null && !status.value().get().getString().isBlank())
                    lines.addAll(font.split(status.value().get(), tooltipWidth));
                g.setTooltipForNextFrame(font, lines, mx, my);
            }
        }
        @Override public List<? extends GuiEventListener> children() { return widgets; }
        @Override public List<? extends NarratableEntry> narratables() { return widgets; }
    }
    private static Component booleanLabel(boolean b) { return Component.translatable(b ? "option.catbud_core.on" : "option.catbud_core.off"); }
    private static Component choiceLabel(String value) { return Component.translatable("choice.catbud_core." + value); }
    private static String format(double value) { return value == Math.rint(value) ? Long.toString(Math.round(value)) : String.format(Locale.ROOT, "%.2f", value); }
    private static double displayNumber(Setting s, double value) { return s.id().endsWith("opacity") ? value * 100 : value; }
    private static Component numberLabel(Setting s, double value) {
        if (s.id().equals("radar.distance") && value == 0) return Component.translatable("option.catbud_core.unlimited");
        if (s.id().endsWith("opacity")) return Component.literal(format(value * 100) + "%");
        if (s.id().endsWith("Scale")) return Component.literal(format(value) + "Ã—");
        return Component.literal(format(value));
    }
}
