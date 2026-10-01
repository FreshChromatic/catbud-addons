package github.freshchromatic.catbud_addons.magic_tower;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Incremental standability index for every section of every currently loaded nearby chunk. */
final class LoadedTerrainMap {
    private static final int POSITIONS_PER_TICK = 8_192;
    private static final long SCAN_TIME_BUDGET_NANOS = 3_000_000;
    private record SectionKey(int chunkX, int chunkZ, int sectionY) {}

    private static final class Scan {
        final SectionKey key;
        final int sectionIndex;
        final LevelChunk chunk;
        final BitSet walkable = new BitSet(4_096);
        int cursor;
        boolean classified;
        boolean airOnly;

        Scan(SectionKey key, int sectionIndex, LevelChunk chunk) {
            this.key = key;
            this.sectionIndex = sectionIndex;
            this.chunk = chunk;
        }
    }

    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private final Map<SectionKey, BitSet> sections = new HashMap<>();
    private final Map<SectionKey, Scan> queued = new HashMap<>();
    private final ArrayDeque<Scan> pending = new ArrayDeque<>();
    private int standableCount;

    void clear() {
        chunks.clear();
        sections.clear();
        queued.clear();
        pending.clear();
        standableCount = 0;
    }

    int loadedChunks() { return chunks.size(); }
    int scannedSections() { return sections.size(); }
    int pendingSections() { return queued.size(); }
    int standableCount() { return standableCount; }

    Boolean standable(BlockPos pos) {
        BitSet bits = sections.get(new SectionKey(pos.getX() >> 4, pos.getZ() >> 4, pos.getY() >> 4));
        return bits == null ? null : bits.get(localIndex(pos));
    }

    void onBlockChanged(ClientLevel level, BlockPos changed) {
        for (BlockPos feet : List.of(changed, changed.above(), changed.below())) {
            SectionKey key = new SectionKey(feet.getX() >> 4, feet.getZ() >> 4, feet.getY() >> 4);
            boolean walkable = canStandLive(level, feet);
            BitSet completed = sections.get(key);
            if (completed != null) {
                boolean before = completed.get(localIndex(feet));
                completed.set(localIndex(feet), walkable);
                if (before != walkable) standableCount += walkable ? 1 : -1;
            }
            Scan scan = queued.get(key);
            if (scan != null) scan.walkable.set(localIndex(feet), walkable);
        }
    }

    void onChunkChanged(ClientLevel level, int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        LevelChunk chunk = chunks.get(key);
        if (chunk == null) return;
        forgetSections(chunkX, chunkZ);
        queueChunk(level, chunkX, chunkZ, chunk);
    }

    void tick(ClientLevel level, BlockPos player, int radius) {
        int centerX = player.getX() >> 4;
        int centerZ = player.getZ() >> 4;
        for (int ring = 0; ring <= radius; ring++) {
            for (int x = centerX - ring; x <= centerX + ring; x++) {
                for (int z = centerZ - ring; z <= centerZ + ring; z++) {
                    if (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) != ring) continue;
                    LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    long key = chunkKey(x, z);
                    if (chunk == null) continue;
                    if (chunks.put(key, chunk) != chunk) {
                        forgetSections(x, z);
                        queueChunk(level, x, z, chunk);
                    }
                }
            }
        }
        for (long key : List.copyOf(chunks.keySet())) {
            int x = (int) (key >> 32);
            int z = (int) key;
            if (Math.abs(x - centerX) > radius || Math.abs(z - centerZ) > radius
                || level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != chunks.get(key)) {
                chunks.remove(key);
                forgetSections(x, z);
            }
        }
        int budget = POSITIONS_PER_TICK;
        long deadline = System.nanoTime() + SCAN_TIME_BUDGET_NANOS;
        while (budget > 0 && !pending.isEmpty() && System.nanoTime() < deadline) {
            Scan scan = pending.peekFirst();
            if (queued.get(scan.key) != scan
                || chunks.get(chunkKey(scan.key.chunkX(), scan.key.chunkZ())) != scan.chunk) {
                pending.removeFirst();
                continue;
            }
            LevelChunkSection section = scan.chunk.getSections()[scan.sectionIndex];
            if (!scan.classified) {
                scan.classified = true;
                if (!section.maybeHas(state -> !state.is(Blocks.BEDROCK))) {
                    finish(scan);
                    continue;
                }
                scan.airOnly = !section.maybeHas(state -> !state.isAir());
            }
            int limit = scan.airOnly ? 256 : 4_096;
            int baseY = scan.key.sectionY() << 4;
            while (budget > 0 && scan.cursor < limit) {
                if ((budget & 63) == 0 && System.nanoTime() >= deadline) break;
                int local = scan.cursor;
                int x = (scan.key.chunkX() << 4) + (local & 15);
                int z = (scan.key.chunkZ() << 4) + ((local >> 4) & 15);
                int y = baseY + (local >> 8);
                if (canStandLive(level, new BlockPos(x, y, z))) scan.walkable.set(local);
                scan.cursor++;
                budget--;
            }
            if (scan.cursor >= limit) finish(scan);
        }
    }

    private void queueChunk(ClientLevel level, int x, int z, LevelChunk chunk) {
        for (int index = 0; index < chunk.getSections().length; index++) {
            SectionKey key = new SectionKey(x, z, level.getSectionYFromSectionIndex(index));
            Scan old = queued.get(key);
            if (old != null && old.chunk == chunk) continue;
            Scan scan = new Scan(key, index, chunk);
            queued.put(key, scan);
            pending.addLast(scan);
        }
    }

    private void finish(Scan scan) {
        pending.removeFirst();
        queued.remove(scan.key, scan);
        BitSet old = sections.put(scan.key, scan.walkable);
        if (old != null) standableCount -= old.cardinality();
        standableCount += scan.walkable.cardinality();
    }

    private void forgetSections(int x, int z) {
        Set<SectionKey> removed = new HashSet<>();
        for (Map.Entry<SectionKey, BitSet> entry : sections.entrySet()) {
            if (entry.getKey().chunkX() == x && entry.getKey().chunkZ() == z) {
                standableCount -= entry.getValue().cardinality();
                removed.add(entry.getKey());
            }
        }
        for (SectionKey key : removed) sections.remove(key);
        queued.keySet().removeIf(key -> key.chunkX() == x && key.chunkZ() == z);
    }

    private static int localIndex(BlockPos pos) {
        return ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    static boolean canStandLive(ClientLevel level, BlockPos pos) {
        return loaded(level, pos) && loaded(level, pos.above()) && loaded(level, pos.below())
            && clear(level, pos) && clear(level, pos.above()) && floor(level, pos.below());
    }

    static boolean loaded(ClientLevel level, BlockPos pos) {
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    static boolean clear(ClientLevel level, BlockPos pos) {
        if (!loaded(level, pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN)) return true;
        if (state.getBlock() instanceof TrapDoorBlock && state.getValue(TrapDoorBlock.OPEN)) return true;
        var shape = state.getCollisionShape(level, pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.125;
    }

    private static boolean floor(ClientLevel level, BlockPos pos) {
        if (!loaded(level, pos)) return false;
        var shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 0.5;
    }
}
