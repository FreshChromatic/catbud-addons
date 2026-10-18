package github.freshchromatic.catbud_addons.catbud_core;

import github.freshchromatic.catbud_addons.update.Notice;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.net.URI;
import java.util.List;

public final class NoticeScreen extends Screen {

    private final Screen                parent;
    private final Notice                notice;
    private List<FormattedCharSequence> lines = List.of();
    private int                         offset;
    private boolean                     shown;

    public NoticeScreen(Screen parent, Notice notice) {
        super(Component.literal(notice.title()));
        this.parent = parent;
        this.notice = notice;
    }

    @Override
    protected void init() {
        lines = font.split(Component.literal(notice.body()), Math.max(100, Math.min(580, width - 48)));
        int size = Math.min(150, (width - 36) / 2);
        addRenderableWidget(new PanelButton(width / 2 - size - 4, height - 32, size, 20, Component.translatable(
            "gui.back"), button -> onClose()));
        if (safeLink(notice.link())) {
            addRenderableWidget(new PanelButton(width / 2 + 4, height - 32, size, 20, Component.translatable(
                "updates.core.github"), button -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(notice.link()))));
        }
    }

    private static boolean safeLink(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getScheme().equals("https") && uri.getUserInfo() == null && uri.getPort() == -1 && List.of(
                "github.com", "catbud.net", "catrealm.net", "api.cbwp.workers.dev").contains(uri.getHost());
        } catch (RuntimeException error) {
            return false;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        int left = Math.max(16, (width - 600) / 2);
        graphics.fill(left - 8, 12, width - left + 8, height - 40, 0xF0080B10);
        graphics.drawString(font, title, left, 22, 0xFFFFCC77);
        int count = Math.max(1, (height - 100) / 12);
        offset = Math.clamp(offset, 0, Math.max(0, lines.size() - count));
        for (int i = offset; i < Math.min(lines.size(), offset + count); i++) {
            graphics.drawString(font, lines.get(i), left, 48 + (i - offset) * 12, 0xFFDDDDDD);
        }
        super.render(graphics, x, y, delta);
        if (!shown) {
            shown = true;
            CatbudCore.updates().coordinator().notifications().read(notice);
        }
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
