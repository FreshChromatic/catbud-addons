package github.freshchromatic.catbud_addons.catbud_core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

final class SettingsActionScreen extends ConfirmScreen {

    SettingsActionScreen(Screen parent, SettingsAddon.StatusRow row) {
        this(parent, row, Minecraft.getInstance().getConnection());
    }

    private SettingsActionScreen(Screen parent, SettingsAddon.StatusRow row, ClientPacketListener connection) {
        super(accepted -> {
            Minecraft client = Minecraft.getInstance();
            if (accepted && client.getConnection() == connection && row.available().getAsBoolean()) {
                row.action().run();
            }
            ClientPlatform.show(client, parent);
        }, row.confirmation().title(), row.confirmation().message(), row.confirmation().confirmLabel(), Component
            .translatable("gui.cancel"));
    }

    @Override
    protected void init() {
        super.init();
        setInitialFocus(noButton);
    }

    @Override
    public void onClose() {
        callback.accept(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
