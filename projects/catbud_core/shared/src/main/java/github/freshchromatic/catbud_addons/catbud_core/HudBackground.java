package github.freshchromatic.catbud_addons.catbud_core;

public record HudBackground(boolean enabled, int color, double radius, HudStyle.Shadow shadow, double chromaSpeed,
    double shadowChromaSpeed) {

    public static HudBackground from(HudStyle style) {
        return new HudBackground(style.background(), style.backgroundColor(), style.radius(), style.boxShadow(), style
            .chroma().backgroundSpeed(), style.chroma().boxShadowSpeed());
    }

    public HudStyle apply(HudStyle style) {
        return style.edit(b -> {
            b.background = enabled;
            b.backgroundColor = color;
            b.radius = radius;
            b.boxShadow = shadow;
            b.chroma = new HudChroma(b.chroma.textSpeed(), chromaSpeed, b.chroma.textShadowSpeed(), shadowChromaSpeed);
        });
    }
}
