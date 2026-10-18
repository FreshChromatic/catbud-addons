package github.freshchromatic.catbud_addons.magic_tower;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import github.freshchromatic.catbud_addons.catbud_core.HudElementConfig;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class TowerHudDefaults {

    private static final Map<String, HudElementConfig> CONFIGS = load();

    private TowerHudDefaults() {
    }

    static HudElementConfig config(String id) {
        HudElementConfig config = CONFIGS.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Missing default tower HUD: " + id);
        }
        return config;
    }

    private static Map<String, HudElementConfig> load() {
        String resource = "/assets/catbud_addons/hud/defaults.json";
        try (var stream = TowerHudDefaults.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing tower HUD defaults resource");
            }
            var document = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject();
            var gson = new Gson();
            Map<String, HudElementConfig> configs = new LinkedHashMap<>();
            document.getAsJsonObject("elements").entrySet().forEach(entry -> configs.put(entry.getKey(), gson.fromJson(
                entry.getValue(), HudElementConfig.class)));
            return Map.copyOf(configs);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read tower HUD defaults", error);
        }
    }
}
