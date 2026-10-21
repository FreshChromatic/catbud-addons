package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class OfficialWarpSyncTest {

    private static final String UUID = "a29a6851-0d71-411c-953c-b87bab0d12f3";

    @ParameterizedTest
    @EnumSource(WarpServerScope.class)
    void withdrawnWarpKeepsPreviewMetadataAndVisitsAfterCloudMergeAndRestart(WarpServerScope scope, @TempDir Path directory)
        throws Exception {
        var store = new WarpStore();
        store.serverId = scope.id;
        store.mergeOfficial(Map.of(UUID, "Steve"), 1000);
        store.previews.put(UUID, "preview");
        store.arrival(UUID, 1100);
        var shared = new WarpData();
        shared.uuid = UUID;
        shared.name = "OldName";
        shared.description = "Station";
        shared.snapshotHash = "snapshot";
        shared.passState = "inactive";
        shared.status = "invalid";
        store.mergeShared(List.of(shared));
        assertEquals("Steve", shared.name);
        assertTrue(shared.canTeleport());
        assertEquals("", shared.warningKey());

        store.mergeOfficial(Map.of(), 2000);
        assertFalse(shared.canTeleport());
        assertEquals("warps.status.unavailable", shared.warningKey());
        assertEquals("Station", shared.description);
        assertEquals("preview", store.previews.get(UUID));
        assertEquals(1, store.visits.get(UUID).count);
        shared.confirmTeleport();
        assertFalse(shared.canTeleport());
        var stale = new WarpData();
        stale.uuid = UUID;
        stale.name = "OldName";
        stale.description = "Station";
        stale.passState = "active";
        stale.availability = "available";
        store.mergeShared(List.of(stale));
        assertFalse(stale.canTeleport());

        Path file = directory.resolve("cache.json");
        store.save(file);
        var restored = WarpStore.load(file);
        assertFalse(restored.warps.getFirst().canTeleport());
        assertEquals(2000, restored.officialDirectory.refreshed);
        restored.mergeOfficial(Map.of(UUID, "Renamed"), 3000);
        assertTrue(restored.warps.getFirst().canTeleport());
        assertEquals("Renamed", restored.warps.getFirst().name);
        assertEquals("Station", restored.warps.getFirst().description);
        assertEquals(1, restored.visits.get(UUID).count);
        assertTrue(restored.discoveries.isEmpty());
    }

    @Test
    void catrealmBedrockWarpsKeepTheirOwnEndpointIdentityAndCache(@TempDir Path directory) throws Exception {
        assertEquals(URI.create("https://api.catbud.net/publics"), WarpServerScope.CATBUD.publicWarpsEndpoint());
        assertEquals(URI.create("https://api.catrealm.net/publics"), WarpServerScope.CATREALM.publicWarpsEndpoint());
        String body = "{\"publics\":[{\"uuid\":\"" + UUID + "\",\"name\":\".Steve\"}]}";
        var players = OfficialWarpApiClient.parseResponse(body.getBytes(StandardCharsets.UTF_8));
        var account = java.util.UUID.fromString(UUID);
        var catbud = ServerWarpCache.load(directory, account, WarpServerScope.CATBUD, "catbud.net");
        var catrealm = ServerWarpCache.load(directory, account, WarpServerScope.CATREALM, "catrealm.net");
        catbud.mergeOfficial(Map.of(UUID, "Steve"), 1000);
        catrealm.mergeOfficial(players, 2000);
        catbud.save(ServerWarpCache.path(directory, account, WarpServerScope.CATBUD));
        catrealm.save(ServerWarpCache.path(directory, account, WarpServerScope.CATREALM));
        var restored = ServerWarpCache.load(directory, account, WarpServerScope.CATREALM, "catrealm.net");
        assertTrue(restored.warps.getFirst().canTeleport());
        assertEquals(".Steve", restored.warps.getFirst().name);
        assertEquals("Steve", ServerWarpCache.load(directory, account, WarpServerScope.CATBUD, "catbud.net").warps
            .getFirst().name);
        restored.mergeOfficial(Map.of(), 3000);
        assertFalse(restored.warps.getFirst().canTeleport());
        assertTrue(catbud.warps.getFirst().canTeleport());
        assertEquals(".Steve", TeleportAttempt.target("to by .Steve"));
        assertNull(TeleportAttempt.target("to by .Steve other"));
        assertNull(TeleportAttempt.target("to by ..Steve"));
    }

    @Test
    void cloudDeletionCannotRemoveOfficialWarpAndNameReuseCannotStealItsIdentity() {
        var store = new WarpStore();
        var old = store.remember("Steve");
        old.uuid = "58ce56f5-bc84-4a68-bde6-c62fa61378c5";
        store.mergeOfficial(Map.of(UUID, "Steve"), 1000);
        assertFalse(old.canTeleport());
        var official = store.warps.stream().filter(warp -> UUID.equals(warp.uuid)).findFirst().orElseThrow();
        store.sharedIds.add(UUID);
        store.mergeCloud(new WarpApiClient.WarpList(true, List.of(), "etag", 2L, List.of(UUID), true));
        assertTrue(store.warps.contains(official));
        assertTrue(official.canTeleport());
        assertSame(official, store.remember("Steve"));
        var unknown = store.remember("Unknown");
        assertFalse(unknown.canTeleport());
    }

    @Test
    void rejectsPartialMalformedAndOversizedListsInsteadOfTreatingThemAsEmpty() throws Exception {
        String valid = "{\"publics\":[{\"uuid\":\"" + UUID.toUpperCase() + "\",\"name\":\"Steve\"}]}";
        assertEquals(Map.of(UUID, "Steve"), OfficialWarpApiClient.parseResponse(valid.getBytes(
            StandardCharsets.UTF_8)));
        assertTrue(OfficialWarpApiClient.parseResponse("{\"publics\":[]}".getBytes(StandardCharsets.UTF_8)).isEmpty());
        for (String invalid : List.of("{}", "{\"publics\":null}", "{\"publics\":[{}]}",
            "{\"publics\":[{\"uuid\":\"1-1-1-1-1\",\"name\":\"Steve\"}]}", "{\"publics\":[{\"uuid\":\"" + UUID
                + "\",\"name\":12}]}", "{\"publics\":[{\"uuid\":\"" + UUID + "\",\"name\":\"Steve\"},{\"uuid\":\""
                    + UUID + "\",\"name\":\"Alex\"}]}")) {
            assertThrows(IOException.class, () -> OfficialWarpApiClient.parseResponse(invalid.getBytes(
                StandardCharsets.UTF_8)));
        }
        assertThrows(IOException.class, () -> OfficialWarpApiClient.parseResponse(new byte[1048577]));
    }

    @Test
    void failedTeleportSurvivesCloudMetadataUntilOfficialRefresh() {
        var store = new WarpStore();
        store.mergeOfficial(Map.of(UUID, "Steve"), 1000);
        var warp = store.warps.getFirst();
        warp.failedTeleport(2000);
        var shared = new WarpData();
        shared.uuid = UUID;
        shared.name = "Steve";
        shared.availability = "available";
        shared.lastSuccess = 3000;
        store.mergeShared(List.of(shared));
        assertFalse(shared.canTeleport());
        store.mergeOfficial(Map.of(UUID, "Steve"), 4000);
        assertTrue(shared.canTeleport());
    }

    @Test
    void unavailableAndMalformedResponsesPreserveLastSuccessfulDirectory() throws Exception {
        var status = new AtomicInteger(200);
        var body = new AtomicReference<>("{\"publics\":[{\"uuid\":\"" + UUID + "\",\"name\":\"Steve\"}]}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/publics", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            var client = new OfficialWarpApiClient(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server
                .getAddress().getPort() + "/publics"));
            var store = new WarpStore();
            store.mergeOfficial(client.fetch(), 1000);
            status.set(503);
            assertThrows(IOException.class, client::fetch);
            status.set(200);
            for (String invalid : List.of("{}", "{\"publics\":null}", "{\"publics\":[{}]}",
                "{\"publics\":[{\"uuid\":\"1-1-1-1-1\",\"name\":\"Steve\"}]}", "{\"publics\":[{\"uuid\":\"" + UUID
                    + "\",\"name\":12}]}", "{\"publics\":[{\"uuid\":\"" + UUID + "\",\"name\":\"Steve\"},{\"uuid\":\""
                        + UUID + "\",\"name\":\"Alex\"}]}")) {
                body.set(invalid);
                assertThrows(IOException.class, client::fetch);
            }
            assertTrue(store.warps.getFirst().canTeleport());
            assertEquals(1000, store.officialDirectory.refreshed);
            body.set("{\"publics\":[]}");
            store.mergeOfficial(client.fetch(), 2000);
            assertFalse(store.warps.getFirst().canTeleport());
            body.set("x".repeat(1048577));
            assertThrows(IOException.class, client::fetch);
        } finally {
            server.stop(0);
        }
    }
}
