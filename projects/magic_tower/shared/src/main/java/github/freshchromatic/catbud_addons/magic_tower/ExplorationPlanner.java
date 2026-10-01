package github.freshchromatic.catbud_addons.magic_tower;

import net.minecraft.core.BlockPos;

/** Ranks reachable unknown exits using the axial corridors built by the dungeon generator. */
final class ExplorationPlanner {
    record Estimate(double cost) {}

    private ExplorationPlanner() {}

    static Estimate estimate(DungeonPathfinder.Grid grid, BlockPos stand, BlockPos wall,
                             BlockPos target, double walkingCost) {
        int dx = Integer.compare(wall.getX(), stand.getX());
        int dz = Integer.compare(wall.getZ(), stand.getZ());
        int horizontal = Math.abs(dx) + Math.abs(dz);
        double corridor = 0;
        double alignment = 0;
        if (horizontal == 1) {
            BlockPos behind = stand.offset(-dx, 0, -dz);
            if (grid.canStand(behind)) {
                corridor += 0.55;
                if (grid.canStand(behind.offset(-dx, 0, -dz))) corridor += 0.25;
            }
            // A narrow axial passage is stronger evidence than the side of a broad room.
            int sideX = dz;
            int sideZ = dx;
            if (!grid.canStand(stand.offset(sideX, 0, sideZ))
                && !grid.canStand(stand.offset(-sideX, 0, -sideZ))) corridor += 0.20;
            int nearbyFloor = 0;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if ((x != 0 || z != 0) && grid.canStand(stand.offset(x, 0, z))) nearbyFloor++;
                }
            }
            if (nearbyFloor >= 5) corridor = Math.max(0, corridor - 0.40);
            int toX = target.getX() - stand.getX();
            int toZ = target.getZ() - stand.getZ();
            alignment = (double) (dx * toX + dz * toZ) / Math.max(1, Math.abs(toX) + Math.abs(toZ));
        }
        double evidence = clamp(0.15 + 0.60 * corridor + 0.18 * alignment, 0.05, 0.90);
        double remaining = Math.abs(wall.getX() - target.getX())
            + Math.abs(wall.getZ() - target.getZ())
            + 1.4 * Math.abs(wall.getY() - target.getY());
        // Cost includes getting to the exit, an estimated concealed route, and the risk of a detour.
        double cost = walkingCost + remaining / (0.55 + 0.55 * evidence)
            + (1 - evidence) * (6 + Math.min(10, walkingCost * 0.5));
        if (horizontal == 0) cost += 12; // The reference generator builds horizontal corridors.
        return new Estimate(cost);
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
