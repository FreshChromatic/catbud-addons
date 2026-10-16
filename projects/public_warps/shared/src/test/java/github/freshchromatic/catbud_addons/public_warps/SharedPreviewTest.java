package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

class SharedPreviewTest {

    @Test
    void denseSceneSurvivesLocalRestartWhenItExceedsSharingLimits(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
        throws Exception {
        var local = new BlockSnapshot(50, 50, 50, "minecraft:overworld");
        var random = new java.util.Random(20261011);
        for (int index = 0; index < 4096; index++) {
            var value = new StringBuilder();
            for (int character = 0; character < 350; character++) {
                value.append((char) ('a' + random.nextInt(26)));
            }
            local.palette.add("example:block_" + index + "[variant=" + value + "]");
        }
        for (int index = 0; index < local.blocks.length; index++) {
            local.blocks[index] = random.nextInt(local.palette.size());
        }
        byte[] encoded = local.encode();
        assertTrue(encoded.length > 128 * 1024);
        assertThrows(SharedPreview.LimitException.class, () -> SharedPreview.from(local));
        var payload = PreviewPayload.from(local, true);
        assertNotNull(payload.encoded());
        assertNull(payload.reportEncoded());
        var store = new WarpStore();
        store.cachePreview("local", payload.encoded(), null);
        var path = directory.resolve("warps.json");
        store.save(path);
        var restored = BlockSnapshot.decode(java.util.Base64.getDecoder().decode(WarpStore.load(path).cachedPreview(
            "local")));
        assertArrayEquals(local.blocks, restored.blocks);
        assertEquals(local.palette, restored.palette);
    }

    @Test
    void sharingRemovesPlayersAndTextAndKeepsLocalSnapshotIntact() throws Exception {
        var local = new BlockSnapshot(50, 50, 50, "minecraft:overworld");
        local.palette.add("minecraft:air");
        local.viewYaw = 43;
        local.entities.add(new BlockSnapshot.SnapshotEntity("minecraft:player", 1, 1, 1, 0, 0,
            "a29a6851-0d71-411c-953c-b87bab0d12f3", "Neko_1212", Map.of()));
        local.entities.add(new BlockSnapshot.SnapshotEntity("minecraft:text_display", 1, 1, 1, 0, 0, null, null, Map.of(
            "text", "private")));
        for (int index = 0; index < 70; index++) {
            local.entities.add(new BlockSnapshot.SnapshotEntity("minecraft:sheep", 1.03, 2, 3, 7, 3, null, null, Map.of(
                "Color", "14b")));
        }
        var payload = SharedPreview.from(local);
        var shared = BlockSnapshot.decode(payload.bytes());
        assertEquals(64, shared.entities.size());
        assertEquals(1, shared.entities.getFirst().x());
        assertEquals(5, shared.entities.getFirst().yaw());
        assertEquals(0, shared.viewYaw);
        assertEquals(72, local.entities.size());
        assertEquals(43, local.viewYaw);
        assertEquals(payload.digest(), SharedPreview.from(local).digest());
    }
}
