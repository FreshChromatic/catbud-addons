package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

class ServerWarpSyncTest {

    @TempDir Path directory;

    @Test
    void migratesOnlyKnownCatbudCacheAndSeparatesSameAccountOnCatrealm() throws Exception {
        UUID account = UUID.randomUUID();
        String address = "play.catbud.net:25565";
        var legacy = new WarpStore();
        var warp = legacy.remember("Neko_1212");
        warp.uuid = account.toString();
        warp.confirmTeleport();
        legacy.visits.put(warp.uuid, new WarpData.Visit());
        legacy.visits.get(warp.uuid).count = 3;
        Path old = directory.resolve(UUID.nameUUIDFromBytes((account + "@" + address).getBytes(StandardCharsets.UTF_8))
            + ".json");
        legacy.save(old);
        var catbud = ServerWarpCache.load(directory, account, WarpServerScope.CATBUD, address);
        assertEquals(3, catbud.visits.get(warp.uuid).count);
        assertTrue(Files.exists(old));
        assertTrue(Files.exists(ServerWarpCache.path(directory, account, WarpServerScope.CATBUD)));
        var catrealm = ServerWarpCache.load(directory, account, WarpServerScope.CATREALM, "catrealm.net");
        assertTrue(catrealm.warps.isEmpty());
        catrealm.remember("Neko_1212");
        catrealm.save(ServerWarpCache.path(directory, account, WarpServerScope.CATREALM));
        assertEquals("catbud", ServerWarpCache.load(directory, account, WarpServerScope.CATBUD, "catbud.net").serverId);
        assertEquals(WarpServerScope.CATREALM, WarpServerScope.fromAddress("PLAY.CATREALM.NET:25565"));
        assertNull(WarpServerScope.fromAddress("fakecatrealm.net"));
    }

    @Test
    void scopedClientRejectsCrossServerListAndKeepsObservationIdentifierOnRetry() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bodies = new java.util.ArrayList<String>();
        server.createContext("/v2/servers/catrealm/warps", exchange -> {
            String body = "{\"serverId\":\"catbud\",\"revision\":1,\"next\":null,\"warps\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/v2/servers/catrealm/observations", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = "{\"serverId\":\"catrealm\",\"state\":\"pending_review\",\"submissionId\":\"" + UUID
                .randomUUID() + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new CloudWarpApiClient(HttpClient.newHttpClient(), WarpServerScope.CATREALM);
            assertThrows(java.io.IOException.class, () -> client.fetchWarps(endpoint, null));
            var report = new WarpStore.Report(UUID.randomUUID().toString(), "Neko_1212", true, true, null, System
                .currentTimeMillis());
            assertEquals("pending_review", client.upload(endpoint, "a".repeat(64), report).state());
            assertEquals("pending_review", client.upload(endpoint, "a".repeat(64), report).state());
            assertEquals(bodies.get(0), bodies.get(1));
            var store = new WarpStore();
            store.pending.add(report);
            store.save(directory.resolve("queue.json"));
            assertEquals(report.submissionId(), WarpStore.load(directory.resolve("queue.json")).pending.getFirst()
                .submissionId());
        } finally {
            server.stop(0);
        }
    }
}
