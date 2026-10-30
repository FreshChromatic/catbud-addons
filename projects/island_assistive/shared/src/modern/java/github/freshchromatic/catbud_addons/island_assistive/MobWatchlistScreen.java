package github.freshchromatic.catbud_addons.island_assistive;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MobWatchlistScreen extends MobWatchlistEditorScreen {

    public MobWatchlistScreen(Screen parent, MobWatchlistStore store, Runnable changed) {
        super(parent, store, changed);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        graphics.fill(left - 8, 8, left + panelWidth + 8, height - 4, 0xF0080B10);
        graphics.text(font, title, left, 16, 0xFFFFCC77);
        graphics.text(font, summary(), left, 34, 0xFFDDDDDD);
        graphics.text(font, Component.translatable("island.watchlist.type"), left, height - 124, 0xFFDDDDDD);
        graphics.text(font, Component.translatable("island.watchlist.name"), left + (panelWidth + 8) / 2, height - 124,
            0xFFDDDDDD);
        int lineY = height - 60;
        for (var line : font.split(message(), panelWidth).stream().limit(2).toList()) {
            graphics.text(font, line, left, lineY, 0xFFDDDDDD);
            lineY += 10;
        }
        super.extractRenderState(graphics, x, y, delta);
    }
}
