package github.freshchromatic.catbud_addons.catbud_core;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class HudLayoutResolver {

    public record Size(double width, double height) {}

    private final Map<String, HudElementConfig>       configs;
    private final Map<String, Size>                   sizes;
    private final double                              width, height, guiScale;
    private final Map<String, HudLayoutEngine.Bounds> resolved = new LinkedHashMap<>();

    public HudLayoutResolver(Map<String, HudElementConfig> configs, Map<String, Size> sizes, double width, double height, double guiScale) {
        this.configs = configs;
        this.sizes = sizes;
        this.width = width;
        this.height = height;
        this.guiScale = guiScale;
    }

    public Map<String, HudLayoutEngine.Bounds> resolve() {
        for (String id : sizes.keySet()) {
            resolve(id, new HashSet<>());
        }
        return Map.copyOf(resolved);
    }

    private HudLayoutEngine.Bounds resolve(String id, Set<String> visiting) {
        if (resolved.containsKey(id)) {
            return resolved.get(id);
        }
        var placement = configs.get(id).placement();
        var size = sizes.get(id);
        HudLayoutEngine.Bounds target = null;
        if (visiting.add(id) && !wouldCycle(configs, id, placement.relativeTo()) && sizes.containsKey(placement
            .relativeTo())) {
            target = resolve(placement.relativeTo(), visiting);
        }
        var bounds = HudLayoutEngine.place(placement, size.width(), size.height(), width, height, target, guiScale);
        visiting.remove(id);
        resolved.put(id, bounds);
        return bounds;
    }

    public static boolean wouldCycle(Map<String, HudElementConfig> configs, String id, String target) {
        Set<String> seen = new HashSet<>();
        while (!target.isEmpty() && configs.containsKey(target)) {
            if (target.equals(id) || !seen.add(target)) {
                return true;
            }
            target = configs.get(target).placement().relativeTo();
        }
        return false;
    }
}
