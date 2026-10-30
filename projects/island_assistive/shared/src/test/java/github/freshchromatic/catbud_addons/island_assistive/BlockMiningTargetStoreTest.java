package github.freshchromatic.catbud_addons.island_assistive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class BlockMiningTargetStoreTest {

    @TempDir Path temporary;

    @Test
    void savesNegativeCoordinatesWithWorldAndDimension() throws Exception {
        var path = temporary.resolve("target.json");
        var store = new BlockMiningTargetStore(path);
        assertNull(store.target());
        assertFalse(store.loadFailed());
        var target = new BlockMiningTarget("server:tcpshield.test.catrealm.net", "minecraft:overworld", -3576, 65,
            -3576);
        store.save(target);
        assertEquals(target, new BlockMiningTargetStore(path).target());
    }

    @Test
    void corruptTargetIsDisabledAndBackedUpWhenReselected() throws Exception {
        var path = temporary.resolve("target.json");
        Files.writeString(path, "broken");
        var store = new BlockMiningTargetStore(path);
        assertTrue(store.loadFailed());
        assertNull(store.target());
        store.save(new BlockMiningTarget("local:world", "minecraft:the_nether", 0, -64, 0));
        assertFalse(store.loadFailed());
        try (var files = Files.list(temporary)) {
            var backup = files.filter(file -> file.getFileName().toString().startsWith("target.json.invalid-"))
                .findFirst().orElseThrow();
            assertEquals("broken", Files.readString(backup));
        }
    }

    @Test
    void invalidRecordAndUnsupportedVersionDoNotBecomeTargets() throws Exception {
        var path = temporary.resolve("target.json");
        for (String document : new String[] { "{\"version\":2,\"target\":null}",
            "{\"version\":1,\"target\":{\"world\":\"server:test\",\"dimension\":\"INVALID\",\"x\":0,\"y\":0,\"z\":0}}",
            "{\"version\":1,\"target\":{\"world\":\"server:test\",\"dimension\":\"minecraft:overworld\",\"x\":2147483647,\"y\":0,\"z\":0}}" }) {
            Files.writeString(path, document);
            var store = new BlockMiningTargetStore(path);
            assertTrue(store.loadFailed());
            assertNull(store.target());
        }
    }
}
