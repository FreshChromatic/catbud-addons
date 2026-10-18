package github.freshchromatic.catbud_addons.catbud_core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

final class UpdateVerificationPlatform {

    static void size(Minecraft client, int width, int height) {
        client.getWindow().setWindowed(width, height);
    }

    private UpdateVerificationPlatform() {
    }

    static boolean ready(Minecraft client) {
        return client.isGameLoadFinished() && client.gui.overlay() == null;
    }

    static void resize(Minecraft client) {
        client.resizeGui();
    }

    static void capture(Minecraft client, String name) {
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, message -> {
        });
    }
}
