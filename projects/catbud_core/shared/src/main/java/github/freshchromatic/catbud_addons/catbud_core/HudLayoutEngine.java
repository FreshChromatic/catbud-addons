package github.freshchromatic.catbud_addons.catbud_core;

public final class HudLayoutEngine {

    private HudLayoutEngine() {
    }

    public record Bounds(double x, double y, double width, double height) {

        public boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    public static Bounds place(HudPlacement placement, double naturalWidth, double naturalHeight, double screenWidth, double screenHeight) {
        return place(placement, naturalWidth, naturalHeight, screenWidth, screenHeight, null, 1);
    }

    public static Bounds place(HudPlacement placement, double naturalWidth, double naturalHeight, double screenWidth, double screenHeight, Bounds target, double guiScale) {
        double factor = placement.factor(guiScale);
        double scale = Math.min(placement.scale() * factor, Math.min(screenWidth / naturalWidth, screenHeight
            / naturalHeight));
        double width = naturalWidth * scale;
        double height = naturalHeight * scale;
        double x = target == null ? (screenWidth - width) * placement.anchor().x
            : target.x() + target.width() * placement.relativeAnchor().x - width * placement.anchor().x;
        double y = target == null ? (screenHeight - height) * placement.anchor().y
            : target.y() + target.height() * placement.relativeAnchor().y - height * placement.anchor().y;
        x += placement.x() * factor;
        y += placement.y() * factor;
        return new Bounds(Math.clamp(x, 0, Math.max(0, screenWidth - width)), Math.clamp(y, 0, Math.max(0, screenHeight
            - height)), width, height);
    }

    public static HudPlacement at(HudPlacement placement, double x, double y, Bounds bounds, double screenWidth, double screenHeight) {
        return at(placement, x, y, bounds, screenWidth, screenHeight, null, 1);
    }

    public static HudPlacement at(HudPlacement placement, double x, double y, Bounds bounds, double screenWidth, double screenHeight, Bounds target, double guiScale) {
        double originX = target == null ? (screenWidth - bounds.width()) * placement.anchor().x
            : target.x() + target.width() * placement.relativeAnchor().x - bounds.width() * placement.anchor().x;
        double originY = target == null ? (screenHeight - bounds.height()) * placement.anchor().y
            : target.y() + target.height() * placement.relativeAnchor().y - bounds.height() * placement.anchor().y;
        return placement.withOffset((x - originX) / placement.factor(guiScale), (y - originY) / placement.factor(
            guiScale));
    }
}
