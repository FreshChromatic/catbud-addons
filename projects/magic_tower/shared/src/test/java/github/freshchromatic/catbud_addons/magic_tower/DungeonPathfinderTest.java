package github.freshchromatic.catbud_addons.magic_tower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class DungeonPathfinderTest {
    private static final BlockPos START = new BlockPos(0, 0, 0);
    private static final BlockPos GOAL = new BlockPos(4, 0, 0);

    @Test
    void routesAroundAWall() {
        Set<BlockPos> wall = Set.of(new BlockPos(2, 0, -1), new BlockPos(2, 0, 0));
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) {
                return pos.getY() == 0 && Math.abs(pos.getZ()) <= 2 && !wall.contains(pos);
            }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
        };
        List<BlockPos> route = DungeonPathfinder.find(grid, START, Set.of(GOAL), 100);
        assertEquals(START, route.getFirst());
        assertEquals(GOAL, route.getLast());
        assertTrue(route.stream().anyMatch(pos -> pos.getZ() > 0));
        assertTrue(route.stream().noneMatch(wall::contains));
    }

    @Test
    void doesNotDrawThroughAnUnreachableWall() {
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) {
                return pos.getY() == 0 && pos.getZ() == 0 && pos.getX() != 2;
            }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
        };
        assertFalse(DungeonPathfinder.find(grid, START, Set.of(GOAL), 100).iterator().hasNext());
    }

    @Test
    void routesToAReachableBedrockFrontierAndCanChooseAnother() {
        BlockPos eastWall = new BlockPos(3, 0, 0);
        BlockPos northWall = new BlockPos(0, 0, -2);
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) {
                return pos.getY() == 0 && pos.getZ() == 0 && pos.getX() >= 0 && pos.getX() <= 2
                    || pos.getY() == 0 && pos.getX() == 0 && pos.getZ() == -1;
            }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
            public List<BlockPos> frontierWalls(BlockPos pos) {
                if (pos.equals(new BlockPos(2, 0, 0))) return List.of(eastWall);
                if (pos.equals(new BlockPos(0, 0, -1))) return List.of(northWall);
                return List.of();
            }
        };
        DungeonPathfinder.FrontierRoute first = DungeonPathfinder.findFrontier(
            grid, START, new BlockPos(6, 0, 0), Set.of(), 100);
        assertEquals(eastWall, first.wall());
        assertEquals(new BlockPos(2, 0, 0), first.blocks().getLast());
        DungeonPathfinder.FrontierRoute second = DungeonPathfinder.findFrontier(
            grid, START, new BlockPos(6, 0, 0), Set.of(eastWall), 100);
        assertEquals(northWall, second.wall());
    }

    @Test
    void prefersAVisibleCorridorContinuationOverANearerRoomWall() {
        BlockPos roomWall = new BlockPos(1, 0, 0);
        BlockPos corridorWall = new BlockPos(5, 0, 1);
        BlockPos portal = new BlockPos(20, 0, 0);
        Set<BlockPos> visible = Set.of(START,
            new BlockPos(0, 0, 1), new BlockPos(1, 0, 1), new BlockPos(2, 0, 1),
            new BlockPos(3, 0, 1), new BlockPos(4, 0, 1));
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) { return visible.contains(pos); }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
            public List<BlockPos> frontierWalls(BlockPos pos, BlockPos target) {
                if (pos.equals(START)) return List.of(roomWall);
                if (pos.equals(new BlockPos(4, 0, 1))) return List.of(corridorWall);
                return List.of();
            }
        };
        // The old walk-cost + straight-distance rule prefers the room wall.
        assertTrue(1.25 * roomWall.distManhattan(portal)
            < 5 + 1.25 * corridorWall.distManhattan(portal));
        DungeonPathfinder.FrontierRoute route = DungeonPathfinder.findFrontier(
            grid, START, portal, Set.of(), 100);
        assertEquals(corridorWall, route.wall());
        assertTrue(route.priority() > 0 && route.priority() <= 1);
    }

    @Test
    void aFrontierThatDidNotOpenLosesPriorityAfterItsCooldown() {
        BlockPos east = new BlockPos(1, 0, 0);
        BlockPos west = new BlockPos(-1, 0, 0);
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) { return pos.equals(START); }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
            public List<BlockPos> frontierWalls(BlockPos pos, BlockPos target) {
                return List.of(east, west);
            }
        };
        BlockPos portal = new BlockPos(8, 0, 0);
        assertEquals(east, DungeonPathfinder.findFrontier(
            grid, START, portal, Set.of(), Map.of(), 100).wall());
        assertEquals(west, DungeonPathfinder.findFrontier(
            grid, START, portal, Set.of(), Map.of(east, 4), 100).wall());
    }

    @Test
    void continuesToTargetAfterBedrockOpens() {
        Set<BlockPos> walkable = new HashSet<>(Set.of(
            new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 0, 0)));
        BlockPos wall = new BlockPos(3, 0, 0);
        BlockPos target = new BlockPos(5, 0, 0);
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) { return walkable.contains(pos); }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
            public List<BlockPos> frontierWalls(BlockPos pos) {
                return pos.equals(new BlockPos(2, 0, 0)) && !walkable.contains(wall)
                    ? List.of(wall) : List.of();
            }
        };
        assertEquals(wall, DungeonPathfinder.findFrontier(grid, START, target, Set.of(), 100).wall());
        walkable.addAll(Set.of(wall, new BlockPos(4, 0, 0), target));
        assertEquals(target, DungeonPathfinder.find(grid, START, Set.of(target), 100).getLast());
    }

    @Test
    void exploresAcrossABedrockRoofTowardAnUndergroundTarget() {
        BlockPos undergroundTarget = new BlockPos(8, -5, 0);
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) {
                return pos.getY() == 0 && pos.getZ() == 0 && pos.getX() >= 0 && pos.getX() <= 5;
            }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
            public List<BlockPos> frontierWalls(BlockPos pos, BlockPos target) {
                return List.of(pos.below());
            }
        };
        DungeonPathfinder.FrontierRoute frontier = DungeonPathfinder.findFrontier(
            grid, START, undergroundTarget, Set.of(), 100);
        assertEquals(new BlockPos(5, -1, 0), frontier.wall());
        assertEquals(new BlockPos(5, 0, 0), frontier.blocks().getLast());
    }

    @Test
    void bedrockRoofAboveATargetDoesNotCountAsReached() {
        BlockPos target = new BlockPos(4, -2, 0);
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) {
                return pos.equals(new BlockPos(4, 0, 0)); // Standing on the roof, two blocks above the target.
            }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
        };
        assertTrue(TargetLines.goals(grid, target).isEmpty());
    }

    @Test
    void routesToTheBottomOfATallPortalWhenItsCenterIsTooHigh() {
        BlockPos bottom = new BlockPos(4, 0, 0);
        BlockPos center = bottom.above(3);
        Set<BlockPos> walkable = Set.of(START, new BlockPos(1, 0, 0),
            new BlockPos(2, 0, 0), new BlockPos(3, 0, 0), bottom);
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) { return walkable.contains(pos); }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
        };
        assertTrue(TargetLines.goals(grid, center).isEmpty());
        Set<BlockPos> goals = TargetLines.goals(grid, Set.of(bottom, center));
        assertEquals(new BlockPos(3, 0, 0),
            DungeonPathfinder.find(grid, START, goals, 100).getLast());
    }

    @Test
    void followsKnownCorridorTowardPortalBeforeExploringBedrock() {
        BlockPos portal = new BlockPos(8, 0, 0);
        Set<BlockPos> corridor = Set.of(START, new BlockPos(0, 0, 1),
            new BlockPos(1, 0, 1), new BlockPos(2, 0, 1), new BlockPos(3, 0, 1),
            new BlockPos(4, 0, 1), new BlockPos(5, 0, 1));
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) { return corridor.contains(pos); }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
            public List<BlockPos> frontierWalls(BlockPos pos, BlockPos target) {
                return pos.equals(START) ? List.of(new BlockPos(-1, 0, 0)) : List.of();
            }
        };
        assertTrue(DungeonPathfinder.find(grid, START, Set.of(portal), 100).isEmpty());
        assertEquals(new BlockPos(-1, 0, 0), DungeonPathfinder.findFrontier(
            grid, START, portal, Set.of(), 100).wall());
        DungeonPathfinder.ApproachRoute approach = DungeonPathfinder.findClosestApproach(
            grid, START, Set.of(portal), 100);
        assertEquals(new BlockPos(5, 0, 1), approach.blocks().getLast());
        assertTrue(approach.distance() < 24);
    }

    @Test
    void canReachAFrontierBeyondFortyEightWalkingBlocks() {
        BlockPos wall = new BlockPos(71, 0, 0);
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) {
                return pos.getY() == 0 && pos.getZ() == 0
                    && pos.getX() >= 0 && pos.getX() <= 70;
            }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
            public List<BlockPos> frontierWalls(BlockPos pos, BlockPos target) {
                return pos.equals(new BlockPos(70, 0, 0)) ? List.of(wall) : List.of();
            }
        };
        DungeonPathfinder.FrontierRoute route = DungeonPathfinder.findFrontier(
            grid, START, new BlockPos(80, 0, 0), Set.of(), 500);
        assertEquals(wall, route.wall());
        assertEquals(new BlockPos(70, 0, 0), route.blocks().getLast());
    }

    @Test
    void retryCooldownCoversTheSameBedrockWallSegment() {
        BlockPos firstWall = new BlockPos(0, 0, -1);
        BlockPos adjacentWall = new BlockPos(1, 0, -1);
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) {
                return pos.equals(START) || pos.equals(new BlockPos(1, 0, 0));
            }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
            public List<BlockPos> frontierWalls(BlockPos pos, BlockPos target) {
                if (pos.equals(START)) return List.of(firstWall);
                if (pos.equals(new BlockPos(1, 0, 0))) return List.of(adjacentWall);
                return List.of();
            }
        };
        assertEquals(firstWall, DungeonPathfinder.findFrontier(
            grid, START, new BlockPos(0, 0, -5), Set.of(), 100).wall());
        assertNull(DungeonPathfinder.findFrontier(
            grid, START, new BlockPos(0, 0, -5), Set.of(firstWall), 100));
    }

    @Test
    void findsEveryReachableExplorationBranch() {
        BlockPos eastWall = new BlockPos(3, 0, 0);
        BlockPos northWall = new BlockPos(0, 0, -3);
        Set<BlockPos> walkable = Set.of(START, new BlockPos(1, 0, 0),
            new BlockPos(2, 0, 0), new BlockPos(0, 0, -1), new BlockPos(0, 0, -2));
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) { return walkable.contains(pos); }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
            public List<BlockPos> frontierWalls(BlockPos pos, BlockPos target) {
                if (pos.equals(new BlockPos(2, 0, 0))) return List.of(eastWall);
                if (pos.equals(new BlockPos(0, 0, -2))) return List.of(northWall);
                return List.of();
            }
        };
        DungeonPathfinder.AllFrontiersSearch search = new DungeonPathfinder.AllFrontiersSearch(START);
        for (int i = 0; i < 10 && !search.complete(); i++) search.advance(grid, 1, 100);
        assertTrue(search.complete());
        assertEquals(Set.of(eastWall, northWall), search.results().stream()
            .map(DungeonPathfinder.ExplorationRoute::wall).collect(java.util.stream.Collectors.toSet()));
        assertTrue(search.results().stream().allMatch(route ->
            route.blocks().getFirst().equals(START) && walkable.contains(route.blocks().getLast())));
    }

    @Test
    void adjacentBedrockBlocksAreOneBranchButSeparatedWallsRemainDistinct() {
        Set<BlockPos> walkable = new HashSet<>();
        for (int z = 0; z <= 6; z++) walkable.add(new BlockPos(0, 0, z));
        DungeonPathfinder.Grid grid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) { return walkable.contains(pos); }
            public boolean canStep(BlockPos from, BlockPos to) { return true; }
            public List<BlockPos> frontierWalls(BlockPos pos, BlockPos target) {
                if (pos.getZ() <= 2 || pos.getZ() >= 5)
                    return List.of(pos.offset(1, 0, 0));
                return List.of();
            }
        };
        DungeonPathfinder.AllFrontiersSearch search = new DungeonPathfinder.AllFrontiersSearch(START);
        search.advance(grid, 100, 100);
        assertEquals(2, search.results().size());
    }
}
