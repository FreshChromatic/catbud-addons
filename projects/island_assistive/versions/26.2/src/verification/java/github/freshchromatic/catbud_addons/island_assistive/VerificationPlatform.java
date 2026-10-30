package github.freshchromatic.catbud_addons.island_assistive;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.glfw.GLFW;

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
            String miningWorld = System.getProperty("catbud.miningWorld");
            if (miningWorld != null) {
                mc.createWorldOpenFlows().openWorld(miningWorld, () -> mc.setScreenAndShow(new TitleScreen()));
                return false;
            }
            mc.createWorldOpenFlows().createFreshLevel("island-assistive-" + System.currentTimeMillis(),
                new LevelSettings("Island Assistive verification", GameType.CREATIVE,
                    LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT), new WorldOptions(
                        42, false, false), WorldPresets::createNormalWorldDimensions, new TitleScreen());
        }
        return false;
    }

    static Object chat(Minecraft mc) {
        return mc.gui.hud.getChat();
    }

    static void resizeGui(Minecraft mc) {
        mc.resizeGui();
    }

    static boolean loading(Minecraft mc) {
        return mc.gui.overlay() != null;
    }

    static void resize(Minecraft mc, int width, int height) {
        GLFW.glfwSetWindowSize(mc.getWindow().handle(), width, height);
    }

    static void capture(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, c -> {
        });
    }
}
