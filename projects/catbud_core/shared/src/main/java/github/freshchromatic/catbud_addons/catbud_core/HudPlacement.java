package github.freshchromatic.catbud_addons.catbud_core;

public record HudPlacement(Anchor anchor, double x, double y, double scale, String relativeTo, Anchor relativeAnchor,
    double guiScale) {

    public enum Anchor {

        TOP_LEFT(0, 0), TOP(0.5, 0), TOP_RIGHT(1, 0), LEFT(0, 0.5), CENTER(0.5, 0.5), RIGHT(1, 0.5), BOTTOM_LEFT(0, 1),
        BOTTOM(0.5, 1), BOTTOM_RIGHT(1, 1);

        public final double x, y;

        Anchor(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    public HudPlacement(Anchor anchor, double x, double y, double scale) {
        this(anchor, x, y, scale, "", Anchor.BOTTOM_LEFT, 0);
    }

    public HudPlacement withOffset(double x, double y) {
        return new HudPlacement(anchor, x, y, scale, relativeTo, relativeAnchor, guiScale);
    }

    public HudPlacement withScale(double next) {
        return new HudPlacement(anchor, x, y, next, relativeTo, relativeAnchor, guiScale);
    }

    public HudPlacement withAnchor(Anchor next) {
        return new HudPlacement(next, x, y, scale, relativeTo, relativeAnchor, guiScale);
    }

    public HudPlacement withRelative(String target, Anchor point) {
        return new HudPlacement(anchor, x, y, scale, target, point, guiScale);
    }

    public HudPlacement withGuiScale(double next) {
        return new HudPlacement(anchor, x, y, scale, relativeTo, relativeAnchor, next);
    }

    public double factor(double currentGuiScale) {
        return guiScale > 0 ? guiScale / Math.max(1, currentGuiScale) : 1;
    }

    public HudPlacement {
        relativeTo = relativeTo == null ? "" : relativeTo;
        relativeAnchor = relativeAnchor == null ? Anchor.BOTTOM_LEFT : relativeAnchor;
        guiScale = HudStyle.finite(guiScale, 0, 16, 0);
        anchor = anchor == null ? Anchor.TOP_LEFT : anchor;
        x = HudStyle.finite(x, -10000, 10000, 8);
        y = HudStyle.finite(y, -10000, 10000, 8);
        scale = HudStyle.finite(scale, 0.25, 5, 1);
    }
}
