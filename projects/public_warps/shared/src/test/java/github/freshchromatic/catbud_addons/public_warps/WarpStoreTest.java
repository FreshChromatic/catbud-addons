package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class WarpStoreTest {

    @Test
    void firstFailureIsHiddenButConfirmedWarpSurvivesFailureAndRestart(@TempDir Path directory) throws Exception {
        var store = new WarpStore();
        var first = store.remember("system_cat");
        first.failedTeleport(1000);
        assertFalse(first.listed());
        store.rejectFirstAttempt(first);
        assertTrue(store.rejected("system_cat"));
        assertTrue(store.warps.isEmpty());
        var shared = new WarpData();
        shared.name = "system_cat";
        shared.uuid = "a29a6851-0d71-411c-953c-b87bab0d12f3";
        store.mergeShared(List.of(shared));
        assertTrue(store.warps.isEmpty());
        var known = store.remember("KnownWarp");
        known.confirmTeleport();
        known.failedTeleport(2000);
        assertTrue(known.listed());
        Path file = directory.resolve("cache.json");
        store.save(file);
        var restored = WarpStore.load(file);
        assertTrue(restored.rejected("system_cat"));
        assertEquals(1, restored.warps.size());
        assertTrue(restored.warps.getFirst().listed());
        var retried = restored.remember("system_cat");
        retried.successfulArrival(3000);
        assertFalse(restored.rejected("system_cat"));
        assertTrue(retried.listed());
    }

    @Test
    void previewBytesEvictLeastRecentlyUsedAndPendingImagesDoNotCrowdOutReports() throws Exception {
        var store = new WarpStore();
        String encoded = "a".repeat(9 * 1024 * 1024);
        store.cachePreview("first", encoded, "hash-first");
        store.cachePreview("second", encoded, "hash-second");
        store.cachedPreview("first");
        store.cachePreview("third", encoded, "hash-third");
        assertTrue(store.previews.containsKey("first"));
        assertFalse(store.previews.containsKey("second"));
        assertFalse(store.previewVersions.containsKey("second"));
        long now = System.currentTimeMillis();
        String pending = "b".repeat(3 * 1024 * 1024);
        store.queue(new WarpStore.Report("first", "Cat", true, true, pending, now));
        store.queue(new WarpStore.Report("third", "Cat", true, true, pending, now));
        assertEquals(2, store.pending.size());
        assertNull(store.pending.getFirst().snapshot());
        assertNotNull(store.pending.getLast().snapshot());
        var path = dir.resolve("bounded.json");
        store.save(path);
        var restored = WarpStore.load(path);
        assertTrue(restored.previews.containsKey("first"));
        assertEquals(2, restored.pending.size());
    }

    @Test
    void tombstonesRemoveSharedEntriesButRetainPersonalDiscoveryVisitsAndPreview() {
        var store = new WarpStore();
        String localId = "a29a6851-0d71-411c-953c-b87bab0d12f3";
        String sharedId = "b29a6851-0d71-411c-953c-b87bab0d12f3";
        var personal = store.remember("LocalWarp");
        store.resolveIdentity(personal, localId);
        store.arrival(localId, 1000);
        store.cachePreview(localId, "personal-preview", null);
        var remotePersonal = new WarpData();
        remotePersonal.name = "LocalWarp";
        remotePersonal.uuid = localId;
        var remote = new WarpData();
        remote.name = "RemoteWarp";
        remote.uuid = sharedId;
        store.mergeCloud(new WarpApiClient.WarpList(true, List.of(remotePersonal, remote), "etag", 1L, List.of(),
            true));
        store.cachePreview(sharedId, "downloaded-preview", "shared-hash");
        store.mergeCloud(new WarpApiClient.WarpList(true, List.of(), "etag2", 2L, List.of(localId, sharedId), false));
        assertEquals(1, store.warps.size());
        assertEquals("LocalWarp", store.warps.getFirst().name);
        assertEquals(1, store.visits.get(localId).count);
        assertEquals("personal-preview", store.previews.get(localId));
        assertFalse(store.previews.containsKey(sharedId));
        assertTrue(store.sharedIds.isEmpty());
        assertEquals(2L, store.sharedRevision);
    }

    @Test
    void rememberedNamesSurviveRestartAndSharedRefresh(@TempDir Path directory) throws Exception {
        var store = new WarpStore();
        var original = store.remember("Neko_1212");
        assertSame(original, store.remember("neko_1212"));
        original.confirmTeleport();
        original.failedTeleport(2000);
        original.passState = "active";
        original.checkedAt = 1000;
        Path path = directory.resolve("warps.json");
        store.save(path);
        var restored = WarpStore.load(path);
        restored.mergeShared(List.of());
        assertEquals(1, restored.warps.size());
        assertFalse(restored.warps.getFirst().canTeleport());
        var shared = new WarpData();
        shared.name = "Neko_1212";
        shared.uuid = "a29a6851-0d71-411c-953c-b87bab0d12f3";
        restored.mergeShared(List.of(shared));
        assertEquals(1, restored.warps.size());
        assertFalse(restored.warps.getFirst().canTeleport());
        assertEquals("warps.status.no_warp", restored.warps.getFirst().warningKey());
        restored.warps.getFirst().successfulArrival(3000);
        assertTrue(restored.warps.getFirst().canTeleport());
        var olderFailure = new WarpData();
        olderFailure.name = "Neko_1212";
        olderFailure.uuid = shared.uuid;
        olderFailure.failedTeleport(2500);
        restored.mergeShared(List.of(olderFailure));
        assertTrue(restored.warps.getFirst().canTeleport());
        assertEquals(3000, restored.warps.getFirst().lastSuccess);
        restored.warps.getFirst().passState = "inactive";
        assertFalse(restored.warps.getFirst().canTeleport());
        assertEquals("warps.status.no_pass", restored.warps.getFirst().warningKey());
    }

    @Test
    void renamedIdentityMergesVisitsAndPreviewWithoutDuplicateWarp() {
        var store = new WarpStore();
        var old = store.remember("OldName");
        old.uuid = "a29a6851-0d71-411c-953c-b87bab0d12f3";
        old.passState = "active";
        old.checkedAt = 5000;
        store.arrival(old.key(), 1000);
        var renamed = store.remember("NewName");
        renamed.successfulArrival(3000);
        store.arrival(renamed.key(), 3000);
        store.cachePreview(renamed.key(), "new-preview", null);
        store.resolveIdentity(renamed, old.uuid);
        assertEquals(1, store.warps.size());
        assertSame(renamed, store.warps.getFirst());
        assertEquals(2, store.visits.get(old.uuid).count);
        assertEquals("new-preview", store.previews.get(old.uuid));
        assertEquals("active", renamed.passState);
        assertEquals(3000, renamed.lastSuccess);
    }

    @Test
    void fiftyCubeRoundTripRetainsAllBlocksAndRejectsCorruptBounds() throws Exception {
        var snapshot = new BlockSnapshot(50, 50, 50, "minecraft:overworld");
        snapshot.palette.add("minecraft:air");
        snapshot.palette.add("minecraft:stone");
        snapshot.blocks[snapshot.index(49, 49, 49)] = 1;
        snapshot.eyeX = 25.5;
        snapshot.eyeY = 26.62;
        snapshot.eyeZ = 25.5;
        var restored = BlockSnapshot.decode(snapshot.encode());
        assertEquals(4, restored.version);
        assertEquals(125000, restored.blocks.length);
        assertEquals("minecraft:stone", restored.state(49, 49, 49));
        snapshot.width = 51;
        assertThrows(java.io.IOException.class, snapshot::encode);
    }

    @Test
    void upgradingPreviewBypassesCooldownWithoutRepeatingUnchangedReports() {
        var store = new WarpStore();
        var snapshot = new BlockSnapshot(30, 30, 30, "minecraft:overworld");
        store.previewReports.put("uuid", new WarpStore.PreviewReport("same", 1000, 2));
        assertTrue(store.mayReportPreview("uuid", snapshot, "same", 1001));
        store.previewReports.put("uuid", new WarpStore.PreviewReport("same", 1000, 3));
        assertFalse(store.mayReportPreview("uuid", snapshot, "same", 1801000));
        assertFalse(store.mayReportPreview("uuid", snapshot, "different", 1799999));
        assertTrue(store.mayReportPreview("uuid", snapshot, "different", 1801000));
    }

    @Test
    void resolvingNamePreservesBothVisitHistoriesAndLatestArrival() {
        var store = new WarpStore();
        store.arrival("neko_1212", 2000);
        store.arrival("uuid", 1000);
        store.mergeNameVisits("Neko_1212", "uuid");
        assertFalse(store.visits.containsKey("neko_1212"));
        assertEquals(2, store.visits.get("uuid").count);
        assertEquals(2000, store.visits.get("uuid").last);
        store.mergeNameVisits("Neko_1212", "uuid");
        assertEquals(2, store.visits.get("uuid").count);
    }

    @TempDir Path dir;

    @Test
    void durableArrivalsAndPreviewSurviveRestartWithoutLosingQueuedSnapshot() throws Exception {
        var store = new WarpStore();
        store.arrival("target", 1000);
        store.arrival("target", 2000);
        long now = System.currentTimeMillis();
        store.queue(new WarpStore.Report("target", "Cat", true, true, "snapshot", now));
        store.queue(new WarpStore.Report("target", "Cat", true, true, null, now + 1));
        store.cachePreview("target", "snapshot", null);
        store.previewReports.put("target", new WarpStore.PreviewReport("digest", now, 2));
        assertEquals("snapshot", store.pending.getFirst().snapshot());
        Path path = dir.resolve("account/server.json");
        store.save(path);
        var loaded = WarpStore.load(path);
        assertEquals(2, loaded.visits.get("target").count);
        assertEquals("snapshot", loaded.pending.getFirst().snapshot());
        assertEquals("snapshot", loaded.previews.get("target"));
        assertEquals(now, loaded.previewReports.get("target").at());
        assertEquals(2, loaded.previewReports.get("target").version());
        assertFalse(Files.exists(dir.resolve("other-account/server.json")));
    }

    @Test
    void compressedSnapshotRoundTripAndCorruptIndicesAreRejected() throws Exception {
        var s = new BlockSnapshot(16, "minecraft:overworld");
        s.palette.add("minecraft:stone");
        assertEquals(1600, BlockSnapshot.decode(s.encode()).blocks.length);
        s.blocks[0] = 2;
        assertThrows(java.io.IOException.class, s::encode);
        assertThrows(java.io.IOException.class, () -> BlockSnapshot.decode(new byte[] { 1, 2, 3 }));
    }

    @Test
    void previewCacheAndRetryQueueStayBounded() {
        var s = new WarpStore();
        for (int i = 0; i < 150; i++) {
            s.cachePreview("id" + i, "snapshot", null);
            s.queue(new WarpStore.Report("id" + i, "Cat", true, true, null, 2000));
        }
        assertEquals(64, s.previews.size());
        assertEquals(100, s.pending.size());
    }

    @Test
    void malformedCacheIsRejectedWithoutModifyingOriginalAndOlderCacheKeepsDefaults() throws Exception {
        Path path = dir.resolve("broken.json");
        String invalid = "{\"visits\":{\"target\":null}}";
        Files.writeString(path, invalid);
        assertThrows(java.io.IOException.class, () -> WarpStore.load(path));
        assertEquals(invalid, Files.readString(path));
        Files.writeString(path, "{\"visits\":{\"target\":{\"count\":4,\"last\":1000}}}");
        var old = WarpStore.load(path);
        assertEquals(4, old.visits.get("target").count);
        assertNotNull(old.previewReports);
        Files.writeString(path, "{\"previewReports\":null}");
        assertThrows(java.io.IOException.class, () -> WarpStore.load(path));
    }
}
