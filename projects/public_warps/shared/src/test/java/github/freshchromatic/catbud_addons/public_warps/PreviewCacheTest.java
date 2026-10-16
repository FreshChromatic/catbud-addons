package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class PreviewCacheTest {

    @Test
    void browsingKeepsRecentScenesAndReleasesOldResourcesUnderBytePressure() {
        var released = new ArrayList<String>();
        var cache = new PreviewCache<String, Long>(64, 100, (key, bytes) -> bytes, (key, value) -> released.add(key));
        cache.put("first", 40L);
        cache.put("second", 40L);
        cache.get("first");
        cache.put("third", 40L);
        assertNull(cache.get("second"));
        assertEquals(List.of("second"), released);
        assertEquals(80, cache.bytes());
        cache.put("third", 20L);
        assertEquals(60, cache.bytes());
        assertEquals(List.of("second", "third"), released);
        cache.clear();
        assertEquals(0, cache.bytes());
        assertEquals(0, cache.size());
        assertEquals(List.of("second", "third", "first", "third"), released);
    }

    @Test
    void oneLargeSceneRemainsReusableRatherThanRebuildingEveryFrame() {
        var cache = new PreviewCache<String, Long>(2, 100, (key, bytes) -> bytes, (key, value) -> {
        });
        cache.put("small", 10L);
        cache.put("large", 200L);
        assertEquals(1, cache.size());
        assertEquals(200L, cache.computeIfAbsent("large", key -> fail("Large scene rebuilt")));
        cache.put("new", 10L);
        assertNull(cache.get("large"));
        assertEquals(10, cache.bytes());
    }

    @Test
    void snapshotWeightAccountsForPaletteAndEntityAppearanceBeyondBlockIndices() {
        var snapshot = new BlockSnapshot(50, 50, 50, "minecraft:overworld");
        snapshot.palette.add("minecraft:stone");
        long base = snapshot.estimatedBytes();
        assertTrue(base > 500000);
        snapshot.palette.add("example:block[variant=" + "a".repeat(400) + "]");
        assertTrue(snapshot.estimatedBytes() >= base + 800);
        long paletteBytes = snapshot.estimatedBytes();
        snapshot.entities.add(new BlockSnapshot.SnapshotEntity("minecraft:armor_stand", 1, 1, 1, 0, 0, null, null,
            java.util.Map.of("equipment", "a".repeat(4096))));
        assertTrue(snapshot.estimatedBytes() >= paletteBytes + 8192);
    }
}
