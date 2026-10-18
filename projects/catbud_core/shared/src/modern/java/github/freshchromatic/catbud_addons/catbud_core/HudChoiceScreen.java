package github.freshchromatic.catbud_addons.catbud_core;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

public final class HudChoiceScreen extends Screen {

    public record Choice(Component name, Runnable action) {}

    private final Screen       parent;
    private final List<Choice> choices;
    private int                offset;

    public HudChoiceScreen(Screen parent, Component title, List<Choice> choices) {
        super(title);
        this.parent = parent;
        this.choices = List.copyOf(choices);
    }

    @Override
    protected void init() {
        int size = Math.min(300, width - 32);
        int count = Math.max(1, (height - 78) / 24);
        offset = Math.clamp(offset, 0, Math.max(0, choices.size() - count));
        for (int i = offset; i < Math.min(choices.size(), offset + count); i++) {
            Choice choice = choices.get(i);
            addRenderableWidget(new PanelButton((width - size) / 2, 36 + (i - offset) * 24, size, 20, choice.name(),
                b -> {
                    choice.action().run();
                    ClientPlatform.show(minecraft, parent);
                }));
        }
        addRenderableWidget(new PanelButton((width - size) / 2, height - 28, size, 20, Component.translatable(
            "gui.back"), b -> onClose()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        graphics.fill(8, 8, width - 8, height - 34, 0xEF080B10);
        graphics.text(font, title, 16, 18, 0xFFFFFFFF);
        super.extractRenderState(graphics, x, y, delta);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        offset = Math.max(0, offset + (vertical < 0 ? 1 : -1));
        rebuildWidgets();
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
