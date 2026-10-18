package github.freshchromatic.catbud_addons.catbud_core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

final class UpdateVerificationPlatform {

    static void size(Minecraft client, int width, int height) {
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(client.getWindow().handle(), width, height);
    }

    private UpdateVerificationPlatform() {
    }

    static boolean ready(Minecraft client) {
        return client.isGameLoadFinished() && client.getOverlay() == null;
    }

    static void resize(Minecraft client) {
        client.resizeGui();
    }

    static void capture(Minecraft client, String name) {
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), 1, message -> {
        });
    }
}
