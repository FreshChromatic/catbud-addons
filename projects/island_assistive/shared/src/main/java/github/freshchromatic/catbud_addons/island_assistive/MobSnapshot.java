package github.freshchromatic.catbud_addons.island_assistive;

import java.util.UUID;

public record MobSnapshot(UUID uuid, String entityType, String customName, String displayName, double x, double y,
    double z, double distance) {}
