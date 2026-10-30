package github.freshchromatic.catbud_addons.island_assistive;

import java.util.Objects;

public record BlockMiningTarget(String world, String dimension, int x, int y, int z) {

    public BlockMiningTarget {
        Objects.requireNonNull(world);
        Objects.requireNonNull(dimension);
        if (world.isBlank() || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || Math.abs((long) x) > 30_000_000
            || Math.abs((long) z) > 30_000_000 || y < -20_000_000 || y > 20_000_000) {
            throw new IllegalArgumentException("Invalid mining target");
        }
    }
}
