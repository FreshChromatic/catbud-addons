package github.freshchromatic.catbud_addons.catbud_core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HudRegistry {

    private final Map<String, HudElement> elements = new LinkedHashMap<>();

    public void register(HudElement element) {
        if (!element.id().matches("[a-z0-9_]+:[a-z0-9_./-]+") || elements.putIfAbsent(element.id(), element) != null) {
            throw new IllegalArgumentException("Invalid or duplicate HUD element: " + element.id());
        }
    }

    public List<HudElement> elements() {
        return List.copyOf(elements.values());
    }

    public List<HudElement> elements(Map<String, HudElementConfig> configs) {
        var result = new java.util.ArrayList<>(elements.values());
        configs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!elements.containsKey(entry.getKey()) && !entry.getValue().deleted()) {
                HudElement source = elements.get(entry.getValue().sourceId());
                if (source != null) {
                    result.add(new HudCopyElement(entry.getKey(), source));
                }
            }
        });
        return List.copyOf(result);
    }

    public HudElement get(String id, Map<String, HudElementConfig> configs) {
        if (elements.containsKey(id)) {
            return elements.get(id);
        }
        HudElementConfig config = configs.get(id);
        if (config != null && elements.containsKey(config.sourceId())) {
            return new HudCopyElement(id, elements.get(config.sourceId()));
        }
        throw new IllegalArgumentException("Unknown HUD element: " + id);
    }

    public HudElement get(String id) {
        HudElement element = elements.get(id);
        if (element == null) {
            throw new IllegalArgumentException("Unknown HUD element: " + id);
        }
        return element;
    }
}
