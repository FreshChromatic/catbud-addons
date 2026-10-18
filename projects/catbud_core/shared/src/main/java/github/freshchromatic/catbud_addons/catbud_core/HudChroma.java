package github.freshchromatic.catbud_addons.catbud_core;

public record HudChroma(double textSpeed, double backgroundSpeed, double textShadowSpeed, double boxShadowSpeed) {

    public HudChroma {
        textSpeed = HudStyle.finite(textSpeed, 0, 10, 0);
        backgroundSpeed = HudStyle.finite(backgroundSpeed, 0, 10, 0);
        textShadowSpeed = HudStyle.finite(textShadowSpeed, 0, 10, 0);
        boxShadowSpeed = HudStyle.finite(boxShadowSpeed, 0, 10, 0);
    }

    public HudStyle resolve(HudStyle style, long millis) {
        if (textSpeed == 0 && backgroundSpeed == 0 && textShadowSpeed == 0 && boxShadowSpeed == 0) {
            return style;
        }
        return style.edit(b -> {
            b.textColor = color(b.textColor, textSpeed, millis);
            b.backgroundColor = color(b.backgroundColor, backgroundSpeed, millis);
            var text = b.textShadow;
            b.textShadow = new HudStyle.Shadow(text.enabled(), color(text.color(), textShadowSpeed, millis), text.x(),
                text.y(), text.blur());
            var box = b.boxShadow;
            b.boxShadow = new HudStyle.Shadow(box.enabled(), color(box.color(), boxShadowSpeed, millis), box.x(), box
                .y(), box.blur());
        });
    }

    public static int color(int argb, double speed, long millis) {
        if (speed == 0) {
            return argb;
        }
        double hue = (millis / 1000.0 * speed / 6) % 1 * 6;
        int sector = (int) hue;
        int rising = (int) Math.round((hue - sector) * 255);
        int falling = 255 - rising;
        int rgb = switch (sector) {
            case 0 -> 0xFF0000 | rising << 8;
            case 1 -> falling << 16 | 0xFF00;
            case 2 -> 0xFF00 | rising;
            case 3 -> falling << 8 | 0xFF;
            case 4 -> rising << 16 | 0xFF;
            default -> 0xFF0000 | falling;
        };
        return argb & 0xFF000000 | rgb;
    }
}
