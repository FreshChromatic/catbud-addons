package github.freshchromatic.catbud_addons.catbud_core;

import java.util.function.Consumer;

public record HudStyle(FontFace font, double textScale, boolean bold, boolean italic, int textColor, boolean background,
    int backgroundColor, double radius, Shadow textShadow, Shadow boxShadow, Insets padding, double iconSize,
    double iconGap, double lineGap, Alignment alignment, double fixedWidth, double fixedHeight,
    HudTextFormat formatting, HudChroma chroma, String backgroundGroup) {

    public enum FontFace {
        MINECRAFT, SANS
    }

    public enum Alignment {
        LEFT, CENTER, RIGHT
    }

    public record Shadow(boolean enabled, int color, double x, double y, double blur) {

        public Shadow {
            x = finite(x, -16, 16, 1);
            y = finite(y, -16, 16, 1);
            blur = finite(blur, 0, 12, 0);
        }
    }

    public record Insets(double top, double right, double bottom, double left) {

        public Insets {
            top = finite(top, 0, 48, 4);
            right = finite(right, 0, 48, 4);
            bottom = finite(bottom, 0, 48, 4);
            left = finite(left, 0, 48, 4);
        }
    }

    public HudStyle(FontFace font, double textScale, boolean bold, boolean italic, int textColor, boolean background, int backgroundColor, double radius, Shadow textShadow, Shadow boxShadow, Insets padding, double iconSize, double iconGap, double lineGap, Alignment alignment) {
        this(font, textScale, bold, italic, textColor, background, backgroundColor, radius, textShadow, boxShadow,
            padding, iconSize, iconGap, lineGap, alignment, 0, 0, null, null, "");
    }

    public HudStyle {
        backgroundGroup = backgroundGroup == null ? "" : backgroundGroup;
        fixedWidth = finite(fixedWidth, 0, 1000, 0);
        fixedHeight = finite(fixedHeight, 0, 1000, 0);
        formatting = formatting == null ? HudTextFormat.defaults() : formatting;
        chroma = chroma == null ? new HudChroma(0, 0, 0, 0) : chroma;
        font = font == null ? FontFace.MINECRAFT : font;
        textScale = finite(textScale, 0.5, 4, 1);
        radius = finite(radius, 0, 48, 4);
        textShadow = textShadow == null ? new Shadow(true, 0xA0000000, 1, 1, 0) : textShadow;
        boxShadow = boxShadow == null ? new Shadow(false, 0x60000000, 2, 2, 4) : boxShadow;
        padding = padding == null ? new Insets(4, 4, 4, 4) : padding;
        iconSize = finite(iconSize, 0, 48, 16);
        iconGap = finite(iconGap, 0, 32, 4);
        lineGap = finite(lineGap, 0, 24, 2);
        alignment = alignment == null ? Alignment.LEFT : alignment;
    }

    public static HudStyle defaults() {
        return new HudStyle(FontFace.MINECRAFT, 1, false, false, 0xFFFFFFFF, true, 0xB8080B10, 4, null, null, null, 16,
            4, 2, Alignment.LEFT);
    }

    public HudStyle edit(Consumer<Builder> action) {
        Builder builder = new Builder(this);
        action.accept(builder);
        return builder.build();
    }

    static double finite(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }

    public static final class Builder {

        public FontFace      font;
        public double        textScale, radius, iconSize, iconGap, lineGap;
        public boolean       bold, italic, background;
        public int           textColor, backgroundColor;
        public Shadow        textShadow, boxShadow;
        public Insets        padding;
        public Alignment     alignment;
        public double        fixedWidth, fixedHeight;
        public HudTextFormat formatting;
        public HudChroma     chroma;
        public String        backgroundGroup;

        private Builder(HudStyle style) {
            backgroundGroup = style.backgroundGroup;
            fixedWidth = style.fixedWidth;
            fixedHeight = style.fixedHeight;
            formatting = style.formatting;
            chroma = style.chroma;
            font = style.font;
            textScale = style.textScale;
            bold = style.bold;
            italic = style.italic;
            textColor = style.textColor;
            background = style.background;
            backgroundColor = style.backgroundColor;
            radius = style.radius;
            textShadow = style.textShadow;
            boxShadow = style.boxShadow;
            padding = style.padding;
            iconSize = style.iconSize;
            iconGap = style.iconGap;
            lineGap = style.lineGap;
            alignment = style.alignment;
        }

        public HudStyle build() {
            return new HudStyle(font, textScale, bold, italic, textColor, background, backgroundColor, radius,
                textShadow, boxShadow, padding, iconSize, iconGap, lineGap, alignment, fixedWidth, fixedHeight,
                formatting, chroma, backgroundGroup);
        }
    }
}
