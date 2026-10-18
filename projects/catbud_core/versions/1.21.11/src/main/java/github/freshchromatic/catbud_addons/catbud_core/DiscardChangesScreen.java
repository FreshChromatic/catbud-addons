package github.freshchromatic.catbud_addons.catbud_core;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;

public final class DiscardChangesScreen extends Screen {

    private final Screen                      parent;
    private final List<Component>             changes;
    private final Runnable                    discard;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private int                               offset;

    public DiscardChangesScreen(Screen parent, List<Component> changes, Runnable discard) {
        super(Component.translatable("hud.core.unsaved"));
        this.parent = parent;
        this.changes = List.copyOf(changes);
        this.discard = discard;
    }

    @Override
    protected void init() {
        lines.clear();
        for (Component change : changes) {
            lines.addAll(font.split(change, Math.max(100, Math.min(580, width - 48))));
        }
        int size = Math.min(150, (width - 36) / 2);
        addRenderableWidget(new PanelButton(width / 2 - size - 4, height - 32, size, 20, Component.translatable(
            "hud.core.keep_editing"), b -> onClose()));
        addRenderableWidget(new PanelButton(width / 2 + 4, height - 32, size, 20, Component.translatable(
            "hud.core.discard"), b -> discard.run()));
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        int left = Math.max(16, (width - 600) / 2);
        graphics.fill(left - 8, 12, width - left + 8, height - 40, 0xF0080B10);
        graphics.drawString(font, title, left, 22, 0xFFFFCC77);
        graphics.drawString(font, Component.translatable("hud.core.discard_help"), left, 39, 0xFFFFFFFF);
        int count = Math.max(1, (height - 108) / 12);
        offset = Math.clamp(offset, 0, Math.max(0, lines.size() - count));
        for (int i = offset; i < Math.min(lines.size(), offset + count); i++) {
            graphics.drawString(font, lines.get(i), left, 60 + (i - offset) * 12, 0xFFDDDDDD);
        }
        if (lines.size() > count) {
            graphics.drawString(font, Component.translatable("hud.core.change_page", offset + 1, Math.min(lines.size(),
                offset + count), lines.size()), left, height - 52, 0xFFAAAAAA);
        }
        super.render(graphics, x, y, delta);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        offset = Math.max(0, offset + (vertical < 0 ? 3 : -3));
        return true;
    }

    @Override
    public void onClose() {
        ClientPlatform.show(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
