package github.freshchromatic.catbud_addons.catbud_core;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** RGB sliders plus validated hexadecimal input; Cancel never mutates the parent draft. */
final class ColorScreen extends Screen {
    private final Screen parent;
    private final Consumer<String> accepted;
    private int rgb;
    private EditBox hex;
    private Button done;
    private int left, top, panelWidth;
    ColorScreen(Screen parent, Component title, String initial, Consumer<String> accepted) {
        super(title); this.parent = parent; this.accepted = accepted;
        rgb = Integer.parseInt(initial.substring(1), 16);
    }
    private String value() { return String.format(java.util.Locale.ROOT, "#%06X", rgb); }
    @Override protected void init() {
        panelWidth = Math.min(width - 32, 280);
        left = (width - panelWidth) / 2 + 12; top = Math.max(30, height / 2 - 65);
        int controlsWidth = panelWidth - 24;
        for (int i = 0; i < 3; i++) {
            int shift = 16 - i * 8;
            String channel = new String[] {"R", "G", "B"}[i];
            addRenderableWidget(new AbstractSliderButton(left, top + i * 25, controlsWidth, 20, Component.empty(), (rgb >> shift & 255) / 255.0) {
                { updateMessage(); }
                protected void updateMessage() { setMessage(Component.literal(channel + ": " + Math.round(value * 255))); }
                @Override public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
                    value = (rgb >> shift & 255) / 255.0; updateMessage();
                    super.extractWidgetRenderState(g, mx, my, delta);
                }
                protected void applyValue() {
                    rgb = rgb & ~(255 << shift) | (int) Math.round(value * 255) << shift;
                    updateMessage(); hex.setValue(value());
                }
            });
        }
        hex = addRenderableWidget(new EditBox(font, left + 40, top + 78, controlsWidth - 40, 20, Component.translatable("screen.catbud_core.hex")));
        hex.setMaxLength(7); hex.setValue(value());
        hex.setResponder(s -> {
            boolean valid = s.matches("#[0-9a-fA-F]{6}");
            if (done != null) done.active = valid;
            hex.setTextColor(valid ? 0xFFE0E0E0 : 0xFFFF7777);
            if (valid) rgb = Integer.parseInt(s.substring(1), 16);
        });
        addRenderableWidget(new PanelButton(left + controlsWidth / 2 + 4, top + 125, controlsWidth / 2 - 4, 20, Component.translatable("gui.cancel"), b -> onClose()));
        done = addRenderableWidget(new PanelButton(left, top + 125, controlsWidth / 2 - 4, 20, Component.translatable("gui.done"), b -> {
            accepted.accept(value()); onClose();
        }));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(left - 12, top - 27, left + panelWidth - 12, top + 157, 0xEE080B10);
        g.outline(left - 12, top - 27, panelWidth, 184, 0xFF000000);
        super.extractRenderState(g, mx, my, delta);
        g.text(font, title, left, top - 17, 0xFFFFFFFF);
        g.text(font, "HEX", left, top + 84, 0xFFFFFFFF);
        g.fill(left, top + 104, left + panelWidth - 24, top + 118, 0xFF000000 | rgb);
        g.outline(left, top + 104, panelWidth - 24, 14, 0xFF000000);
        if (!done.active) g.text(font, "#RRGGBB", left, top + 105, 0xFFFFFFFF);
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
