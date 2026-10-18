package github.freshchromatic.catbud_addons.catbud_core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HudConfigStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path                    path;
    private JsonObject                    document = new JsonObject();
    private Map<String, HudElementConfig> current  = Map.of();
    private boolean                       warning;

    public HudConfigStore(Path path) {
        this.path = path;
        try {
            if (Files.exists(path)) {
                document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (document.has("configVersion") && document.get("configVersion").getAsInt() != 1) {
                    throw new IOException("Unsupported HUD configuration version");
                }
                Map<String, HudElementConfig> loaded = new LinkedHashMap<>();
                if (document.has("elements")) {
                    for (var entry : document.getAsJsonObject("elements").entrySet()) {
                        JsonObject defaults = GSON.toJsonTree(new HudElementConfig(true, false, null, null))
                            .getAsJsonObject();
                        JsonObject source = entry.getValue().getAsJsonObject();
                        HudElementConfig config = GSON.fromJson(merge(defaults, source), HudElementConfig.class);
                        if (config == null) {
                            throw new IOException("Missing HUD configuration: " + entry.getKey());
                        }
                        loaded.put(entry.getKey(), config);
                    }
                }
                current = Map.copyOf(loaded);
            }
        } catch (Exception error) {
            warning = true;
            LoggerFactory.getLogger("Catbud HUD").warn("Cannot read HUD configuration {}", path, error);
            try {
                Files.copy(path, path.resolveSibling(path.getFileName() + ".invalid-" + System.currentTimeMillis()));
            } catch (IOException backupError) {
                LoggerFactory.getLogger("Catbud HUD").warn("Cannot back up HUD configuration", backupError);
            }
            document = new JsonObject();
        }
    }

    public boolean warning() {
        return warning;
    }

    public Map<String, HudElementConfig> current() {
        return current;
    }

    public HudElementConfig config(HudElement element) {
        return current.getOrDefault(element.id(), element.defaults());
    }

    public void apply(Map<String, HudElementConfig> draft) throws IOException {
        Map<String, HudElementConfig> validated = Map.copyOf(draft);
        JsonObject next = document.deepCopy();
        next.addProperty("configVersion", 1);
        JsonObject elements = next.has("elements") ? next.getAsJsonObject("elements").deepCopy() : new JsonObject();
        current.forEach((id, config) -> {
            if (config.sourceId() != null && !validated.containsKey(id) && validated.containsKey(config.sourceId())) {
                elements.add(id, merge(elements.getAsJsonObject(id), GSON.toJsonTree(config.asDeleted())
                    .getAsJsonObject()));
            }
        });
        validated.forEach((id, config) -> {
            JsonObject existing = elements.has(id) ? elements.getAsJsonObject(id) : new JsonObject();
            elements.add(id, merge(existing, GSON.toJsonTree(config).getAsJsonObject()));
        });
        next.add("elements", elements);
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".catbud-hud-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(next));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        document = next;
        current = validated;
        warning = false;
    }

    private static JsonObject merge(JsonObject base, JsonObject changes) {
        JsonObject result = base.deepCopy();
        changes.entrySet().forEach(entry -> {
            var previous = result.get(entry.getKey());
            var next = entry.getValue();
            if (previous != null && previous.isJsonObject() && next.isJsonObject()) {
                result.add(entry.getKey(), merge(previous.getAsJsonObject(), next.getAsJsonObject()));
            } else {
                result.add(entry.getKey(), next.deepCopy());
            }
        });
        return result;
    }
}
