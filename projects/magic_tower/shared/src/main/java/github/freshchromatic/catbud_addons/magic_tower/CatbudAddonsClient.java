package github.freshchromatic.catbud_addons.magic_tower;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CatbudAddonsClient implements ClientModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("Catbud Magic Tower");
    private static final TowerSession SESSION = new TowerSession();
    private static KeyMapping targetLineKey;
    public static TowerSession session() { return SESSION; }
    public static boolean autoPortalRoutesEnabled() {
        return MagicTowerSettings.routes() && MagicTowerSettings.values().bool("routes.auto");
    }
    static Component targetKeyName() { return targetLineKey.getTranslatedKeyMessage(); }
    public static void onWorldBlockChanged(ClientLevel level, BlockPos pos) {
        if (!SESSION.isActive()) return;
        SESSION.targets().onBlockChanged(level, pos);
        if (MagicTowerSettings.routes()) TargetLines.onBlockChanged(pos);
    }
    public static void onWorldChunkChanged(ClientLevel level, int chunkX, int chunkZ) {
        if (!SESSION.isActive()) return;
        SESSION.targets().onChunkChanged(chunkX, chunkZ);
        if (MagicTowerSettings.routes()) TargetLines.onChunkChanged(level, chunkX, chunkZ);
    }
    @Override public void onInitializeClient() {
        MagicTowerSettings.register();
        targetLineKey = ClientPlatform.registerKey("key.catbud_addons.toggle_target_line",
            InputConstants.KEY_GRAVE, Identifier.fromNamespaceAndPath("catbud_addons", "general"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SESSION.tick(client);
            while (targetLineKey.consumeClick()) if (ClientPlatform.screen(client) == null) TargetLines.toggleLookedAt(client, SESSION);
            TargetLines.tick(client, SESSION);
        });
        RouteRenderContext.register(context -> TargetLines.render(context, Minecraft.getInstance(), SESSION));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("catbud_addons", "portal_radar"),
            (graphics, delta) -> RadarHud.render(new github.freshchromatic.catbud_addons.catbud_core.HudGraphics(graphics), Minecraft.getInstance(), SESSION));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("catbud_addons", "chest_esp"),
            (graphics, delta) -> ChestEspRenderer.render(new github.freshchromatic.catbud_addons.catbud_core.HudGraphics(graphics), Minecraft.getInstance(), SESSION));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("catbud_addons", "target_selection"),
            (graphics, delta) -> TargetLines.renderSelectionHint(new github.freshchromatic.catbud_addons.catbud_core.HudGraphics(graphics), Minecraft.getInstance(), SESSION));
    }
}
