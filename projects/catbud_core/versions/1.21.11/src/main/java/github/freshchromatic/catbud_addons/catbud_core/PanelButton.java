package github.freshchromatic.catbud_addons.catbud_core;

import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Shared framed controls, with the checkbox and tab grammar of EntityCulling. */
final class PanelButton extends Button {
    private final BooleanSupplier selected;
    private final boolean checkbox;
    private final Identifier icon;
    private final Component label;
    PanelButton(int x, int y, int width, int height, Component label, OnPress press) {
        this(x, y, width, height, label, press, null, false, null);
    }
    PanelButton(int x, int y, int width, int height, Component label, OnPress press,
                BooleanSupplier selected, boolean checkbox, Identifier icon) {
        super(x, y, width, height, label, press, DEFAULT_NARRATION);
        this.label = label; this.selected = selected; this.checkbox = checkbox; this.icon = icon;
    }
    @Override protected void renderContents(GuiGraphics g, int mx, int my, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean focused = isHoveredOrFocused(), checked = selected != null && selected.getAsBoolean();
        int color = active ? 0xFFFFFFFF : 0xFF929292;
        if (checkbox) {
            frame(g, x, y, 18, 18, active && focused ? 0xFF555555 : 0xFF303030, focused && active);
            int ink = active ? 0xFFF0F0F0 : 0xFF777777;
            if (checked) {
                for (int i = 0; i < 5; i++) g.fill(x + 3 + i, y + 8 + i, x + 5 + i, y + 10 + i, ink);
                for (int i = 0; i < 9; i++) g.fill(x + 7 + i, y + 12 - i, x + 9 + i, y + 14 - i, ink);
            } else {
                for (int i = 0; i < 10; i++) {
                    g.fill(x + 4 + i, y + 4 + i, x + 5 + i, y + 5 + i, ink);
                    g.fill(x + 13 - i, y + 4 + i, x + 14 - i, y + 5 + i, ink);
                }
            }
            g.drawString(Minecraft.getInstance().font, Minecraft.getInstance().font.plainSubstrByWidth(label.getString(), w - 23), x + 23, y + 5, color);
        } else {
            frame(g, x, y, w, h, selected != null ? (checked ? 0xD01A1A1A : 0xA0080808)
                : focused && active ? 0xFF555555 : 0xFF303030, focused && active);
            int textX = x + w / 2;
            if (icon != null) { g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, icon,
                x + 5, y + (h - 16) / 2, 0, 0, 16, 16, 16, 16); textX += 9; }
            g.drawCenteredString(Minecraft.getInstance().font, getMessage(), textX, y + (h - 8) / 2,
                selected != null && !checked && !focused ? 0xFFAAAAAA : color);
        }
    }
    private static void frame(GuiGraphics g, int x, int y, int w, int h, int fill, boolean focus) {
        g.fill(x, y, x + w, y + h, fill);
        g.renderOutline(x, y, w, h, focus ? 0xFFEEEEEE : 0xFF000000);
        g.hLine(x + 1, x + w - 2, y + 1, 0xFF555555);
    }
    @Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
        return checkbox ? Component.empty().append(label).append(": ").append(Component.translatable(
            selected.getAsBoolean() ? "option.catbud_core.on" : "option.catbud_core.off")) : super.createNarrationMessage();
    }
}
