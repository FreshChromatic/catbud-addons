package github.freshchromatic.catbud_addons.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

public final class ReleaseHttpClient implements AutoCloseable {

    private volatile InputStream activeDownload;

    public static final String API = "https://api.cbwp.workers.dev/addons/v1/";

    private final ReleaseTransport         transport;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(work -> {
                                                         var thread = new Thread(work, "catbud-download-deadline");
                                                         thread.setDaemon(true);
                                                         return thread;
                                                     });

    public ReleaseHttpClient() {
        this(new JdkReleaseTransport());
    }

    public ReleaseHttpClient(ReleaseTransport transport) {
        this.transport = java.util.Objects.requireNonNull(transport);
    }

    public static final class RequestFailure extends IOException {

        private final long retrySeconds;

        RequestFailure(int status, long retrySeconds) {
            super("Update service returned HTTP " + status);
            this.retrySeconds = retrySeconds;
        }

        public long retrySeconds() {
            return retrySeconds;
        }
    }

    public record JsonResponse(String body, String etag, boolean unchanged) {}

    public JsonResponse json(String route, String etag, boolean fresh) throws Exception {
        URI uri = URI.create(API + route);
        if (!uri.toString().startsWith(API)) {
            throw new IOException("Invalid update API route");
        }
        var builder = request(uri).header("Accept", "application/json");
        if (fresh) {
            builder.header("Cache-Control", "no-cache");
        } else if (!etag.isEmpty()) {
            builder.header("If-None-Match", etag);
        }
        var response = transport.send(builder.GET().build());
        try (var body = response.body()) {
            if (response.statusCode() == 304) {
                return new JsonResponse("", etag, true);
            }
            requireSuccess(response);
            if (!response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) {
                throw new IOException("Update service did not return JSON");
            }
            var timeout = deadlines.schedule(() -> close(body), 15, TimeUnit.SECONDS);
            try {
                byte[] bytes = body.readNBytes(512 * 1024 + 1);
                if (bytes.length > 512 * 1024) {
                    throw new IOException("Update response exceeds size limit");
                }
                return new JsonResponse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), response.headers()
                    .firstValue("ETag").orElse(""), false);
            } finally {
                timeout.cancel(false);
            }
        }
    }

    public void download(ReleaseManifest.Artifact artifact, Path destination, BooleanSupplier cancelled, LongConsumer progress)
        throws Exception {
        if (cancelled.getAsBoolean()) {
            throw new IOException("Download cancelled");
        }
        URI uri = URI.create(artifact.url());
        String root = "https://github.com/" + ReleaseManifest.REPOSITORY + "/releases/download/";
        if (!uri.toString().startsWith(root)) {
            throw new IOException("Untrusted release download");
        }
        HttpResponse<InputStream> response = null;
        for (int hop = 0; hop < 5; hop++) {
            requireDownloadHost(uri);
            response = transport.send(request(uri).GET().build());
            if (List.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
                response.body().close();
                uri = uri.resolve(response.headers().firstValue("Location").orElseThrow());
                response = null;
                continue;
            }
            break;
        }
        if (response == null) {
            throw new IOException("Too many release download redirects");
        }
        Path partial = destination.resolveSibling(destination.getFileName() + ".part");
        boolean createdPartial = false;
        try (var input = response.body()) {
            activeDownload = input;
            requireSuccess(response);
            var timeout = deadlines.schedule(() -> close(input), 120, TimeUnit.SECONDS);
            try (var output = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW)) {
                createdPartial = true;
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int count = input.read(buffer); count != -1; count = input.read(buffer)) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Download cancelled");
                    }
                    total += count;
                    if (total > artifact.size() || total > ReleaseManifest.MAX_ARTIFACT_BYTES) {
                        throw new IOException("Release download exceeds declared size");
                    }
                    output.write(buffer, 0, count);
                    progress.accept(total);
                }
                if (total != artifact.size()) {
                    throw new IOException("Incomplete release download");
                }
            } finally {
                timeout.cancel(false);
            }
            github.freshchromatic.catbud_addons.update.install.FileIntegrity.require(partial, artifact.sha256());
            JarMetadata metadata = JarMetadata.read(partial);
            if (!metadata.modId().equals(artifact.modId()) || !metadata.version().equals(artifact.version())
                || !metadata.requires().equals(artifact.requires()) || !metadata.provides().equals(artifact
                    .provides())) {
                throw new IOException("Downloaded addon metadata differs from signed manifest");
            }
            Files.move(partial, destination);
        } finally {
            activeDownload = null;
            if (createdPartial) {
                Files.deleteIfExists(partial);
            }
        }
    }

    private static void requireDownloadHost(URI uri) throws IOException {
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1 || !List.of(
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com").contains(uri
                .getHost())) {
            throw new IOException("Untrusted release redirect");
        }
    }

    public void cancelDownload() {
        InputStream input = activeDownload;
        if (input != null) {
            close(input);
        }
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("User-Agent",
            "Catbud-Addons-Updater/1");
    }

    private static void requireSuccess(HttpResponse<?> response) throws RequestFailure {
        if (response.statusCode() != 200) {
            long retry = 0;
            try {
                retry = Math.clamp(Long.parseLong(response.headers().firstValue("Retry-After").orElse("0")), 0, 86400);
            } catch (NumberFormatException ignored) {
                retry = 0;
            }
            throw new RequestFailure(response.statusCode(), retry);
        }
    }

    private static void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        cancelDownload();
        deadlines.shutdownNow();
        transport.close();
    }
}
