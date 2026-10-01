package github.freshchromatic.catbud_addons.catbud_core;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class CatbudCore implements ClientModInitializer {
    private static final Map<String, SettingsAddon> ADDONS = new LinkedHashMap<>();
    private static KeyMapping openKey;
    private static SettingsStore coreStore;
    static SettingsStore coreStore() { return coreStore; }
    public static void register(SettingsAddon addon) {
        if (ADDONS.putIfAbsent(addon.id(), addon) != null) throw new IllegalArgumentException("Duplicate addon " + addon.id());
    }
    public static List<SettingsAddon> addons() { return List.copyOf(ADDONS.values()); }
    public static java.nio.file.Path configPath(String id) {
        if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("Invalid addon id");
        return FabricLoader.getInstance().getConfigDir().resolve("catbud/addons/" + id + ".json");
    }
    public static Screen screen(Screen parent) { return new SettingsScreen(parent); }
    public static Component openKeyName() { return openKey == null ? Component.literal("Z") : openKey.getTranslatedKeyMessage(); }
    @Override public void onInitializeClient() {
        coreStore = new SettingsStore(FabricLoader.getInstance().getConfigDir().resolve("catbud/core.json"), List.of(), v -> {});
        openKey = github.freshchromatic.catbud_addons.catbud_core.ClientPlatform.registerKey("key.catbud_core.open_settings",
            InputConstants.KEY_Z, Identifier.fromNamespaceAndPath("catbud_core", "general"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (ClientPlatform.screen(client) == null && client.player != null) ClientPlatform.show(client, screen(null));
            }
        });
    }
}
