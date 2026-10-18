package github.freshchromatic.catbud_addons.catbud_core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HudShapes {

    private record Key(int width, int height, HudStyle style) {}

    private record Span(int left, int top, int right, int bottom, int color) {}

    private static final Map<Key, List<Span>> CACHE = new LinkedHashMap<>(32, 0.75f, true);

    private HudShapes() {
    }

    static void background(HudGraphics graphics, double width, double height, HudStyle style) {
        Key key = new Key((int) Math.ceil(width), (int) Math.ceil(height), style);
        List<Span> spans = CACHE.computeIfAbsent(key, HudShapes::build);
        if (CACHE.size() > 128) {
            CACHE.remove(CACHE.keySet().iterator().next());
        }
        for (Span span : spans) {
            graphics.fill(span.left(), span.top(), span.right(), span.bottom(), span.color());
        }
    }

    private static List<Span> build(Key key) {
        List<Span> spans = new ArrayList<>();
        HudStyle style = key.style();
        if (!style.background()) {
            return List.of();
        }
        HudStyle.Shadow shadow = style.boxShadow();
        if (shadow.enabled()) {
            int layers = Math.max(1, (int) Math.ceil(shadow.blur()));
            int alpha = shadow.color() >>> 24;
            for (int layer = layers; layer >= 0; layer--) {
                double opacity = (alpha / 255.0) / (layers + 1);
                int ink = (int) Math.round(opacity * 255) << 24 | shadow.color() & 0xFFFFFF;
                rounded(spans, (int) shadow.x() - layer, (int) shadow.y() - layer, key.width() + layer * 2, key.height()
                    + layer * 2, style.radius() + layer, ink);
            }
        }
        rounded(spans, 0, 0, key.width(), key.height(), style.radius(), style.backgroundColor());
        return List.copyOf(spans);
    }

    private static void rounded(List<Span> spans, int x, int y, int width, int height, double radius, int color) {
        if ((color >>> 24) == 0) {
            return;
        }
        double r = Math.min(radius, Math.min(width, height) / 2.0);
        if (r <= 0) {
            spans.add(new Span(x, y, x + width, y + height, color));
            return;
        }
        for (int row = 0; row < height; row++) {
            double distance = Math.max(0, r - Math.min(row + 0.5, height - row - 0.5));
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - distance * distance)));
            spans.add(new Span(x + inset, y + row, x + width - inset, y + row + 1, color));
        }
    }
}
