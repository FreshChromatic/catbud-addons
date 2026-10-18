package github.freshchromatic.catbud_addons.catbud_core;

public record HudElementConfig(boolean enabled, boolean locked, HudPlacement placement, HudStyle style, String sourceId,
    boolean deleted) {

    public HudElementConfig(boolean enabled, boolean locked, HudPlacement placement, HudStyle style) {
        this(enabled, locked, placement, style, null, false);
    }

    public HudElementConfig {
        placement = placement == null ? new HudPlacement(null, 8, 8, 1) : placement;
        style = style == null ? HudStyle.defaults() : style;
    }

    public HudElementConfig withStyle(HudStyle next) {
        return new HudElementConfig(enabled, locked, placement, next, sourceId, deleted);
    }

    public HudElementConfig withPlacement(HudPlacement next) {
        return new HudElementConfig(enabled, locked, next, style, sourceId, deleted);
    }

    public HudElementConfig withEnabled(boolean next) {
        return new HudElementConfig(next, locked, placement, style, sourceId, deleted);
    }

    public HudElementConfig withLocked(boolean next) {
        return new HudElementConfig(enabled, next, placement, style, sourceId, deleted);
    }

    public HudElementConfig withSource(String next) {
        return new HudElementConfig(enabled, locked, placement, style, next, deleted);
    }

    public HudElementConfig asDeleted() {
        return new HudElementConfig(enabled, locked, placement, style, sourceId, true);
    }
}
