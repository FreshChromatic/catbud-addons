package github.freshchromatic.catbud_addons.catbud_core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public record HudFrame(List<Entry> entries, Map<String, HudLayoutEngine.Bounds> bounds) {

    public record Entry(HudElement element, HudElementConfig config, HudRenderer.Layout layout,
        HudLayoutEngine.Bounds bounds) {}

    public record Background(HudStyle style, HudLayoutEngine.Bounds bounds, double scale) {}

    public List<Background> backgrounds() {
        Map<String, java.util.ArrayList<Entry>> groups = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.config().style().background()) {
                String group = entry.config().style().backgroundGroup();
                String key = group.isEmpty() ? "element:" + entry.element().id() : "group:" + group;
                groups.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(entry);
            }
        }
        return groups.values().stream().map(group -> {
            var first = group.getFirst();
            double left = group.stream().mapToDouble(e -> e.bounds().x()).min().orElse(0);
            double top = group.stream().mapToDouble(e -> e.bounds().y()).min().orElse(0);
            double right = group.stream().mapToDouble(e -> e.bounds().x() + e.bounds().width()).max().orElse(0);
            double bottom = group.stream().mapToDouble(e -> e.bounds().y() + e.bounds().height()).max().orElse(0);
            return new Background(first.config().style(), new HudLayoutEngine.Bounds(left, top, right - left, bottom
                - top), first.bounds().width() / first.layout().width());
        }).toList();
    }

    public static HudFrame measure(HudRegistry registry, Map<String, HudElementConfig> configs, Predicate<HudElement> include, boolean preview, double width, double height, double guiScale) {
        Map<String, HudElementConfig> complete = new LinkedHashMap<>(configs);
        Map<String, HudRenderer.Layout> layouts = new LinkedHashMap<>();
        Map<String, HudLayoutResolver.Size> sizes = new LinkedHashMap<>();
        var elements = registry.elements(configs).stream().filter(include).toList();
        for (HudElement element : elements) {
            var config = configs.getOrDefault(element.id(), element.defaults());
            complete.put(element.id(), config);
            var layout = HudRenderer.measure(element.content(preview), config.style());
            layouts.put(element.id(), layout);
            sizes.put(element.id(), new HudLayoutResolver.Size(layout.width(), layout.height()));
        }
        var bounds = new HudLayoutResolver(complete, sizes, width, height, guiScale).resolve();
        var entries = elements.stream().map(element -> new Entry(element, complete.get(element.id()), layouts.get(
            element.id()), bounds.get(element.id()))).toList();
        return new HudFrame(entries, bounds);
    }
}
