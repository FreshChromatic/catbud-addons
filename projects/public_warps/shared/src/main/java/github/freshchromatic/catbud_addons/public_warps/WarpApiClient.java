package github.freshchromatic.catbud_addons.public_warps;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class WarpApiClient {

    private static final Gson JSON = new Gson();
    private final HttpClient  http;
    private final URI         playerEndpoint;
    private final URI         identityEndpoint;

    public record WarpList(boolean changed, List<WarpData> warps, String etag, Long revision, List<String> deleted,
        boolean full) {

        public WarpList(boolean changed, List<WarpData> warps, String etag) {
            this(changed, warps, etag, null, List.of(), true);
        }
    }

    public record VerifiedPlayer(String uuid, String name) {}

    public record PlayerQualification(String uuid, String name, boolean activePass) {}

    public static final class InactivePassException extends IOException {

        public final String uuid;

        public InactivePassException(String uuid) {
            super("Player has no active pass");
            this.uuid = uuid;
        }
    }

    public record ReportResult(int status, String retryAfter, String state, String submissionId, String reason) {

        public ReportResult(int status, String retryAfter) {
            this(status, retryAfter, null, null, null);
        }

        public ReportResult(int status, String retryAfter, String state, String submissionId) {
            this(status, retryAfter, state, submissionId, null);
        }

        public boolean terminal() {
            return status == 200 || status == 202 || status == 400 || status == 401 || status == 403 || status == 404
                || status == 410 || status == 413 || status == 422;
        }
    }

    public WarpApiClient(HttpClient http) {
        this(http, URI.create("https://api.catbud.net/discord/player/"), URI.create(
            "https://api.mojang.com/users/profiles/minecraft/"));
    }

    public WarpApiClient forServer(WarpServerScope scope) {
        return new WarpApiClient(http, scope.playerEndpoint(), identityEndpoint);
    }

    WarpApiClient(HttpClient http, URI playerEndpoint, URI identityEndpoint) {
        this.http = http;
        this.playerEndpoint = playerEndpoint;
        this.identityEndpoint = identityEndpoint;
    }

    public WarpList fetchWarps(String service, String etag) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(service + "/v1/warps")).timeout(Duration.ofSeconds(15));
        if (etag != null) {
            builder.header("If-None-Match", etag);
        }
        var response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        String body = readBounded(response, 2 * 1024 * 1024);
        if (response.statusCode() == 304) {
            return new WarpList(false, List.of(), etag);
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        var rows = new ArrayList<WarpData>();
        var document = JsonParser.parseString(body).getAsJsonObject();
        for (var element : document.getAsJsonArray("warps")) {
            var warp = JSON.fromJson(element, WarpData.class);
            boolean validName = warp.name != null && warp.name.matches("[A-Za-z0-9_]{1,16}");
            if (!validName || warp.uuid == null) {
                continue;
            }
            UUID.fromString(warp.uuid);
            rows.add(warp);
        }
        return new WarpList(true, rows, response.headers().firstValue("etag").orElse(null));
    }

    public VerifiedPlayer verifyPlayer(String target, String knownUuid) throws Exception {
        var qualification = checkPlayer(target, knownUuid);
        if (!qualification.activePass()) {
            throw new InactivePassException(qualification.uuid());
        }
        return new VerifiedPlayer(qualification.uuid(), qualification.name());
    }

    public VerifiedPlayer resolvePlayer(String target, String knownUuid) throws Exception {
        String uuid = knownUuid == null ? resolveUuid(target) : knownUuid;
        UUID.fromString(uuid);
        if (!target.matches("\\.?[A-Za-z0-9_]{1,16}")) {
            throw new IOException("Invalid player name");
        }
        return new VerifiedPlayer(uuid.toLowerCase(Locale.ROOT), target);
    }

    public PlayerQualification checkPlayer(String target, String knownUuid) throws Exception {
        String uuid = knownUuid == null ? resolveUuid(target) : knownUuid;
        UUID.fromString(uuid);
        URI endpoint = playerEndpoint.resolve(uuid + "?detailed=true");
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String body = readBounded(response, 1024 * 1024);
        if (response.statusCode() != 200) {
            throw new IOException("Catbud verification failed: " + response.statusCode());
        }
        var player = JsonParser.parseString(body).getAsJsonObject();
        var pass = player.get("pass");
        boolean validPass = pass != null && pass.isJsonPrimitive() && pass.getAsJsonPrimitive().isBoolean();
        if (!validPass) {
            throw new IOException("Invalid pass response");
        }
        boolean hasIdentity = player.has("uuid") && player.has("name");
        if (!hasIdentity) {
            throw new IOException("Catbud identity mismatch");
        }
        String returnedUuid = player.get("uuid").getAsString().replace("-", "");
        String returnedName = player.get("name").getAsString();
        boolean matchingIdentity = returnedUuid.equalsIgnoreCase(uuid.replace("-", "")) && returnedName
            .equalsIgnoreCase(target);
        if (!matchingIdentity) {
            throw new IOException("Catbud identity mismatch");
        }
        return new PlayerQualification(uuid.toLowerCase(Locale.ROOT), returnedName, pass.getAsBoolean());
    }

    private String resolveUuid(String target) throws Exception {
        URI endpoint = identityEndpoint.resolve(target);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String body = readBounded(response, 64 * 1024);
        if (response.statusCode() != 200) {
            throw new IOException("UUID lookup failed");
        }
        String compact = JsonParser.parseString(body).getAsJsonObject().get("id").getAsString();
        return compact.replaceFirst("([0-9a-f]{8})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{12})",
            "$1-$2-$3-$4-$5");
    }

    public byte[] fetchPreview(String service, String hash) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(service + "/v1/snapshots/" + hash)).timeout(Duration.ofSeconds(
            15)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Preview download failed");
            }
            return readBytes(stream, 128 * 1024);
        }
    }

    public ReportResult submitReport(String service, WarpStore.Report report) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(service + "/v1/reports")).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(report)))
            .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        readBounded(response, 64 * 1024);
        return new ReportResult(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null));
    }

    private static String readBounded(HttpResponse<InputStream> response, int limit) throws IOException {
        try (var stream = response.body()) {
            return new String(readBytes(stream, limit), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readBytes(InputStream stream, int limit) throws IOException {
        byte[] bytes = stream.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw new IOException("Response too large");
        }
        return bytes;
    }
}
