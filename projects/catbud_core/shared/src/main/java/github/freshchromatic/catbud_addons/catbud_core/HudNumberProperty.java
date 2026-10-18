package github.freshchromatic.catbud_addons.catbud_core;

import java.util.function.BiFunction;
import java.util.function.ToDoubleFunction;

enum HudNumberProperty {

    X("position", -10000, 10000, c -> c.placement().x(), (c, v) -> c.withPlacement(c.placement().withOffset(v, c
        .placement().y()))),

    Y("position", -10000, 10000, c -> c.placement().y(), (c, v) -> c.withPlacement(c.placement().withOffset(c
        .placement().x(), v))),

    SCALE("position", 0.25, 5, c -> c.placement().scale(), (c, v) -> c.withPlacement(c.placement().withScale(v))),

    FIXED_WIDTH("size", 0, 1000, c -> c.style().fixedWidth(), (c, v) -> style(c, b -> b.fixedWidth = v)),

    FIXED_HEIGHT("size", 0, 1000, c -> c.style().fixedHeight(), (c, v) -> style(c, b -> b.fixedHeight = v)),

    TEXT_CHROMA("chroma", 0, 10, c -> c.style().chroma().textSpeed(), (c, v) -> style(c, b -> b.chroma = new HudChroma(
        v, b.chroma.backgroundSpeed(), b.chroma.textShadowSpeed(), b.chroma.boxShadowSpeed()))),

    BACKGROUND_CHROMA("chroma", 0, 10, c -> c.style().chroma().backgroundSpeed(), (c, v) -> style(c,
        b -> b.chroma = new HudChroma(b.chroma.textSpeed(), v, b.chroma.textShadowSpeed(), b.chroma.boxShadowSpeed()))),

    TEXT_SHADOW_CHROMA("chroma", 0, 10, c -> c.style().chroma().textShadowSpeed(), (c, v) -> style(c,
        b -> b.chroma = new HudChroma(b.chroma.textSpeed(), b.chroma.backgroundSpeed(), v, b.chroma.boxShadowSpeed()))),

    BOX_SHADOW_CHROMA("chroma", 0, 10, c -> c.style().chroma().boxShadowSpeed(), (c, v) -> style(c,
        b -> b.chroma = new HudChroma(b.chroma.textSpeed(), b.chroma.backgroundSpeed(), b.chroma.textShadowSpeed(),
            v))),

    TEXT_SCALE("text", 0.5, 4, c -> c.style().textScale(), (c, v) -> style(c, b -> b.textScale = v)),

    TEXT_OPACITY("text", 0, 100, c -> opacity(c.style().textColor()), (c, v) -> style(c, b -> b.textColor = alpha(
        b.textColor, v))),

    LINE_GAP("text", 0, 24, c -> c.style().lineGap(), (c, v) -> style(c, b -> b.lineGap = v)),

    BACKGROUND_OPACITY("background", 0, 100, c -> opacity(c.style().backgroundColor()), (c, v) -> style(c,
        b -> b.backgroundColor = alpha(b.backgroundColor, v))),

    RADIUS("background", 0, 48, c -> c.style().radius(), (c, v) -> style(c, b -> b.radius = v)),

    TEXT_SHADOW_OPACITY("text_shadow", 0, 100, c -> opacity(c.style().textShadow().color()), (c, v) -> textShadow(c,
        alpha(c.style().textShadow().color(), v), null, null)),

    TEXT_SHADOW_X("text_shadow", -16, 16, c -> c.style().textShadow().x(), (c, v) -> textShadow(c, null, v, null)),

    TEXT_SHADOW_Y("text_shadow", -16, 16, c -> c.style().textShadow().y(), (c, v) -> textShadow(c, null, null, v)),

    BOX_SHADOW_OPACITY("box_shadow", 0, 100, c -> opacity(c.style().boxShadow().color()), (c, v) -> boxShadow(c, alpha(c
        .style().boxShadow().color(), v), null, null, null)),

    BOX_SHADOW_X("box_shadow", -16, 16, c -> c.style().boxShadow().x(), (c, v) -> boxShadow(c, null, v, null, null)),

    BOX_SHADOW_Y("box_shadow", -16, 16, c -> c.style().boxShadow().y(), (c, v) -> boxShadow(c, null, null, v, null)),

    BLUR("box_shadow", 0, 12, c -> c.style().boxShadow().blur(), (c, v) -> boxShadow(c, null, null, null, v)),

    PADDING_TOP("padding", 0, 48, c -> c.style().padding().top(), (c, v) -> padding(c, v, null, null, null)),

    PADDING_RIGHT("padding", 0, 48, c -> c.style().padding().right(), (c, v) -> padding(c, null, v, null, null)),

    PADDING_BOTTOM("padding", 0, 48, c -> c.style().padding().bottom(), (c, v) -> padding(c, null, null, v, null)),

    PADDING_LEFT("padding", 0, 48, c -> c.style().padding().left(), (c, v) -> padding(c, null, null, null, v)),

    ICON_SIZE("icon", 0, 48, c -> c.style().iconSize(), (c, v) -> style(c, b -> b.iconSize = v)),

    ICON_GAP("icon", 0, 32, c -> c.style().iconGap(), (c, v) -> style(c, b -> b.iconGap = v));

    final String                                                 section;
    final double                                                 min, max;
    final ToDoubleFunction<HudElementConfig>                     get;
    final BiFunction<HudElementConfig, Double, HudElementConfig> set;

    HudNumberProperty(String section, double min, double max, ToDoubleFunction<HudElementConfig> get, BiFunction<HudElementConfig, Double, HudElementConfig> set) {
        this.section = section;
        this.min = min;
        this.max = max;
        this.get = get;
        this.set = set;
    }

    static HudElementConfig style(HudElementConfig config, java.util.function.Consumer<HudStyle.Builder> action) {
        return config.withStyle(config.style().edit(action));
    }

    static double opacity(int color) {
        return Math.round((color >>> 24) / 255.0 * 100);
    }

    static int alpha(int color, double opacity) {
        return (int) Math.round(opacity / 100 * 255) << 24 | color & 0xFFFFFF;
    }

    private static HudElementConfig textShadow(HudElementConfig config, Integer color, Double x, Double y) {
        var s = config.style().textShadow();
        return style(config, b -> b.textShadow = new HudStyle.Shadow(s.enabled(), color == null ? s.color() : color,
            x == null ? s.x() : x, y == null ? s.y() : y, 0));
    }

    private static HudElementConfig boxShadow(HudElementConfig config, Integer color, Double x, Double y, Double blur) {
        var s = config.style().boxShadow();
        return style(config, b -> b.boxShadow = new HudStyle.Shadow(s.enabled(), color == null ? s.color() : color,
            x == null ? s.x() : x, y == null ? s.y() : y, blur == null ? s.blur() : blur));
    }

    private static HudElementConfig padding(HudElementConfig config, Double top, Double right, Double bottom, Double left) {
        var p = config.style().padding();
        return style(config, b -> b.padding = new HudStyle.Insets(top == null ? p.top() : top, right == null ? p.right()
            : right, bottom == null ? p.bottom() : bottom, left == null ? p.left() : left));
    }
}
