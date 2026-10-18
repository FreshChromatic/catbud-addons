package github.freshchromatic.catbud_addons.update;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseHttpClientTest {

    @TempDir Path directory;

    @FunctionalInterface
    private interface ResponseFactory {

        HttpResponse<InputStream> respond(HttpRequest request) throws Exception;
    }

    private static final class Transport implements ReleaseTransport {

        private final ResponseFactory factory;
        private int                   requests;

        private Transport(ResponseFactory factory) {
            this.factory = factory;
        }

        @Override
        public HttpResponse<InputStream> send(HttpRequest request) throws Exception {
            requests++;
            return factory.respond(request);
        }

        @Override
        public void close() {
        }
    }

    private record Response(int statusCode, HttpHeaders headers, HttpRequest request, InputStream body) implements
        HttpResponse<InputStream> {

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    @Test
    void rejectsUntrustedRedirectBeforeContactingIt() throws Exception {
        var transport = new Transport(request -> response(request, 302, Map.of("Location", List.of(
            "https://evil.test/mod.jar")), new byte[0]));
        try (var http = new ReleaseHttpClient(transport)) {
            assertThrows(IOException.class, () -> http.download(artifact(2, "0".repeat(64)), directory.resolve(
                "new.jar"), () -> false, count -> {
                }));
            assertEquals(1, transport.requests);
            assertFalse(Files.exists(directory.resolve("new.jar")));
        }
    }

    @Test
    void enforcesDownloadSizeAndHashAndRemovesIncompleteFiles() throws Exception {
        for (long size : new long[] { 1, 2 }) {
            var transport = new Transport(request -> response(request, 200, Map.of(), new byte[] { 1, 2 }));
            try (var http = new ReleaseHttpClient(transport)) {
                Path target = directory.resolve("new-" + size + ".jar");
                assertThrows(IOException.class, () -> http.download(artifact(size, "0".repeat(64)), target, () -> false,
                    count -> {
                    }));
                assertFalse(Files.exists(target));
                assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".part")));
            }
        }
    }

    @Test
    void boundsMetadataAndHonorsRateLimitHints() throws Exception {
        var transport = new Transport(request -> response(request, 429, Map.of("Retry-After", List.of("120")),
            new byte[0]));
        try (var http = new ReleaseHttpClient(transport)) {
            var failure = assertThrows(ReleaseHttpClient.RequestFailure.class, () -> http.json("catalog?minecraft=26.2",
                "", false));
            assertEquals(120, failure.retrySeconds());
        }
        var oversized = new Transport(request -> response(request, 200, Map.of("Content-Type", List.of(
            "application/json")), new byte[512 * 1024 + 1]));
        try (var http = new ReleaseHttpClient(oversized)) {
            assertThrows(IOException.class, () -> http.json("catalog?minecraft=26.2", "", false));
        }
    }

    @Test
    void cancellationBeforeAndDuringTransferNeverLeavesAnInstallableArchive() throws Exception {
        var transport = new Transport(request -> response(request, 200, Map.of(), new byte[] { 1, 2 }));
        try (var http = new ReleaseHttpClient(transport)) {
            assertThrows(IOException.class, () -> http.download(artifact(2, "0".repeat(64)), directory.resolve(
                "cancelled.jar"), () -> true, count -> {
                }));
            assertEquals(0, transport.requests);
            AtomicInteger checks = new AtomicInteger();
            assertThrows(IOException.class, () -> http.download(artifact(2, "0".repeat(64)), directory.resolve(
                "cancelled.jar"), () -> checks.getAndIncrement() > 0, count -> {
                }));
            assertFalse(Files.exists(directory.resolve("cancelled.jar.part")));
        }
    }

    @Test
    void preservesAnExistingPartialFileThatThisTransferDidNotCreate() throws Exception {
        var transport = new Transport(request -> response(request, 200, Map.of(), new byte[] { 1, 2 }));
        Path target = directory.resolve("new.jar");
        Path partial = directory.resolve("new.jar.part");
        Files.writeString(partial, "unrelated content");
        try (var http = new ReleaseHttpClient(transport)) {
            assertThrows(IOException.class, () -> http.download(artifact(2, "0".repeat(64)), target, () -> false,
                count -> {
                }));
            assertEquals("unrelated content", Files.readString(partial));
            assertFalse(Files.exists(target));
        }
    }

    private static Response response(HttpRequest request, int status, Map<String, List<String>> headers, byte[] body) {
        return new Response(status, HttpHeaders.of(headers, (key, value) -> true), request, new ByteArrayInputStream(
            body));
    }

    private static ReleaseManifest.Artifact artifact(long size, String hash) {
        return new ReleaseManifest.Artifact("core:26.2", "core", "catbud_core", "26.2", ">=0.19.5", 25, "1.0.4",
            "catbud-core-26.2-1.0.4.jar",
            "https://github.com/FreshChromatic/catbud-addons/releases/download/v1.0.4/catbud-core-26.2-1.0.4.jar", size,
            hash, List.of(), Map.of());
    }
}
