package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

class WarpApiClientTest {

    private static final String UUID = "a29a6851-0d71-411c-953c-b87bab0d12f3";

    @Test
    void verifiesResolvedIdentityAndRejectsStringPassAndMismatchedPlayers() throws Exception {
        var player = new AtomicReference<>(playerJson("true", "Neko_1212"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/profiles/", exchange -> {
            byte[] body = ("{\"id\":\"" + UUID.replace("-", "") + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/players/", exchange -> {
            byte[] body = player.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            var api = new WarpApiClient(HttpClient.newHttpClient(), base.resolve("players/"), base.resolve(
                "profiles/"));
            assertEquals(UUID, api.verifyPlayer("Neko_1212", null).uuid());
            player.set(playerJson("false", "Neko_1212"));
            assertFalse(api.checkPlayer("Neko_1212", UUID).activePass());
            assertThrows(WarpApiClient.InactivePassException.class, () -> api.verifyPlayer("Neko_1212", UUID));
            player.set(playerJson("false", "OtherPlayer"));
            IOException mismatch = assertThrows(IOException.class, () -> api.verifyPlayer("Neko_1212", UUID));
            assertFalse(mismatch instanceof WarpApiClient.InactivePassException);
            player.set(playerJson("\"true\"", "Neko_1212"));
            assertThrows(IOException.class, () -> api.verifyPlayer("Neko_1212", UUID));
            player.set(playerJson("true", "OtherPlayer"));
            assertThrows(IOException.class, () -> api.verifyPlayer("Neko_1212", UUID));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesListCachingRetryHintsAndPreviewResponseLimits() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/warps", exchange -> {
            if ("known".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            byte[] body = ("{\"warps\":[{\"uuid\":\"" + UUID + "\",\"name\":\"Neko_1212\"}]}").getBytes(
                StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("ETag", "known");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/v1/reports", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Retry-After", "5");
            exchange.sendResponseHeaders(503, 2);
            try (var output = exchange.getResponseBody()) {
                output.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/v1/snapshots/", exchange -> {
            byte[] body = new byte[128 * 1024 + 1];
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            String service = "http://127.0.0.1:" + server.getAddress().getPort();
            var api = new WarpApiClient(HttpClient.newHttpClient());
            var list = api.fetchWarps(service, null);
            assertEquals("Neko_1212", list.warps().getFirst().name);
            assertEquals("known", list.etag());
            assertFalse(api.fetchWarps(service, list.etag()).changed());
            var report = new WarpStore.Report(UUID, "Neko_1212", true, true, null, 1);
            var result = api.submitReport(service, report);
            assertFalse(result.terminal());
            assertEquals("5", result.retryAfter());
            assertThrows(IOException.class, () -> api.fetchPreview(service, "large"));
        } finally {
            server.stop(0);
        }
    }

    private static String playerJson(String pass, String name) {
        return "{\"uuid\":\"" + UUID + "\",\"name\":\"" + name + "\",\"pass\":" + pass + "}";
    }
}
