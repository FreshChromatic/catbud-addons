package github.freshchromatic.catbud_addons.catbud_core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HudChanges {

    public record Change(String elementId, String property, String before, String after) {}

    private static final Gson GSON = new Gson();

    private HudChanges() {
    }

    public static List<Change> between(Map<String, HudElementConfig> before, Map<String, HudElementConfig> after) {
        List<Change> changes = new ArrayList<>();
        var ids = new java.util.LinkedHashSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (String id : ids) {
            compare(changes, id, "", GSON.toJsonTree(before.get(id)), GSON.toJsonTree(after.get(id)));
        }
        return List.copyOf(changes);
    }

    private static void compare(List<Change> changes, String id, String path, JsonElement before, JsonElement after) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (before != null && after != null && before.isJsonObject() && after.isJsonObject()) {
            var keys = new java.util.LinkedHashSet<>(before.getAsJsonObject().keySet());
            keys.addAll(after.getAsJsonObject().keySet());
            for (String key : keys) {
                compare(changes, id, path.isEmpty() ? key : path + "." + key, before.getAsJsonObject().get(key), after
                    .getAsJsonObject().get(key));
            }
        } else {
            changes.add(new Change(id, path, value(path, before), value(path, after)));
        }
    }

    private static String value(String path, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "—";
        }
        if (path.endsWith("Color") || path.endsWith(".color")) {
            return String.format(java.util.Locale.ROOT, "#%08X", value.getAsInt());
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    public static List<Component> describe(HudRegistry registry, Map<String, HudElementConfig> before, Map<String, HudElementConfig> after) {
        return between(before, after).stream().map(change -> {
            Component name;
            try {
                var configs = after.containsKey(change.elementId()) ? after : before;
                name = registry.get(change.elementId(), configs).name();
            } catch (IllegalArgumentException error) {
                name = Component.literal(change.elementId());
            }
            if (change.property().isEmpty()) {
                return (Component) Component.translatable(after.containsKey(change.elementId())
                    ? "hud.core.element_added"
                    : "hud.core.element_removed", name);
            }
            String property = change.property().replaceFirst("^(style|placement)\\.", "").replaceAll("([a-z])([A-Z])",
                "$1_$2").replace('.', '_').toLowerCase(java.util.Locale.ROOT);
            return (Component) Component.translatable("hud.core.change", name, Component.translatable("hud.core."
                + property), displayValue(registry, before, change.property(), change.before()), displayValue(registry,
                    after, change.property(), change.after()));
        }).toList();
    }

    private static Component displayValue(HudRegistry registry, Map<String, HudElementConfig> configs, String path, String value) {
        if (path.equals("style.backgroundGroup")) {
            if (value.isEmpty()) {
                return Component.translatable("hud.core.ungrouped");
            }
            var members = registry.elements(configs).stream().filter(element -> configs.getOrDefault(element.id(),
                element.defaults()).style().backgroundGroup().equals(value)).map(element -> element.name().getString())
                .toList();
            return Component.literal(String.join("、", members));
        }
        if (path.equals("placement.relativeTo")) {
            if (value.isEmpty()) {
                return Component.translatable("hud.core.screen_anchor");
            }
            try {
                return registry.get(value, configs).name();
            } catch (IllegalArgumentException error) {
                return Component.literal(value);
            }
        }
        if (value.equals("true") || value.equals("false")) {
            return Component.translatable(value.equals("true") ? "option.catbud_core.on" : "option.catbud_core.off");
        }
        if (java.util.Set.of("style.font", "style.alignment", "style.formatting.letterCase", "style.formatting.weight",
            "placement.anchor", "placement.relativeAnchor").contains(path)) {
            return Component.translatable("hud.core.value." + value.toLowerCase(java.util.Locale.ROOT));
        }
        return Component.literal(value);
    }
}
