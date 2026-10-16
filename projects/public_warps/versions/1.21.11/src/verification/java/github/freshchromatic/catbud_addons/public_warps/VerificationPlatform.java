package github.freshchromatic.catbud_addons.public_warps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Native verification only; excluded from release archives. */
final class VerificationPlatform {

    private static boolean creating;
    private static int     readyTicks;

    static boolean prepareWorld(Minecraft mc) {
        if (mc.level != null) {
            if (mc.player == null || !mc.level.hasChunkAt(mc.player.blockPosition().offset(-15, 0, -15)) || !mc.level
                .hasChunkAt(mc.player.blockPosition().offset(14, 0, 14))) {
                return false;
            }
            return ++readyTicks >= 20;
        }
        if (!creating) {
            creating = true;
            mc.createWorldOpenFlows().createFreshLevel("warp-preview-" + System.currentTimeMillis(),
                new net.minecraft.world.level.LevelSettings("Warp preview verification",
                    net.minecraft.world.level.GameType.CREATIVE, false, net.minecraft.world.Difficulty.NORMAL, true,
                    new net.minecraft.world.level.gamerules.GameRules(
                        net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS),
                    net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                new net.minecraft.world.level.levelgen.WorldOptions(42, false, false),
                net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions,
                new net.minecraft.client.gui.screens.TitleScreen());
        }
        return false;
    }

    static void hover(Minecraft mc, double x, double y) {
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().handle(), x * mc.getWindow().getGuiScale(), y * mc
            .getWindow().getGuiScale());
        try {
            for (String field : new String[] { "xpos", "ypos" }) {
                var f = mc.mouseHandler.getClass().getDeclaredField(field);
                f.setAccessible(true);
                f.setDouble(mc.mouseHandler, (field.equals("xpos") ? x : y) * mc.getWindow().getGuiScale());
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void resizeGui(Minecraft mc) {
        mc.resizeDisplay();
    }

    static boolean loading(Minecraft mc) {
        return mc.getOverlay() != null;
    }

    static void resize(Minecraft mc, int width, int height) {
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), width, height);
    }

    static void capture(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1, c -> {
        });
    }
}
