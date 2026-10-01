package github.freshchromatic.catbud_addons.magic_tower;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

public final class WorldTargets {
    public enum PortalType { NETHER, END }
    public enum SpecialType { COIN_BANK, ENCHANTMENT_EXTRACTION_TABLE }
    public record PortalMarker(Vec3 center, PortalType type, BlockPos anchor) {}
    public record SpecialMarker(Vec3 center, SpecialType type, BlockPos anchor) {}

    private static final int SCANS_PER_TICK = 16;
    private static final int NEAR_REFRESH_TICKS = 20;
    private static final int FAR_REFRESH_TICKS = 200;
    private final Map<Long, LevelChunk> scanned = new HashMap<>();
    private final Map<Long, Long> lastScannedTick = new HashMap<>();
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    private final Set<Long> pendingSet = new HashSet<>();
    private final Map<BlockPos, PortalType> portals = new HashMap<>();
    private final Map<BlockPos, PortalType> observedPortals = new HashMap<>();
    private final Set<BlockPos> chests = new HashSet<>();
    private final Set<BlockPos> mushrooms = new HashSet<>();
    private final Map<BlockPos, SpecialType> specials = new HashMap<>();
    private final Map<BlockPos, BlockState> lineTargets = new HashMap<>();
    private final Map<BlockPos, PortalType> pinnedPortals = new HashMap<>();
    private final Map<PortalType, BlockPos> autoPortals = new EnumMap<>(PortalType.class);
    private final Set<BlockPos> usedChests = new HashSet<>();
    private int previousPortalCount;
    private int previousChestCount;
    private long lastCountLog;
    private long tickCount;

    public Set<BlockPos> chests() { return Set.copyOf(chests); }
    public int targetCount(String type) {
        return switch (type) {
            case "nether" -> (int) portalMarkers().stream().filter(p -> p.type() == PortalType.NETHER).count();
            case "end" -> (int) portalMarkers().stream().filter(p -> p.type() == PortalType.END).count();
            case "coin" -> (int) specials.values().stream().filter(s -> s == SpecialType.COIN_BANK).count();
            case "enchantment" -> (int) specials.values().stream().filter(s -> s == SpecialType.ENCHANTMENT_EXTRACTION_TABLE).count();
            case "chest" -> chests.size();
            default -> (int) mushrooms.stream().filter(p -> !specials.containsKey(p)).count();
        };
    }
    public void filterManualTargets(java.util.function.Predicate<BlockPos> keep) {
        lineTargets.keySet().removeIf(p -> !keep.test(p));
        pinnedPortals.keySet().retainAll(lineTargets.keySet());
    }
    public Set<BlockPos> mushrooms() { return Set.copyOf(mushrooms); }
    public Set<BlockPos> lineTargets() { return Set.copyOf(lineTargets.keySet()); }
    public Map<PortalType, BlockPos> autoPortalTargets() { return Map.copyOf(autoPortals); }
    public Map<BlockPos, PortalType> observedPortals() { return Map.copyOf(observedPortals); }
    public PortalType portalTypeAt(BlockPos pos) { return rememberedPortalType(pos); }
    public Set<BlockPos> portalBlocksFor(BlockPos anchor) {
        PortalType type = rememberedPortalType(anchor);
        if (type == null || observedPortals.get(anchor) != type) return Set.of(anchor);
        Set<BlockPos> connected = new HashSet<>();
        ArrayDeque<BlockPos> pendingBlocks = new ArrayDeque<>();
        connected.add(anchor);
        pendingBlocks.add(anchor);
        while (!pendingBlocks.isEmpty()) {
            BlockPos current = pendingBlocks.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (observedPortals.get(neighbor) == type && connected.add(neighbor))
                    pendingBlocks.addLast(neighbor);
            }
        }
        return Set.copyOf(connected);
    }
    public void setAutoPortal(PortalType type, BlockPos pos) { autoPortals.put(type, pos.immutable()); }
    public void removeAutoPortal(PortalType type) { autoPortals.remove(type); }
    public void clearAutoPortals() { autoPortals.clear(); }
    public boolean containsTarget(BlockPos pos) { return isTracked(pos); }
    public int lineColor(BlockPos pos) {
        SpecialType special = specials.get(pos);
        if (special == SpecialType.COIN_BANK) return 0xFFFFD052;
        if (special == SpecialType.ENCHANTMENT_EXTRACTION_TABLE) return 0xFF62D1FF;
        PortalType portal = rememberedPortalType(pos);
        if (portal == PortalType.NETHER) return 0xFFB36AE8;
        if (portal == PortalType.END) return 0xFF66D8E9;
        if (chests.contains(pos)) return 0xFFFFB347;
        return 0xFFB875FF;
    }
    public String targetNameKey(BlockPos pos) {
        SpecialType special = specials.get(pos);
        if (special == SpecialType.COIN_BANK) return "target.catbud_addons.coin_bank";
        if (special == SpecialType.ENCHANTMENT_EXTRACTION_TABLE)
            return "target.catbud_addons.enchantment_extraction_table";
        PortalType portal = rememberedPortalType(pos);
        if (portal == PortalType.NETHER) return "target.catbud_addons.nether_portal";
        if (portal == PortalType.END) return "target.catbud_addons.end_portal";
        if (chests.contains(pos)) return "target.catbud_addons.copper_chest";
        return "target.catbud_addons.red_mushroom_block";
    }
    public boolean toggleLine(BlockPos pos, BlockState state) {
        if (lineTargets.remove(pos) != null) {
            pinnedPortals.remove(pos);
            return false;
        }
        if (!isTracked(pos)) return false;
        lineTargets.put(pos.immutable(), state);
        PortalType portal = portals.get(pos);
        if (portal != null) pinnedPortals.put(pos.immutable(), portal);
        return true;
    }
    public List<SpecialMarker> specialMarkers() {
        return specials.entrySet().stream()
            .map(entry -> new SpecialMarker(Vec3.atCenterOf(entry.getKey()), entry.getValue(), entry.getKey()))
            .toList();
    }

    public void clear() {
        scanned.clear();
        lastScannedTick.clear();
        pending.clear();
        pendingSet.clear();
        portals.clear();
        observedPortals.clear();
        chests.clear();
        mushrooms.clear();
        specials.clear();
        lineTargets.clear();
        pinnedPortals.clear();
        autoPortals.clear();
        usedChests.clear();
        previousPortalCount = 0;
        previousChestCount = 0;
        lastCountLog = 0;
        tickCount = 0;
    }

    public void tick(ClientLevel level, BlockPos playerPos, int radius) {
        tickCount++;
        int centerX = playerPos.getX() >> 4;
        int centerZ = playerPos.getZ() >> 4;
        // Discover the nearest chunks first. The effective view distance also respects the server limit.
        for (int ring = 0; ring <= radius; ring++) {
            for (int x = centerX - ring; x <= centerX + ring; x++) {
                for (int z = centerZ - ring; z <= centerZ + ring; z++) {
                    if (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) != ring) continue;
                    long key = chunkKey(x, z);
                    LevelChunk loaded = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    LevelChunk old = scanned.get(key);
                    if (old != null && old != loaded) {
                        scanned.remove(key);
                        lastScannedTick.remove(key);
                        removeChunk(x, z);
                    }
                    int refreshInterval = ring <= 2 ? NEAR_REFRESH_TICKS : FAR_REFRESH_TICKS;
                    if (loaded != null && (old != loaded
                        || tickCount - lastScannedTick.getOrDefault(key, 0L) >= refreshInterval)) {
                        queueChunk(key);
                    }
                }
            }
        }
        // Targets outside the scan radius, or in chunks that unloaded, must never remain visible.
        for (long key : List.copyOf(scanned.keySet())) {
            int x = (int) (key >> 32);
            int z = (int) key;
            if (Math.abs(x - centerX) > radius || Math.abs(z - centerZ) > radius
                || level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != scanned.get(key)) {
                scanned.remove(key);
                lastScannedTick.remove(key);
                removeChunk(x, z);
            }
        }
        for (int i = 0; i < SCANS_PER_TICK && !pending.isEmpty(); i++) {
            long key = pending.removeFirst();
            pendingSet.remove(key);
            int x = (int) (key >> 32);
            int z = (int) key;
            if (Math.abs(x - centerX) > radius || Math.abs(z - centerZ) > radius) continue;
            LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            scanChunk(level, chunk, x, z);
            scanned.put(key, chunk);
            lastScannedTick.put(key, tickCount);
        }
        validateTracked(level);
        logCountChanges();
    }

    public void onBlockChanged(ClientLevel level, BlockPos position) {
        if (!scanned.containsKey(chunkKey(position.getX() >> 4, position.getZ() >> 4))) return;
        BlockPos pos = position.immutable();
        boolean wasChest = chests.remove(pos);
        portals.remove(pos);
        observedPortals.remove(pos);
        mushrooms.remove(pos);
        specials.remove(pos);
        BlockState state = level.getBlockState(pos);
        if (wasChest && !isChest(state)) {
            usedChests.add(pos);
            CatbudAddonsClient.LOGGER.debug("銅箱已變更並排除：" + pos.toShortString());
        }
        if (isChest(state) && !usedChests.contains(pos)) chests.add(pos);
        PortalType type = portalType(state);
        if (type != null) {
            portals.put(pos, type);
            observedPortals.put(pos, type);
        }
        if (isMushroom(state)) mushrooms.add(pos);
        SpecialType special = specialType(state);
        if (special != null) specials.put(pos, special);
        if (lineTargets.containsKey(pos) && (!lineTargets.get(pos).equals(state) || !isTracked(pos))) {
            lineTargets.remove(pos);
            pinnedPortals.remove(pos);
        }
        autoPortals.entrySet().removeIf(entry -> entry.getValue().equals(pos) && portals.get(pos) != entry.getKey());
        logCountChanges();
    }

    public void onChunkChanged(int chunkX, int chunkZ) {
        queueChunk(chunkKey(chunkX, chunkZ));
    }

    private void queueChunk(long key) {
        if (pendingSet.add(key)) pending.addLast(key);
    }

    private void scanChunk(ClientLevel level, LevelChunk chunk, int chunkX, int chunkZ) {
        Map<BlockPos, PortalType> foundPortals = new HashMap<>();
        Set<BlockPos> foundChests = new HashSet<>();
        Set<BlockPos> foundMushrooms = new HashSet<>();
        Map<BlockPos, SpecialType> foundSpecials = new HashMap<>();
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || !section.maybeHas(WorldTargets::isTarget)) continue;
            int baseY = level.getSectionYFromSectionIndex(sectionIndex) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!isTarget(state)) continue;
                        BlockPos pos = new BlockPos((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z);
                        PortalType portal = portalType(state);
                        if (portal != null) foundPortals.put(pos, portal);
                        else if (isChest(state)) foundChests.add(pos);
                        else {
                            foundMushrooms.add(pos);
                            SpecialType special = specialType(state);
                            if (special != null) foundSpecials.put(pos, special);
                        }
                    }
                }
            }
        }
        // A server can replace the contents of an existing chunk without replacing its object.
        portals.keySet().removeIf(pos -> inChunk(pos, chunkX, chunkZ) && !foundPortals.containsKey(pos));
        portals.putAll(foundPortals);
        observedPortals.keySet().removeIf(pos -> inChunk(pos, chunkX, chunkZ) && !foundPortals.containsKey(pos));
        observedPortals.putAll(foundPortals);
        mushrooms.removeIf(pos -> inChunk(pos, chunkX, chunkZ) && !foundMushrooms.contains(pos));
        mushrooms.addAll(foundMushrooms);
        specials.keySet().removeIf(pos -> inChunk(pos, chunkX, chunkZ));
        specials.putAll(foundSpecials);
        for (BlockPos pos : List.copyOf(chests)) {
            if (inChunk(pos, chunkX, chunkZ) && !foundChests.contains(pos)) {
                chests.remove(pos);
                usedChests.add(pos);
                CatbudAddonsClient.LOGGER.debug("銅箱已變更並排除：" + pos.toShortString());
            }
        }
        for (BlockPos pos : foundChests) {
            if (!usedChests.contains(pos)) chests.add(pos);
        }
    }

    private void validateTracked(ClientLevel level) {
        for (BlockPos pos : List.copyOf(chests)) {
            if (!isChest(level.getBlockState(pos))) {
                chests.remove(pos);
                usedChests.add(pos);
                CatbudAddonsClient.LOGGER.debug("銅箱已變更並排除：" + pos.toShortString());
            }
        }
        for (BlockPos pos : List.copyOf(portals.keySet())) {
            PortalType type = portalType(level.getBlockState(pos));
            if (type == null) {
                portals.remove(pos);
                observedPortals.remove(pos);
            } else {
                portals.put(pos, type);
                observedPortals.put(pos, type);
            }
        }
        for (BlockPos pos : List.copyOf(mushrooms)) {
            BlockState state = level.getBlockState(pos);
            if (!isMushroom(state)) mushrooms.remove(pos);
            SpecialType special = specialType(state);
            if (special == null) specials.remove(pos);
            else specials.put(pos, special);
        }
        lineTargets.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            // A selected portal can stay pinned while its chunk is temporarily unloaded.
            if (pinnedPortals.containsKey(pos) && !scanned.containsKey(chunkKey(pos.getX() >> 4, pos.getZ() >> 4)))
                return false;
            boolean invalid = !isTracked(pos) || !entry.getValue().equals(level.getBlockState(pos));
            if (invalid) pinnedPortals.remove(pos);
            return invalid;
        });
        autoPortals.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getValue();
            return scanned.containsKey(chunkKey(pos.getX() >> 4, pos.getZ() >> 4))
                && portals.get(pos) != entry.getKey();
        });
    }

    private void removeChunk(int x, int z) {
        portals.keySet().removeIf(pos -> (pos.getX() >> 4) == x && (pos.getZ() >> 4) == z);
        chests.removeIf(pos -> (pos.getX() >> 4) == x && (pos.getZ() >> 4) == z);
        mushrooms.removeIf(pos -> inChunk(pos, x, z));
        specials.keySet().removeIf(pos -> inChunk(pos, x, z));
        lineTargets.keySet().removeIf(pos -> inChunk(pos, x, z) && !pinnedPortals.containsKey(pos));
    }

    public List<PortalMarker> portalMarkers() {
        ArrayList<PortalMarker> result = new ArrayList<>();
        Set<BlockPos> remaining = new HashSet<>(portals.keySet());
        while (!remaining.isEmpty()) {
            BlockPos first = remaining.iterator().next();
            PortalType type = portals.get(first);
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            remaining.remove(first);
            queue.add(first);
            double x = 0, y = 0, z = 0;
            int count = 0;
            ArrayList<BlockPos> members = new ArrayList<>();
            while (!queue.isEmpty()) {
                BlockPos pos = queue.removeFirst();
                members.add(pos);
                x += pos.getX() + 0.5;
                y += pos.getY() + 0.5;
                z += pos.getZ() + 0.5;
                count++;
                for (Direction direction : Direction.values()) {
                    BlockPos neighbor = pos.relative(direction);
                    if (portals.get(neighbor) == type && remaining.remove(neighbor)) queue.addLast(neighbor);
                }
            }
            Vec3 center = new Vec3(x / count, y / count, z / count);
            BlockPos anchor = members.stream()
                .min((a, b) -> Double.compare(a.distToCenterSqr(center), b.distToCenterSqr(center)))
                .orElse(first);
            result.add(new PortalMarker(center, type, anchor));
        }
        // Keep an explicitly selected portal visible on the radar while its chunk is unloaded.
        for (Map.Entry<BlockPos, PortalType> entry : pinnedPortals.entrySet()) {
            if (!portals.containsKey(entry.getKey()))
                result.add(new PortalMarker(Vec3.atCenterOf(entry.getKey()), entry.getValue(), entry.getKey()));
        }
        for (Map.Entry<PortalType, BlockPos> entry : autoPortals.entrySet()) {
            BlockPos pos = entry.getValue();
            if (!portals.containsKey(pos) && !pinnedPortals.containsKey(pos))
                result.add(new PortalMarker(Vec3.atCenterOf(pos), entry.getKey(), pos));
        }
        return result;
    }

    private PortalType rememberedPortalType(BlockPos pos) {
        PortalType live = portals.get(pos);
        if (live != null) return live;
        PortalType manual = pinnedPortals.get(pos);
        if (manual != null) return manual;
        PortalType observed = observedPortals.get(pos);
        if (observed != null) return observed;
        for (Map.Entry<PortalType, BlockPos> entry : autoPortals.entrySet()) {
            if (entry.getValue().equals(pos)) return entry.getKey();
        }
        return null;
    }

    private void logCountChanges() {
        long now = System.currentTimeMillis();
        if (now - lastCountLog >= 500
            && (portals.size() != previousPortalCount || chests.size() != previousChestCount)) {
            CatbudAddonsClient.LOGGER.debug("掃描結果：傳送門方塊 " + portals.size() + "，未開銅箱 " + chests.size());
            previousPortalCount = portals.size();
            previousChestCount = chests.size();
            lastCountLog = now;
        }
    }

    private static boolean isTarget(BlockState state) {
        return portalType(state) != null || isChest(state) || isMushroom(state);
    }

    private boolean isTracked(BlockPos pos) {
        return chests.contains(pos) || mushrooms.contains(pos) || portals.containsKey(pos);
    }

    private static boolean isMushroom(BlockState state) {
        return state.is(Blocks.RED_MUSHROOM_BLOCK);
    }

    static SpecialType specialType(BlockState state) {
        if (!isMushroom(state)) return null;
        boolean down = state.getValue(HugeMushroomBlock.DOWN);
        boolean east = state.getValue(HugeMushroomBlock.EAST);
        boolean north = state.getValue(HugeMushroomBlock.NORTH);
        boolean south = state.getValue(HugeMushroomBlock.SOUTH);
        boolean up = state.getValue(HugeMushroomBlock.UP);
        boolean west = state.getValue(HugeMushroomBlock.WEST);
        if (down && !east && !north && south && up && !west) return SpecialType.COIN_BANK;
        if (!down && !east && !north && !south && !up && !west)
            return SpecialType.ENCHANTMENT_EXTRACTION_TABLE;
        return null;
    }

    private static PortalType portalType(BlockState state) {
        Identifier id = id(state.getBlock());
        if (id.getNamespace().equals("minecraft") && id.getPath().equals("nether_portal")) return PortalType.NETHER;
        if (id.getNamespace().equals("minecraft") && id.getPath().equals("end_portal")) return PortalType.END;
        return null;
    }

    private static boolean isChest(BlockState state) {
        Identifier id = id(state.getBlock());
        return id.getNamespace().equals("minecraft")
            && (id.getPath().equals("copper_chest") || id.getPath().equals("waxed_copper_chest"));
    }

    private static Identifier id(Block block) { return BuiltInRegistries.BLOCK.getKey(block); }
    private static boolean inChunk(BlockPos pos, int x, int z) {
        return (pos.getX() >> 4) == x && (pos.getZ() >> 4) == z;
    }
    private static long chunkKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
}
