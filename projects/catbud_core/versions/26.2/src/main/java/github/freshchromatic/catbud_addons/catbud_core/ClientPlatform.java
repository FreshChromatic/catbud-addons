package github.freshchromatic.catbud_addons.catbud_core;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
public final class ClientPlatform {
    private ClientPlatform() {}
    public static Screen screen(Minecraft client) { return client.gui.screen(); }
    public static void show(Minecraft client, Screen screen) { client.setScreenAndShow(screen); }
    public static Camera camera(Minecraft client) { return client.gameRenderer.mainCamera(); }
    public static Vec3 position(Camera camera) { return camera.position(); }
    public static float xRot(Camera camera) { return camera.xRot(); }
    public static float yRot(Camera camera) { return camera.yRot(); }
    public static Vector3fc forward(Camera camera) { return camera.forwardVector(); }
    public static Identifier dimension(Minecraft client) { return client.level.dimension().identifier(); }
    public static BossHealthOverlay bossOverlay(Minecraft client) { return client.gui.hud.getBossOverlay(); }
    public static Vec3 project(Minecraft client, Vec3 point) { return client.gameRenderer.projectPointToScreen(point); }
    public static net.minecraft.client.KeyMapping registerKey(String name, int key, net.minecraft.resources.Identifier categoryId) {
        var category = net.minecraft.client.KeyMapping.Category.register(categoryId);
        return net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new net.minecraft.client.KeyMapping(name,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, key, category));
    }
    public static void chat(Minecraft client, net.minecraft.network.chat.Component text) { client.player.sendSystemMessage(text); }
    public static void actionbar(Minecraft client, net.minecraft.network.chat.Component text) { client.gui.hud.setOverlayMessage(text, false); }
}
