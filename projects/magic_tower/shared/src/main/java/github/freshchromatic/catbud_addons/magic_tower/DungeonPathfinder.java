package github.freshchromatic.catbud_addons.magic_tower;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Bounded walking route search through loaded dungeon terrain. */
final class DungeonPathfinder {
    interface Grid {
        boolean canStand(BlockPos pos);
        boolean canStep(BlockPos from, BlockPos to);
        default List<BlockPos> frontierWalls(BlockPos pos) { return List.of(); }
        default List<BlockPos> frontierWalls(BlockPos pos, BlockPos target) { return frontierWalls(pos); }
    }

    private record Node(BlockPos pos, double cost, double estimate) {}
    record FrontierRoute(List<BlockPos> blocks, BlockPos wall, double priority) {}
    record ExplorationRoute(List<BlockPos> blocks, BlockPos wall) {}
    record ApproachRoute(List<BlockPos> blocks, double distance) {}
    /** Finds every reachable bedrock boundary without guessing which one will open. */
    static final class AllFrontiersSearch {
        private record Boundary(BlockPos stand, BlockPos wall, double cost) {}
        private final BlockPos origin;
        private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::cost));
        private final Map<BlockPos, Double> costs = new HashMap<>();
        private final Map<BlockPos, BlockPos> parents = new HashMap<>();
        private final Map<BlockPos, Boundary> boundaries = new HashMap<>();
        private final Set<BlockPos> closed = new HashSet<>();
        private boolean complete;

        AllFrontiersSearch(BlockPos origin) {
            this.origin = origin;
            costs.put(origin, 0.0);
            open.add(new Node(origin, 0, 0));
        }

        BlockPos origin() { return origin; }
        boolean complete() { return complete; }

        void advance(Grid grid, int nodesPerTick, int maxVisited) {
            if (complete) return;
            if (!grid.canStand(origin)) {
                complete = true;
                return;
            }
            int processed = 0;
            while (!open.isEmpty() && processed < nodesPerTick && closed.size() < maxVisited) {
                Node current = open.poll();
                if (!closed.add(current.pos())) continue;
                processed++;
                for (BlockPos wall : grid.frontierWalls(current.pos(), current.pos()))
                    boundaries.putIfAbsent(wall, new Boundary(current.pos(), wall, current.cost()));
                for (int[] direction : DIRECTIONS) {
                    for (int dy : new int[] {0, 1, -1}) {
                        BlockPos next = current.pos().offset(direction[0], dy, direction[1]);
                        if (closed.contains(next) || !grid.canStand(next)
                            || !grid.canStep(current.pos(), next)) continue;
                        double nextCost = current.cost() + (dy == 0 ? 1 : 1.4);
                        if (nextCost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                        costs.put(next, nextCost);
                        parents.put(next, current.pos());
                        open.add(new Node(next, nextCost, nextCost));
                    }
                }
            }
            complete = open.isEmpty() || closed.size() >= maxVisited;
        }

        List<ExplorationRoute> results() {
            if (!complete) return List.of();
            List<Boundary> chosen = new ArrayList<>();
            Set<BlockPos> visitedWalls = new HashSet<>();
            for (Boundary boundary : boundaries.values()) {
                if (!visitedWalls.add(boundary.wall())) continue;
                Boundary nearest = boundary;
                ArrayDeque<Boundary> pending = new ArrayDeque<>();
                pending.add(boundary);
                while (!pending.isEmpty()) {
                    Boundary current = pending.removeFirst();
                    if (current.cost() < nearest.cost()) nearest = current;
                    int dx = Integer.compare(current.wall().getX(), current.stand().getX());
                    int dz = Integer.compare(current.wall().getZ(), current.stand().getZ());
                    for (int side : new int[] {-1, 1}) {
                        BlockPos neighborPos = current.wall().offset(dz * side, 0, dx * side);
                        Boundary neighbor = boundaries.get(neighborPos);
                        if (neighbor == null || !visitedWalls.add(neighborPos)) continue;
                        if (Integer.compare(neighbor.wall().getX(), neighbor.stand().getX()) == dx
                            && Integer.compare(neighbor.wall().getZ(), neighbor.stand().getZ()) == dz)
                            pending.add(neighbor);
                        else visitedWalls.remove(neighborPos);
                    }
                }
                chosen.add(nearest);
            }
            chosen.sort(Comparator.comparingDouble(Boundary::cost));
            List<ExplorationRoute> routes = new ArrayList<>();
            for (Boundary boundary : chosen)
                routes.add(new ExplorationRoute(reconstruct(boundary.stand(), parents), boundary.wall()));
            return List.copyOf(routes);
        }
    }
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int FRONTIER_WALK_LIMIT = 96;

    private DungeonPathfinder() {}

    static List<BlockPos> find(Grid grid, BlockPos start, Set<BlockPos> goals, int maxVisited) {
        if (goals.isEmpty() || !grid.canStand(start)) return List.of();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::estimate));
        Map<BlockPos, Double> costs = new HashMap<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        costs.put(start, 0.0);
        open.add(new Node(start, 0, heuristic(start, goals)));
        while (!open.isEmpty() && closed.size() < maxVisited) {
            Node current = open.poll();
            if (!closed.add(current.pos())) continue;
            if (goals.contains(current.pos())) return reconstruct(current.pos(), parents);
            for (int[] direction : DIRECTIONS) {
                for (int dy : new int[] {0, 1, -1}) {
                    BlockPos next = current.pos().offset(direction[0], dy, direction[1]);
                    if (closed.contains(next) || !grid.canStand(next) || !grid.canStep(current.pos(), next)) continue;
                    double nextCost = current.cost() + (dy == 0 ? 1 : 1.4);
                    if (nextCost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    costs.put(next, nextCost);
                    parents.put(next, current.pos());
                    open.add(new Node(next, nextCost, nextCost + heuristic(next, goals)));
                }
            }
        }
        return List.of();
    }

    static FrontierRoute findFrontier(Grid grid, BlockPos start, BlockPos target,
                                      Set<BlockPos> excludedWalls, int maxVisited) {
        return findFrontier(grid, start, target, excludedWalls, Map.of(), maxVisited);
    }

    static ApproachRoute findClosestApproach(Grid grid, BlockPos start,
                                             Set<BlockPos> targets, int maxVisited) {
        if (targets.isEmpty() || !grid.canStand(start)) return null;
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(
                (Node node) -> node.cost() + 0.8 * heuristic(node.pos(), targets))
            .thenComparingInt(node -> node.pos().getX())
            .thenComparingInt(node -> node.pos().getY())
            .thenComparingInt(node -> node.pos().getZ()));
        Map<BlockPos, Double> costs = new HashMap<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        costs.put(start, 0.0);
        open.add(new Node(start, 0, 0));
        BlockPos best = start;
        double bestDistance = heuristic(start, targets);
        double bestCost = 0;
        double bestScore = bestDistance;
        while (!open.isEmpty() && closed.size() < maxVisited) {
            Node current = open.poll();
            if (!closed.add(current.pos())) continue;
            double distance = heuristic(current.pos(), targets);
            double score = distance + 0.25 * current.cost();
            if (score < bestScore - 0.001
                || (Math.abs(score - bestScore) < 0.001 && current.cost() < bestCost)) {
                best = current.pos();
                bestDistance = distance;
                bestCost = current.cost();
                bestScore = score;
            }
            for (int[] direction : DIRECTIONS) {
                for (int dy : new int[] {0, 1, -1}) {
                    BlockPos next = current.pos().offset(direction[0], dy, direction[1]);
                    if (closed.contains(next) || !grid.canStand(next) || !grid.canStep(current.pos(), next)) continue;
                    double nextCost = current.cost() + (dy == 0 ? 1 : 1.4);
                    if (nextCost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    costs.put(next, nextCost);
                    parents.put(next, current.pos());
                    open.add(new Node(next, nextCost, nextCost));
                }
            }
        }
        return best.equals(start) ? null : new ApproachRoute(reconstruct(best, parents), bestDistance);
    }

    static FrontierRoute findFrontier(Grid grid, BlockPos start, BlockPos target,
                                      Set<BlockPos> excludedWalls, Map<BlockPos, Integer> failedVisits,
                                      int maxVisited) {
        if (!grid.canStand(start)) return null;
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::estimate));
        Map<BlockPos, Double> costs = new HashMap<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        costs.put(start, 0.0);
        open.add(new Node(start, 0, 0));
        BlockPos bestPosition = null;
        BlockPos bestWall = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double bestPriority = 0;
        while (!open.isEmpty() && closed.size() < maxVisited) {
            Node current = open.poll();
            if (!closed.add(current.pos())) continue;
            for (BlockPos wall : grid.frontierWalls(current.pos(), target)) {
                if (excludedWalls.stream().anyMatch(excluded ->
                    sameFrontierRegion(current.pos(), wall, excluded))) continue;
                ExplorationPlanner.Estimate estimate = ExplorationPlanner.estimate(
                    grid, current.pos(), wall, target, current.cost());
                double score = estimate.cost()
                    + 8.0 * Math.min(4, nearbyFailedVisits(failedVisits, current.pos(), wall));
                if (score < bestScore) {
                    bestScore = score;
                    bestPosition = current.pos();
                    bestWall = wall;
                    bestPriority = 1.0 / (1.0 + score / 32.0);
                }
            }
            if (current.cost() >= FRONTIER_WALK_LIMIT) continue;
            for (int[] direction : DIRECTIONS) {
                for (int dy : new int[] {0, 1, -1}) {
                    BlockPos next = current.pos().offset(direction[0], dy, direction[1]);
                    if (closed.contains(next) || !grid.canStand(next) || !grid.canStep(current.pos(), next)) continue;
                    double nextCost = current.cost() + (dy == 0 ? 1 : 1.4);
                    if (nextCost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    costs.put(next, nextCost);
                    parents.put(next, current.pos());
                    open.add(new Node(next, nextCost, nextCost));
                }
            }
        }
        return bestPosition == null ? null
            : new FrontierRoute(reconstruct(bestPosition, parents), bestWall, bestPriority);
    }

    private static double heuristic(BlockPos pos, Set<BlockPos> goals) {
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos goal : goals) {
            double horizontal = Math.abs(pos.getX() - goal.getX()) + Math.abs(pos.getZ() - goal.getZ());
            double vertical = Math.abs(pos.getY() - goal.getY());
            best = Math.min(best, Math.max(horizontal, vertical) + 0.4 * vertical);
        }
        return best;
    }

    private static boolean sameFrontierRegion(BlockPos stand, BlockPos wall, BlockPos other) {
        if (Math.abs(other.getY() - wall.getY()) > 2) return false;
        if (wall.getX() != stand.getX())
            return wall.getX() == other.getX() && Math.abs(wall.getZ() - other.getZ()) <= 8;
        if (wall.getZ() != stand.getZ())
            return wall.getZ() == other.getZ() && Math.abs(wall.getX() - other.getX()) <= 8;
        return wall.getX() == other.getX() && wall.getZ() == other.getZ();
    }

    private static int nearbyFailedVisits(Map<BlockPos, Integer> visits, BlockPos stand, BlockPos wall) {
        int count = 0;
        for (Map.Entry<BlockPos, Integer> entry : visits.entrySet())
            if (sameFrontierRegion(stand, wall, entry.getKey()))
                count = Math.max(count, entry.getValue());
        return count;
    }

    private static List<BlockPos> reconstruct(BlockPos end, Map<BlockPos, BlockPos> parents) {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (BlockPos pos = end; pos != null; pos = parents.get(pos)) result.add(pos);
        return List.copyOf(result.reversed());
    }
}
