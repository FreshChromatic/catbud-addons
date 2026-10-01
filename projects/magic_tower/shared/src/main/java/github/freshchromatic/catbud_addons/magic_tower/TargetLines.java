package github.freshchromatic.catbud_addons.magic_tower;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;


import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import github.freshchromatic.catbud_addons.catbud_core.HudGraphics;
import net.minecraft.client.multiplayer.ClientLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/** Independent walking routes to selected radar and ESP targets. */
final class TargetLines {
    private static final double MIN_AIM_DOT = Math.cos(Math.toRadians(6));
    private static final int MAX_VISITED = 12_000;
    private static final int FRONTIER_MAX_VISITED = 4_000;
    private static final int EXPLORATION_NODES_PER_TICK = 800;
    private static final long REFRESH_TICKS = 200;
    private static final long FRONTIER_WAIT_TICKS = 80;
    private static final long FRONTIER_RETRY_TICKS = 200;
    private static final Map<BlockPos, Route> ROUTES = new HashMap<>();
    private static final Set<BlockPos> REPORTED_ROUTE_FAILURES = new HashSet<>();
    private static final Set<BlockPos> REPORTED_APPROACHES = new HashSet<>();
    private static final Map<BlockPos, BlockPos> LAST_FRONTIERS = new HashMap<>();
    private static final Map<BlockPos, Map<BlockPos, Long>> FRONTIER_RETRY_AFTER = new HashMap<>();
    private static final Map<BlockPos, Map<BlockPos, Integer>> FRONTIER_FAILED_VISITS = new HashMap<>();
    private static final Map<BlockPos, Long> DIRTY_FRONTIERS = new HashMap<>();
    private static final Set<BlockPos> OBSERVED_WALKABLE = new HashSet<>();
    private static final LoadedTerrainMap TERRAIN = new LoadedTerrainMap();
    private static final EnumSet<WorldTargets.PortalType> SUPPRESSED_AUTO =
        EnumSet.noneOf(WorldTargets.PortalType.class);
    private static boolean manualExploration = true;
    private static List<Route> manualRoutes = List.of();
    private static DungeonPathfinder.AllFrontiersSearch manualSearch;
    private static BlockPos manualSearchOrigin;
    private static long lastManualSearchAt = -100;
    private static boolean manualTerrainDirty;
    private static long currentFloorEpoch = -1;
    private static Object currentLevel;
    private static long tickCount;
    private static long lastSearchTick = -5;
    private static BlockPos focusedTarget;
    private static boolean reportedNoStart;

    private record Route(List<BlockPos> blocks, long calculatedAt, int progress,
                         BlockPos frontierWall, long waitingSince, double priority,
                         boolean approaching) {
        Route(List<BlockPos> blocks, long calculatedAt, int progress,
              BlockPos frontierWall, long waitingSince, double priority) {
            this(blocks, calculatedAt, progress, frontierWall, waitingSince, priority, false);
        }
        boolean exploring() { return frontierWall != null; }
    }
    private record Candidate(BlockPos pos, double score, double distance, boolean hit) {}

    private static final class LevelGrid implements DungeonPathfinder.Grid {
        private final ClientLevel level;
        private final Map<BlockPos, Boolean> standable = new HashMap<>();

        private LevelGrid(ClientLevel level) { this.level = level; }

        @Override
        public boolean canStand(BlockPos pos) {
            return standable.computeIfAbsent(pos, key -> {
                if (!loaded(key)) return OBSERVED_WALKABLE.contains(key);
                Boolean known = TERRAIN.standable(key);
                boolean result = known != null ? known : LoadedTerrainMap.canStandLive(level, key);
                if (result) OBSERVED_WALKABLE.add(key.immutable());
                else OBSERVED_WALKABLE.remove(key);
                return result;
            });
        }

        @Override
        public boolean canStep(BlockPos from, BlockPos to) {
            if (to.getY() <= from.getY()) return true;
            return loaded(from.above(2)) ? LoadedTerrainMap.clear(level, from.above(2))
                : OBSERVED_WALKABLE.contains(from) && OBSERVED_WALKABLE.contains(to);
        }

        @Override
        public List<BlockPos> frontierWalls(BlockPos pos, BlockPos target) {
            ArrayList<BlockPos> result = new ArrayList<>();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(direction);
                if (!loaded(next) || canStand(next)
                    || (canStand(next.above()) && canStep(pos, next.above()))) continue;
                if (level.getBlockState(next).is(Blocks.BEDROCK)) result.add(next);
                else if (level.getBlockState(next.above()).is(Blocks.BEDROCK)) result.add(next.above());
            }
            if (target.getY() < pos.getY() && level.getBlockState(pos.below()).is(Blocks.BEDROCK))
                result.add(pos.below());
            if (target.getY() > pos.getY() && level.getBlockState(pos.above(2)).is(Blocks.BEDROCK))
                result.add(pos.above(2));
            return result;
        }

        private boolean loaded(BlockPos pos) {
            return LoadedTerrainMap.loaded(level, pos);
        }
    }

    private TargetLines() {}

    static boolean manualExplorationEnabled() { return manualExploration; }
    static int routeCount() { return (int) ROUTES.values().stream().filter(r -> !r.blocks().isEmpty()).count() + manualRoutes.size(); }
    static int[] terrainCounts() { return new int[] {TERRAIN.loadedChunks(), TERRAIN.scannedSections(), TERRAIN.pendingSections(), TERRAIN.standableCount()}; }
    static boolean autoSuppressed(WorldTargets.PortalType type) { return SUPPRESSED_AUTO.contains(type); }
    static void setAutoSuppressed(WorldTargets.PortalType type, boolean suppress) {
        if (suppress) SUPPRESSED_AUTO.add(type); else SUPPRESSED_AUTO.remove(type);
        WorldTargets targets = CatbudAddonsClient.session().targets();
        BlockPos pos = targets.autoPortalTargets().get(type);
        if (pos != null && !targets.lineTargets().contains(pos)) ROUTES.remove(pos);
        targets.removeAutoPortal(type);
        lastSearchTick = tickCount - 5;
    }
    static void onConfigurationChanged(boolean explorationChanged) {
        if (explorationChanged) { manualExploration = MagicTowerSettings.values().bool("routes.exploreDefault"); resetManualSearch(); }
        WorldTargets targets = CatbudAddonsClient.session().targets();
        targets.filterManualTargets(p -> MagicTowerSettings.selectable(targets, p));
        for (var type : WorldTargets.PortalType.values()) if (!MagicTowerSettings.autoPortal(type)) {
            BlockPos pos = targets.autoPortalTargets().get(type);
            if (pos != null && !targets.lineTargets().contains(pos)) ROUTES.remove(pos);
            targets.removeAutoPortal(type);
        }
        if (!MagicTowerSettings.routes()) {
            ROUTES.clear(); TERRAIN.clear(); OBSERVED_WALKABLE.clear(); resetManualSearch();
        }
        lastSearchTick = tickCount - 5;
    }

    static boolean setManualExploration(Minecraft client, TowerSession session, boolean enabled) {
        if (enabled && (client.level == null || client.player == null || !session.isActive())) return false;
        if (manualExploration == enabled) return true;
        manualExploration = enabled;
        resetManualSearch();
        return true;
    }

    private static void resetManualSearch() {
        manualRoutes = List.of();
        manualSearch = null;
        manualSearchOrigin = null;
        lastManualSearchAt = -100;
        manualTerrainDirty = true;
    }

    static Component terrainStatus() {
        return Component.translatable("message.catbud_addons.terrain_status",
            TERRAIN.loadedChunks(), TERRAIN.scannedSections(), TERRAIN.pendingSections(),
            TERRAIN.standableCount());
    }

    static void toggleLookedAt(Minecraft client, TowerSession session) {
        if (!MagicTowerSettings.routes() || !MagicTowerSettings.values().bool("routes.manual")) return;
        if (client.level == null || client.player == null) return;
        if (!session.isActive()) {
            message(client, "message.catbud_addons.inactive");
            return;
        }
        WorldTargets targets = session.targets();
        List<Candidate> candidates = aimedTargets(client, targets);
        if (candidates.isEmpty()) {
            message(client, "message.catbud_addons.no_target");
            return;
        }
        if (client.hasShiftDown()) {
            int index = focusedIndex(candidates);
            int next = (index + 1) % candidates.size();
            Candidate candidate = candidates.get(next);
            focusedTarget = candidate.pos();
            message(client, "message.catbud_addons.selected", next + 1, candidates.size(),
                name(targets, candidate.pos()), candidate.pos().getX(), candidate.pos().getY(),
                candidate.pos().getZ(), Math.round(candidate.distance()));
            return;
        }
        Candidate selected = candidates.get(focusedIndex(candidates));
        focusedTarget = selected.pos();
        WorldTargets.PortalType selectedPortal = targets.portalTypeAt(selected.pos());
        if (CatbudAddonsClient.autoPortalRoutesEnabled() && selectedPortal != null
            && autoEligible(session, selectedPortal)) {
            if (selected.pos().equals(targets.autoPortalTargets().get(selectedPortal))) {
                SUPPRESSED_AUTO.add(selectedPortal);
                targets.removeAutoPortal(selectedPortal);
                if (targets.lineTargets().contains(selected.pos()))
                    targets.toggleLine(selected.pos(), client.level.getBlockState(selected.pos()));
                ROUTES.remove(selected.pos());
                message(client, "message.catbud_addons.auto_portal_suppressed",
                    name(targets, selected.pos()), CatbudAddonsClient.targetKeyName());
                return;
            }
            if (SUPPRESSED_AUTO.remove(selectedPortal)) {
                lastSearchTick = tickCount - 5;
                message(client, "message.catbud_addons.auto_portal_resumed",
                    name(targets, selected.pos()));
                return;
            }
        }
        boolean wasSelected = targets.lineTargets().contains(selected.pos());
        boolean added = targets.toggleLine(selected.pos(), client.level.getBlockState(selected.pos()));
        ROUTES.remove(selected.pos());
        LAST_FRONTIERS.remove(selected.pos());
        FRONTIER_RETRY_AFTER.remove(selected.pos());
        FRONTIER_FAILED_VISITS.remove(selected.pos());
        REPORTED_APPROACHES.remove(selected.pos());
        lastSearchTick = tickCount - 5;
        if (wasSelected) {
            REPORTED_ROUTE_FAILURES.remove(selected.pos());
            message(client, "message.catbud_addons.unmarked", name(targets, selected.pos()),
                selected.pos().getX(), selected.pos().getY(), selected.pos().getZ());
        } else if (added) {
            REPORTED_ROUTE_FAILURES.remove(selected.pos());
            message(client, "message.catbud_addons.marked", name(targets, selected.pos()),
                selected.pos().getX(), selected.pos().getY(), selected.pos().getZ(),
                Math.round(selected.distance()));
        } else {
            message(client, "message.catbud_addons.target_changed");
        }
    }

    private static String name(WorldTargets targets, BlockPos pos) {
        return Component.translatable(targets.targetNameKey(pos)).getString();
    }

    private static void message(Minecraft client, String key, Object... args) {
        if (client.player == null) return;
        String mode = MagicTowerSettings.values().text("hints.messages");
        Component text = Component.literal("[Catbud] ").append(Component.translatable(key, args));
        if (mode.equals("chat")) ClientPlatform.chat(client, text);
        else if (mode.equals("actionbar")) ClientPlatform.actionbar(client, text);
    }

    static void onBlockChanged(BlockPos changed) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) TERRAIN.onBlockChanged(client.level, changed);
        OBSERVED_WALKABLE.remove(changed);
        OBSERVED_WALKABLE.remove(changed.above());
        OBSERVED_WALKABLE.remove(changed.below());
        manualTerrainDirty = true;
        if (client.level != null && !client.level.getBlockState(changed).is(Blocks.BEDROCK)) {
            for (Map<BlockPos, Integer> failed : FRONTIER_FAILED_VISITS.values())
                failed.remove(changed);
            for (Map<BlockPos, Long> retries : FRONTIER_RETRY_AFTER.values())
                retries.remove(changed);
        }
        for (Map.Entry<BlockPos, Route> entry : ROUTES.entrySet()) {
            Route route = entry.getValue();
            if (affectsRoute(route, changed) || entry.getKey().equals(changed))
                DIRTY_FRONTIERS.putIfAbsent(entry.getKey(), tickCount);
        }
    }

    static void onChunkChanged(ClientLevel level, int chunkX, int chunkZ) {
        TERRAIN.onChunkChanged(level, chunkX, chunkZ);
        for (Map.Entry<BlockPos, Route> entry : ROUTES.entrySet()) {
            if (entry.getValue().blocks().stream().anyMatch(pos ->
                pos.getX() >> 4 == chunkX && pos.getZ() >> 4 == chunkZ))
                DIRTY_FRONTIERS.putIfAbsent(entry.getKey(), tickCount);
        }
        manualTerrainDirty = true;
    }

    private static boolean affectsRoute(Route route, BlockPos changed) {
        if (changed.equals(route.frontierWall())) return true;
        for (int i = Math.max(0, route.progress()); i < route.blocks().size(); i++) {
            BlockPos step = route.blocks().get(i);
            if (changed.equals(step) || changed.equals(step.above()) || changed.equals(step.below()))
                return true;
        }
        return false;
    }

    private static int focusedIndex(List<Candidate> candidates) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).pos().equals(focusedTarget)) return i;
        }
        return 0;
    }

    private static List<Candidate> aimedTargets(Minecraft client, WorldTargets targets) {
        Camera camera = ClientPlatform.camera(client);
        Vec3 eye = ClientPlatform.position(camera);
        Vector3fc forward = ClientPlatform.forward(camera);
        Vec3 look = new Vec3(forward.x(), forward.y(), forward.z());
        BlockPos hitPos = client.hitResult instanceof BlockHitResult hit ? hit.getBlockPos() : null;
        Map<BlockPos, Vec3> positions = new HashMap<>();
        for (WorldTargets.PortalMarker portal : targets.portalMarkers()) {
            positions.put(portal.anchor(), portal.center());
        }
        for (WorldTargets.SpecialMarker special : targets.specialMarkers()) {
            positions.put(special.anchor(), special.center());
        }
        for (BlockPos pos : targets.chests()) positions.putIfAbsent(pos, Vec3.atCenterOf(pos));
        for (BlockPos pos : targets.mushrooms()) positions.putIfAbsent(pos, Vec3.atCenterOf(pos));
        if (hitPos != null && targets.containsTarget(hitPos))
            positions.putIfAbsent(hitPos, Vec3.atCenterOf(hitPos));
        List<Candidate> result = new ArrayList<>();
        for (Map.Entry<BlockPos, Vec3> entry : positions.entrySet()) {
            if (!MagicTowerSettings.selectable(targets, entry.getKey())) continue;
            double score = aimScore(eye, look, entry.getValue());
            boolean hit = entry.getKey().equals(hitPos);
            if (score >= MIN_AIM_DOT || hit)
                result.add(new Candidate(entry.getKey(), score, eye.distanceTo(entry.getValue()), hit));
        }
        result.sort((a, b) -> {
            if (a.hit() != b.hit()) return a.hit() ? -1 : 1;
            if (Math.abs(a.score() - b.score()) > 0.0001)
                return Double.compare(b.score(), a.score());
            return Double.compare(a.distance(), b.distance());
        });
        return result;
    }

    static void renderSelectionHint(HudGraphics graphics, Minecraft client, TowerSession session) {
        if (!session.isActive() || client.level == null || client.player == null) return;
        var settings = MagicTowerSettings.values();
        if (!MagicTowerSettings.routes() || !settings.bool("hints.enabled")) return;
        List<Candidate> candidates = aimedTargets(client, session.targets());
        int x = client.getWindow().getGuiScaledWidth() / 2;
        int y = client.getWindow().getGuiScaledHeight() / 2 + 24;
        if (!candidates.isEmpty()) {
            int index = focusedIndex(candidates);
            Candidate candidate = candidates.get(index);
            BlockPos pos = candidate.pos();
            String name = Component.translatable(session.targets().targetNameKey(pos)).getString();
            String selected = (index + 1) + "/" + candidates.size() + "  " + name;
            if (settings.bool("hints.distance")) selected += "  " + Math.round(candidate.distance()) + "m";
            if (settings.bool("hints.coordinates")) selected += "  (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
            graphics.centeredText(client.font, selected, x, y, MagicTowerSettings.routeColor(session.targets(), pos));
            y += 12;
            if (settings.bool("hints.controls")) {
                graphics.centeredText(client.font,
                    Component.translatable("hint.catbud_addons.cycle_target", CatbudAddonsClient.targetKeyName(), CatbudAddonsClient.targetKeyName()).getString(),
                    x, y, 0xFFE6E6E6);
                y += 12;
            }
        }
        Route focusedRoute = ROUTES.get(focusedTarget);
        if (settings.bool("hints.priority") && focusedRoute != null && focusedRoute.exploring()
            && (session.targets().lineTargets().contains(focusedTarget)
                || session.targets().autoPortalTargets().containsValue(focusedTarget)))
            graphics.centeredText(client.font,
                Component.translatable("hint.catbud_addons.exploration_priority",
                    name(session.targets(), focusedTarget),
                    String.format(java.util.Locale.ROOT, "%.2f", focusedRoute.priority())).getString(),
                x, y, MagicTowerSettings.routeColor(session.targets(), focusedTarget));
        if (settings.bool("hints.exploreCount") && manualExploration && !manualRoutes.isEmpty())
            graphics.centeredText(client.font,
                Component.translatable("hint.catbud_addons.manual_exploring", manualRoutes.size()).getString(),
                x, y + 12, settings.color("routes.color.explore", "routes.opacity"));
    }

    static double aimScore(Vec3 eye, Vec3 look, Vec3 target) {
        Vec3 delta = target.subtract(eye);
        double length = delta.length();
        return length < 0.001 ? -1 : look.dot(delta) / length;
    }

    static void onAutoSettingChanged() {
        WorldTargets targets = CatbudAddonsClient.session().targets();
        for (BlockPos pos : targets.autoPortalTargets().values()) {
            if (!targets.lineTargets().contains(pos)) ROUTES.remove(pos);
        }
        targets.clearAutoPortals();
        SUPPRESSED_AUTO.clear();
        REPORTED_APPROACHES.clear();
        lastSearchTick = tickCount - 5;
    }

    private static void tickManualExploration(LevelGrid grid, BlockPos start) {
        if (!manualExploration) return;
        if (manualSearch == null) {
            if (!manualTerrainDirty && tickCount - lastManualSearchAt < 100
                && manualSearchOrigin != null && start.distManhattan(manualSearchOrigin) < 8) return;
            manualSearch = new DungeonPathfinder.AllFrontiersSearch(start);
            manualTerrainDirty = false;
        }
        DungeonPathfinder.Grid loadedGrid = new DungeonPathfinder.Grid() {
            public boolean canStand(BlockPos pos) {
                return grid.loaded(pos) && grid.canStand(pos);
            }
            public boolean canStep(BlockPos from, BlockPos to) {
                return grid.canStep(from, to);
            }
            public List<BlockPos> frontierWalls(BlockPos pos, BlockPos target) {
                return grid.frontierWalls(pos, target);
            }
        };
        int limit = Math.min(250_000, Math.max(50_000, TERRAIN.standableCount() + 1_024));
        manualSearch.advance(loadedGrid, EXPLORATION_NODES_PER_TICK, limit);
        if (!manualSearch.complete()) return;
        manualRoutes = manualSearch.results().stream()
            .filter(result -> grid.loaded(result.wall())
                && grid.level.getBlockState(result.wall()).is(Blocks.BEDROCK))
            .map(result -> new Route(result.blocks(), tickCount, 0, result.wall(), -1, 0))
            .toList();
        manualSearchOrigin = manualSearch.origin();
        manualSearch = null;
        lastManualSearchAt = tickCount;
    }

    private static boolean autoEligible(TowerSession session, WorldTargets.PortalType type) {
        return MagicTowerSettings.autoPortal(type) && (type == WorldTargets.PortalType.NETHER
            || session.evacuationStatus() == TowerSession.EvacuationStatus.PRESENT);
    }

    private static void reconcileAutoPortals(Minecraft client, TowerSession session) {
        WorldTargets targets = session.targets();
        if (!CatbudAddonsClient.autoPortalRoutesEnabled()) {
            targets.clearAutoPortals();
            return;
        }
        for (WorldTargets.PortalType type : WorldTargets.PortalType.values()) {
            if (!autoEligible(session, type) || SUPPRESSED_AUTO.contains(type)) {
                targets.removeAutoPortal(type);
                continue;
            }
            if (targets.autoPortalTargets().containsKey(type)) continue;
            WorldTargets.PortalMarker discovered = targets.portalMarkers().stream()
                .filter(marker -> marker.type() == type)
                .min((a, b) -> Double.compare(
                    a.center().distanceToSqr(client.player.position()),
                    b.center().distanceToSqr(client.player.position())))
                .orElse(null);
            if (discovered != null) targets.setAutoPortal(type, discovered.anchor());
            else targets.observedPortals().entrySet().stream()
                .filter(entry -> entry.getValue() == type)
                .min((a, b) -> Double.compare(
                    a.getKey().distToCenterSqr(client.player.position()),
                    b.getKey().distToCenterSqr(client.player.position())))
                .ifPresent(entry -> targets.setAutoPortal(type, entry.getKey()));
        }
    }

    static void tick(Minecraft client, TowerSession session) {
        if (!session.isActive() || client.level == null || client.player == null) {
            ROUTES.clear();
            REPORTED_ROUTE_FAILURES.clear();
            REPORTED_APPROACHES.clear();
            LAST_FRONTIERS.clear();
            FRONTIER_RETRY_AFTER.clear();
            FRONTIER_FAILED_VISITS.clear();
            OBSERVED_WALKABLE.clear();
            TERRAIN.clear();
            DIRTY_FRONTIERS.clear();
            currentLevel = null;
            lastSearchTick = tickCount - 5;
            focusedTarget = null;
            reportedNoStart = false;
            manualExploration = MagicTowerSettings.values().bool("routes.exploreDefault");
            resetManualSearch();
            onAutoSettingChanged();
            currentFloorEpoch = -1;
            return;
        }
        if (currentLevel != client.level || currentFloorEpoch != session.floorEpoch()) {
            ROUTES.clear();
            REPORTED_ROUTE_FAILURES.clear();
            REPORTED_APPROACHES.clear();
            LAST_FRONTIERS.clear();
            FRONTIER_RETRY_AFTER.clear();
            FRONTIER_FAILED_VISITS.clear();
            OBSERVED_WALKABLE.clear();
            TERRAIN.clear();
            DIRTY_FRONTIERS.clear();
            currentLevel = client.level;
            lastSearchTick = tickCount - 5;
            focusedTarget = null;
            reportedNoStart = false;
            manualExploration = MagicTowerSettings.values().bool("routes.exploreDefault");
            resetManualSearch();
            onAutoSettingChanged();
            currentFloorEpoch = session.floorEpoch();
        }
        if (!MagicTowerSettings.routes()) return;
        tickCount++;
        TERRAIN.tick(client.level, client.player.blockPosition(),
            client.options.getEffectiveRenderDistance());
        reconcileAutoPortals(client, session);
        boolean openedFrontier = false;
        for (Map<BlockPos, Long> retries : FRONTIER_RETRY_AFTER.values())
            openedFrontier |= retries.keySet().removeIf(wall ->
                client.level.getChunkSource().hasChunk(wall.getX() >> 4, wall.getZ() >> 4)
                    && !client.level.getBlockState(wall).is(Blocks.BEDROCK));
        if (openedFrontier) {
            ROUTES.entrySet().removeIf(entry -> entry.getValue().blocks().isEmpty());
            lastSearchTick = tickCount - 5;
        }
        Set<BlockPos> selected = new HashSet<>(session.targets().lineTargets());
        selected.addAll(session.targets().autoPortalTargets().values());
        ROUTES.keySet().retainAll(selected);
        REPORTED_ROUTE_FAILURES.retainAll(selected);
        REPORTED_APPROACHES.retainAll(selected);
        LAST_FRONTIERS.keySet().retainAll(selected);
        FRONTIER_RETRY_AFTER.keySet().retainAll(selected);
        FRONTIER_FAILED_VISITS.keySet().retainAll(selected);
        DIRTY_FRONTIERS.keySet().retainAll(selected);
        for (Map<BlockPos, Long> retries : FRONTIER_RETRY_AFTER.values())
            retries.entrySet().removeIf(entry -> entry.getValue() <= tickCount);
        if (selected.isEmpty() && !manualExploration) {
            OBSERVED_WALKABLE.clear();
            reportedNoStart = false;
            return;
        }
        LevelGrid grid = new LevelGrid(client.level);
        BlockPos start = standingStart(grid, client.player.blockPosition());
        if (start == null) {
            if (!reportedNoStart) message(client, "message.catbud_addons.no_start");
            reportedNoStart = true;
            return;
        }
        reportedNoStart = false;
        for (BlockPos target : selected) {
            Route route = ROUTES.get(target);
            Long dirtySince = DIRTY_FRONTIERS.get(target);
            if (dirtySince != null && tickCount - dirtySince >= 5) {
                DIRTY_FRONTIERS.remove(target);
                int progress = route == null ? -1 : advance(start, route);
                if (progress < 0 || !pathClearToEnd(grid, route.blocks(), progress)
                    || route.exploring()
                        && !client.level.getBlockState(route.frontierWall()).is(Blocks.BEDROCK)) {
                    ROUTES.remove(target);
                    route = null;
                }
            }
            if (route != null && route.exploring()) {
                if (!client.level.getBlockState(route.frontierWall()).is(Blocks.BEDROCK)) {
                    ROUTES.remove(target);
                    route = null;
                    lastSearchTick = tickCount - 5;
                } else if (!route.blocks().isEmpty()
                    && start.distManhattan(route.blocks().getLast()) <= 1) {
                    long waitingSince = route.waitingSince() < 0 ? tickCount : route.waitingSince();
                    if (tickCount - waitingSince < FRONTIER_WAIT_TICKS) {
                        ROUTES.put(target, new Route(route.blocks(), route.calculatedAt(),
                            route.blocks().size() - 1, route.frontierWall(), waitingSince,
                            route.priority(), route.approaching()));
                        continue;
                    }
                    FRONTIER_RETRY_AFTER.computeIfAbsent(target, ignored -> new HashMap<>())
                        .put(route.frontierWall(), tickCount + FRONTIER_RETRY_TICKS);
                    FRONTIER_FAILED_VISITS.computeIfAbsent(target, ignored -> new HashMap<>())
                        .merge(route.frontierWall(), 1, Integer::sum);
                    ROUTES.remove(target);
                    route = null;
                    lastSearchTick = tickCount - 5;
                }
            }
            if (route != null && route.approaching() && !route.blocks().isEmpty()
                && start.distManhattan(route.blocks().getLast()) <= 1) {
                long waitingSince = route.waitingSince() < 0 ? tickCount : route.waitingSince();
                if (tickCount - waitingSince < FRONTIER_WAIT_TICKS) {
                    ROUTES.put(target, new Route(route.blocks(), route.calculatedAt(),
                        route.blocks().size() - 1, null, waitingSince, route.priority(), true));
                    continue;
                }
                route = null;
            }
            if (route != null && (route.exploring() || route.approaching()
                || tickCount - route.calculatedAt() < REFRESH_TICKS)) {
                if (route.blocks().isEmpty()) continue;
                int progress = advance(start, route);
                if (progress >= 0 && pathClear(grid, route.blocks(), progress)) {
                    ROUTES.put(target, new Route(route.blocks(), route.calculatedAt(), progress,
                        route.frontierWall(), route.waitingSince(), route.priority(), route.approaching()));
                    continue;
                }
            }
            if (tickCount - lastSearchTick < 5) continue;
            Set<BlockPos> goals = session.targets().portalTypeAt(target) == null
                ? goals(grid, target)
                : goals(grid, session.targets().portalBlocksFor(target));
            List<BlockPos> blocks = DungeonPathfinder.find(grid, start, goals, MAX_VISITED);
            if (!blocks.isEmpty()) {
                ROUTES.put(target, new Route(blocks, tickCount, 0, null, -1, 0));
                boolean hadFailure = REPORTED_ROUTE_FAILURES.remove(target);
                REPORTED_APPROACHES.remove(target);
                boolean hadFrontier = LAST_FRONTIERS.remove(target) != null;
                if (hadFailure || hadFrontier)
                    message(client, "message.catbud_addons.route_restored", name(session.targets(), target),
                        target.getX(), target.getY(), target.getZ());
            } else {
                Set<BlockPos> portalBlocks = session.targets().portalTypeAt(target) == null
                    ? Set.of(target) : session.targets().portalBlocksFor(target);
                DungeonPathfinder.ApproachRoute approach = DungeonPathfinder.findClosestApproach(
                    grid, start, portalBlocks, MAX_VISITED);
                if (approach != null) {
                    ROUTES.put(target, new Route(approach.blocks(), tickCount, 0,
                        null, -1, 0, true));
                    REPORTED_ROUTE_FAILURES.remove(target);
                    LAST_FRONTIERS.remove(target);
                    if (REPORTED_APPROACHES.add(target))
                        message(client, "message.catbud_addons.approaching", name(session.targets(), target));
                    lastSearchTick = tickCount;
                    break;
                }
                REPORTED_APPROACHES.remove(target);
                DungeonPathfinder.FrontierRoute frontier = DungeonPathfinder.findFrontier(
                    grid, start, target,
                    FRONTIER_RETRY_AFTER.getOrDefault(target, Map.of()).keySet(),
                    FRONTIER_FAILED_VISITS.getOrDefault(target, Map.of()), FRONTIER_MAX_VISITED);
                if (frontier == null && !FRONTIER_RETRY_AFTER.getOrDefault(target, Map.of()).isEmpty())
                    frontier = DungeonPathfinder.findFrontier(grid, start, target, Set.of(),
                        FRONTIER_FAILED_VISITS.getOrDefault(target, Map.of()), FRONTIER_MAX_VISITED);
                if (frontier != null) {
                    ROUTES.put(target, new Route(frontier.blocks(), tickCount, 0, frontier.wall(), -1,
                        frontier.priority()));
                    REPORTED_ROUTE_FAILURES.remove(target);
                    BlockPos previousWall = LAST_FRONTIERS.put(target, frontier.wall());
                    if (previousWall == null || previousWall.distManhattan(frontier.wall()) > 8)
                        message(client, "message.catbud_addons.exploring", name(session.targets(), target),
                            frontier.wall().getX(), frontier.wall().getY(), frontier.wall().getZ(),
                            String.format(java.util.Locale.ROOT, "%.2f", frontier.priority()));
                } else {
                    ROUTES.put(target, new Route(List.of(), tickCount, 0, null, -1, 0));
                    if (REPORTED_ROUTE_FAILURES.add(target)) {
                        CatbudAddonsClient.LOGGER.debug("路線搜尋失敗：起點 " + start.toShortString()
                            + "，目標 " + target.toShortString() + "，可站立入口 " + goals.size());
                        message(client, goals.isEmpty()
                                && session.targets().portalTypeAt(target) != null
                                ? "message.catbud_addons.no_portal_approach"
                                : "message.catbud_addons.no_frontier",
                            name(session.targets(), target));
                    }
                }
            }
            lastSearchTick = tickCount;
            break; // Bound route computation to one target per game tick.
        }
        tickManualExploration(grid, start);
    }

    private static BlockPos standingStart(LevelGrid grid, BlockPos feet) {
        if (grid.canStand(feet)) return feet;
        for (int dy : new int[] {1, -1, 2, -2}) {
            BlockPos candidate = feet.offset(0, dy, 0);
            if (grid.canStand(candidate)) return candidate;
        }
        return null;
    }

    static Set<BlockPos> goals(DungeonPathfinder.Grid grid, BlockPos target) {
        return goals(grid, Set.of(target));
    }

    static Set<BlockPos> goals(DungeonPathfinder.Grid grid, Set<BlockPos> targets) {
        Set<BlockPos> result = new HashSet<>();
        for (BlockPos target : targets) {
            ArrayList<BlockPos> columns = new ArrayList<>();
            columns.add(target);
            for (Direction direction : Direction.Plane.HORIZONTAL) columns.add(target.relative(direction));
            for (BlockPos column : columns) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = column.offset(0, dy, 0);
                    if (grid.canStand(candidate)) result.add(candidate);
                }
            }
        }
        return result;
    }

    private static int advance(BlockPos player, Route route) {
        int progress = route.progress();
        int end = Math.min(route.blocks().size() - 1, progress + 4);
        int nearest = -1;
        for (int i = progress; i <= end; i++) {
            if (route.blocks().get(i).distManhattan(player) <= 1) nearest = i;
        }
        return nearest;
    }

    private static boolean pathClear(LevelGrid grid, List<BlockPos> route, int progress) {
        int end = Math.min(route.size() - 1, progress + 4);
        for (int i = progress + 1; i <= end; i++) {
            if (!grid.canStand(route.get(i)) || !grid.canStep(route.get(i - 1), route.get(i))) return false;
        }
        return true;
    }

    private static boolean pathClearToEnd(LevelGrid grid, List<BlockPos> route, int progress) {
        for (int i = progress + 1; i < route.size(); i++)
            if (!grid.canStand(route.get(i)) || !grid.canStep(route.get(i - 1), route.get(i)))
                return false;
        return true;
    }

    static void render(RouteRenderContext context, Minecraft client, TowerSession session) {
        if (!MagicTowerSettings.routes()) return;
        if (!session.isActive() || client.level == null || client.player == null) return;
        if (ROUTES.isEmpty() && manualRoutes.isEmpty()) return;
        Vec3 camera = context.camera();
        Vec3 player = client.player.position().add(0, 0.2, 0);
        Set<BlockPos> selected = new HashSet<>(session.targets().lineTargets());
        selected.addAll(session.targets().autoPortalTargets().values());
        for (Map.Entry<BlockPos, Route> entry : ROUTES.entrySet()) {
            if (!selected.contains(entry.getKey())) continue;
            drawRoute(context, camera, player, entry.getValue(), entry.getKey(),
                MagicTowerSettings.routeColor(session.targets(), entry.getKey()));
        }
        if (manualExploration)
            for (Route route : manualRoutes) {
                if (route.blocks().isEmpty() || !client.level.getBlockState(route.frontierWall()).is(Blocks.BEDROCK))
                    continue;
                Vec3 routeStart = Vec3.atBottomCenterOf(route.blocks().getFirst()).add(0, 0.2, 0);
                drawRoute(context, camera, routeStart, route, null, MagicTowerSettings.values().color("routes.color.explore", "routes.opacity"));
            }
    }

    private static void drawRoute(RouteRenderContext context, Vec3 camera, Vec3 player,
                                  Route path, BlockPos target, int color) {
        List<BlockPos> route = path.blocks();
        if (route.isEmpty()) return;
        List<Vec3> points = new ArrayList<>();
        points.add(player);
        for (int i = Math.min(path.progress() + 1, route.size()); i < route.size(); i++)
            points.add(Vec3.atBottomCenterOf(route.get(i)).add(0, 0.2, 0));
        if (path.exploring() || path.approaching() || target == null)
            points.add(Vec3.atBottomCenterOf(route.getLast()).add(0, 0.85, 0));
        else points.add(Vec3.atCenterOf(target));
        context.draw(points, color, (float) MagicTowerSettings.values().number("routes.width"));
    }

}
