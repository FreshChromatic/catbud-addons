package github.freshchromatic.catbud_addons.island_assistive;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MobWatchlistScreen extends MobWatchlistEditorScreen {

    public MobWatchlistScreen(Screen parent, MobWatchlistStore store, Runnable changed) {
        super(parent, store, changed);
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        graphics.fill(left - 8, 8, left + panelWidth + 8, height - 4, 0xF0080B10);
        graphics.drawString(font, title, left, 16, 0xFFFFCC77);
        graphics.drawString(font, summary(), left, 34, 0xFFDDDDDD);
        graphics.drawString(font, Component.translatable("island.watchlist.type"), left, height - 124, 0xFFDDDDDD);
        graphics.drawString(font, Component.translatable("island.watchlist.name"), left + (panelWidth + 8) / 2, height
            - 124, 0xFFDDDDDD);
        int lineY = height - 60;
        for (var line : font.split(message(), panelWidth).stream().limit(2).toList()) {
            graphics.drawString(font, line, left, lineY, 0xFFDDDDDD);
            lineY += 10;
        }
        super.render(graphics, x, y, delta);
    }
}
