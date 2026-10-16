package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

class CloudWarpSyncTest {

    private static final String ID = "a29a6851-0d71-411c-953c-b87bab0d12f3";

    @Test
    void synchronizesDeletionAndFallsBackToUncachedFullReadWhenHistoryExpires() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var revision = new AtomicInteger(1);
        var deltaStatus = new AtomicInteger(200);
        var fullReads = new AtomicInteger();
        var deltaReads = new AtomicInteger();
        var uncachedReads = new AtomicInteger();
        server.createContext("/v1/warps", exchange -> {
            fullReads.incrementAndGet();
            if ("no-cache".equals(exchange.getRequestHeaders().getFirst("Cache-Control"))) {
                uncachedReads.incrementAndGet();
            }
            exchange.getResponseHeaders().set("ETag", etag(revision.get()));
            respond(exchange, 200, "{\"revision\":" + revision.get() + ",\"next\":null,\"warps\":[{\"uuid\":\"" + ID
                + "\",\"name\":\"Neko_1212\",\"tags\":[]}]}");
        });
        server.createContext("/v1/changes", exchange -> {
            deltaReads.incrementAndGet();
            if (deltaStatus.get() != 200) {
                respond(exchange, deltaStatus.get(), "{}");
                return;
            }
            respond(exchange, 200, "{\"revision\":2,\"next\":null,\"etag\":" + new com.google.gson.Gson().toJson(etag(
                2)) + ",\"changes\":[{\"revision\":2,\"uuid\":\"" + ID + "\",\"deleted\":true,\"warp\":null}]}");
        });
        server.start();
        try {
            var client = new CloudWarpApiClient(HttpClient.newHttpClient());
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var full = client.fetchWarps(endpoint, null, null);
            assertTrue(full.full());
            assertEquals(1L, full.revision());
            var delta = client.fetchWarps(endpoint, full.etag(), full.revision());
            assertFalse(delta.full());
            assertEquals(java.util.List.of(ID), delta.deleted());
            assertEquals(1, fullReads.get());
            assertEquals(1, deltaReads.get());
            deltaStatus.set(410);
            revision.set(3);
            var recovered = client.fetchWarps(endpoint, delta.etag(), delta.revision());
            assertTrue(recovered.full());
            assertEquals(3L, recovered.revision());
            assertEquals(1, uncachedReads.get());
        } finally {
            server.stop(0);
        }
    }

    private static String etag(long revision) {
        return "\"" + revision + ":" + System.currentTimeMillis() / 3600000 + ":\"";
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
