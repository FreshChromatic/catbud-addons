package github.freshchromatic.catbud_addons.public_warps;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CloudWarpApiClient {

    private static final Gson     JSON = new Gson();
    private final HttpClient      http;
    private final WarpServerScope scope;

    public record Player(String uuid, String name) {}

    public record Discoveries(List<Player> players) {}

    public record Availability(String uuid, String name, String reason, String submissionId) {

        public Availability(String uuid, String name, String reason) {
            this(uuid, name, reason, UUID.randomUUID().toString());
        }
    }

    public record Preflight(String uuid, String name, String hash, int size) {}

    public record Ticket(boolean needed, String ticket, long expiresAt) {}

    public CloudWarpApiClient(HttpClient http) {
        this(http, null);
    }

    public CloudWarpApiClient(HttpClient http, WarpServerScope scope) {
        this.http = http;
        this.scope = scope;
    }

    private String base(String service) {
        return service + (scope == null ? "/v1" : "/v2/servers/" + scope.id);
    }

    private void validateServer(com.google.gson.JsonObject document) throws IOException {
        if (scope != null && (!document.has("serverId") || !scope.id.equals(document.get("serverId").getAsString()))) {
            throw new IOException("Shared response belongs to another server");
        }
    }

    public WarpApiClient.WarpList fetchWarps(String service, String etag) throws Exception {
        return fetchWarps(service, etag, null);
    }

    public WarpApiClient.WarpList fetchWarps(String service, String etag, Long revision) throws Exception {
        boolean force = false;
        String hour = String.valueOf(System.currentTimeMillis() / 3600000);
        if (revision != null && revision >= 0 && etag != null && etag.equals("\"" + (scope == null ? ""
            : scope.id + ":") + revision + ":" + hour + ":\"")) {
            try {
                return fetchChanges(service, revision);
            } catch (ListChangedException unavailableHistory) {
                etag = null;
                force = true;
            }
        }
        for (int restart = 0; restart < 3; restart++) {
            try {
                return fetchPages(service, etag, force);
            } catch (ListChangedException changed) {
                etag = null;
                force = true;
            }
        }
        throw new IOException("Shared list changed repeatedly during pagination");
    }

    private WarpApiClient.WarpList fetchPages(String service, String etag, boolean force) throws Exception {
        var rows = new ArrayList<WarpData>();
        String next = null;
        long revision = -1;
        String firstEtag = null;
        for (int page = 0; page < 51; page++) {
            String query = next == null ? "" : "?after=" + next + "&revision=" + revision;
            var builder = HttpRequest.newBuilder(URI.create(base(service) + "/warps" + query)).timeout(Duration
                .ofSeconds(15));
            if (page == 0 && etag != null) {
                builder.header("If-None-Match", etag);
            }
            if (force) {
                builder.header("Cache-Control", "no-cache");
            }
            var response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (var stream = response.body()) {
                body = stream.readNBytes(262145);
            }
            if (body.length > 262144) {
                throw new IOException("Shared list page exceeds limit");
            }
            if (response.statusCode() == 304 && page == 0) {
                return new WarpApiClient.WarpList(false, List.of(), etag);
            }
            if (response.statusCode() == 409) {
                throw new ListChangedException();
            }
            if (response.statusCode() != 200) {
                throw new IOException("Shared list unavailable: " + response.statusCode());
            }
            var document = JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
            validateServer(document);
            long currentRevision = document.get("revision").getAsLong();
            if (page == 0) {
                revision = currentRevision;
                firstEtag = response.headers().firstValue("etag").orElse(null);
            } else if (revision != currentRevision) {
                throw new ListChangedException();
            }
            var elements = document.getAsJsonArray("warps");
            if (elements.size() > 100) {
                throw new IOException("Too many shared list entries");
            }
            for (var element : elements) {
                WarpData warp = JSON.fromJson(element, WarpData.class);
                validateWarp(warp);
                rows.add(warp);
            }
            if (rows.size() > 5000) {
                throw new IOException("Shared list exceeds entry limit");
            }
            var cursor = document.get("next");
            if (cursor == null || cursor.isJsonNull()) {
                return new WarpApiClient.WarpList(true, rows, firstEtag, revision, List.of(), true);
            }
            String previous = next;
            next = cursor.getAsString();
            UUID.fromString(next);
            if (next.equals(previous) || rows.size() > 5000) {
                throw new IOException("Invalid shared list pagination");
            }
        }
        throw new IOException("Shared list exceeds page limit");
    }

    private WarpApiClient.WarpList fetchChanges(String service, long since) throws Exception {
        var updates = new java.util.LinkedHashMap<String, WarpData>();
        var deleted = new java.util.HashSet<String>();
        long after = since;
        Long revision = null;
        String etag = null;
        for (int page = 0; page < 50; page++) {
            String query = "?since=" + since + "&after=" + after;
            if (revision != null) {
                query += "&revision=" + revision;
            }
            var request = HttpRequest.newBuilder(URI.create(base(service) + "/changes" + query)).timeout(Duration
                .ofSeconds(15)).GET().build();
            var response = send(request, 262144);
            if (response.result().status() == 409 || response.result().status() == 410) {
                throw new ListChangedException();
            }
            if (response.result().status() != 200) {
                throw new IOException("Shared changes unavailable: " + response.result().status());
            }
            var document = JsonParser.parseString(response.body()).getAsJsonObject();
            validateServer(document);
            long current = document.get("revision").getAsLong();
            if (current < since || (revision != null && current != revision)) {
                throw new ListChangedException();
            }
            revision = current;
            etag = document.get("etag").getAsString();
            var changes = document.getAsJsonArray("changes");
            if (changes.size() > 100) {
                throw new IOException("Too many shared changes");
            }
            for (var item : changes) {
                var change = item.getAsJsonObject();
                long sequence = change.get("revision").getAsLong();
                if (sequence <= after || sequence > revision) {
                    throw new IOException("Invalid shared change order");
                }
                after = sequence;
                String uuid = change.get("uuid").getAsString();
                validateUuid(uuid);
                var deletion = change.get("deleted");
                if (deletion == null || !deletion.isJsonPrimitive() || !deletion.getAsJsonPrimitive().isBoolean()) {
                    throw new IOException("Invalid shared deletion flag");
                }
                if (deletion.getAsBoolean()) {
                    updates.remove(uuid);
                    deleted.add(uuid);
                } else {
                    var warp = JSON.fromJson(change.get("warp"), WarpData.class);
                    validateWarp(warp);
                    if (!uuid.equals(warp.uuid)) {
                        throw new IOException("Invalid shared update");
                    }
                    deleted.remove(uuid);
                    updates.put(uuid, warp);
                }
            }
            var next = document.get("next");
            if (next == null || next.isJsonNull()) {
                return new WarpApiClient.WarpList(true, List.copyOf(updates.values()), etag, revision, List.copyOf(
                    deleted), false);
            }
            if (changes.isEmpty() || next.getAsLong() != after) {
                throw new IOException("Invalid shared change pagination");
            }
        }
        throw new ListChangedException();
    }

    private void validateWarp(WarpData warp) throws IOException {
        if (warp == null || warp.name == null || !warp.name.matches("\\.?[A-Za-z0-9_]{1,16}") || warp.tags == null) {
            throw new IOException("Invalid shared player");
        }
        if (scope != null && !scope.id.equals(warp.serverId)) {
            throw new IOException("Shared warp belongs to another server");
        }
        validateUuid(warp.uuid);
        warp.locallyDiscovered = false;
    }

    private void validateUuid(String uuid) throws IOException {
        if (uuid == null || !uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Invalid shared UUID");
        }
    }

    public WarpApiClient.ReportResult discover(String service, String credential, Discoveries discoveries)
        throws Exception {
        return postJson(base(service) + "/discoveries", credential, discoveries).result();
    }

    public WarpApiClient.ReportResult availability(String service, String credential, Availability value)
        throws Exception {
        return postJson(base(service) + (scope == null ? "/availability-reports" : "/observations"), credential, value)
            .result();
    }

    public WarpApiClient.ReportResult upload(String service, String credential, WarpStore.Report report)
        throws Exception {
        if (scope != null) {
            var observation = availability(service, credential, new Availability(report.uuid(), report.name(),
                "successful_arrival", report.submissionId()));
            if (observation.status() != 200 && observation.status() != 202) {
                return observation;
            }
            if (report.snapshot() == null) {
                return observation;
            }
        }
        if (report.snapshot() == null) {
            return availability(service, credential, new Availability(report.uuid(), report.name(),
                "successful_arrival"));
        }
        BlockSnapshot local = BlockSnapshot.decode(java.util.Base64.getDecoder().decode(report.snapshot()));
        SharedPreview preview;
        try {
            preview = SharedPreview.from(local);
        } catch (SharedPreview.LimitException oversized) {
            return new WarpApiClient.ReportResult(413, null);
        }
        var preflight = postJson(base(service) + "/submissions/preflight", credential, new Preflight(report.uuid(),
            report.name(), preview.digest(), 65536));
        if (preflight.result().status() != 200) {
            return preflight.result();
        }
        Ticket ticket = JSON.fromJson(preflight.body(), Ticket.class);
        if (!ticket.needed()) {
            if (scope != null) {
                return preflight.result();
            }
            var result = JsonParser.parseString(preflight.body()).getAsJsonObject();
            boolean published = result.has("published") && result.get("published").getAsBoolean();
            return new WarpApiClient.ReportResult(published ? 200 : 202, null, published ? "published"
                : "pending_review", result.has("submissionId") ? result.get("submissionId").getAsString() : null);
        }
        UUID.fromString(ticket.ticket());
        var request = HttpRequest.newBuilder(URI.create(base(service) + (scope == null ? "/submissions/" : "/uploads/")
            + ticket.ticket())).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + credential).header(
                "Content-Type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray(preview
                    .bytes())).build();
        return send(request).result();
    }

    public byte[] fetchPreview(String service, String hash) throws Exception {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid preview digest");
        }
        var request = HttpRequest.newBuilder(URI.create(base(service) + "/previews/" + hash)).timeout(Duration
            .ofSeconds(15)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            byte[] bytes = stream.readNBytes(65537);
            if (response.statusCode() != 200 || bytes.length > 65536) {
                throw new IOException("Shared preview unavailable");
            }
            return bytes;
        }
    }

    public WarpApiClient.ReportResult submission(String service, String credential, String id) throws Exception {
        UUID.fromString(id);
        var request = HttpRequest.newBuilder(URI.create(base(service) + "/submissions/" + id)).timeout(Duration
            .ofSeconds(15)).header("Authorization", "Bearer " + credential).GET().build();
        return send(request).result();
    }

    private record ResponseData(WarpApiClient.ReportResult result, String body) {}

    private ResponseData postJson(String url, String credential, Object value) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Authorization",
            "Bearer " + credential).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                JSON.toJson(value))).build();
        return send(request);
    }

    private ResponseData send(HttpRequest request) throws Exception {
        return send(request, 65536);
    }

    private ResponseData send(HttpRequest request, int limit) throws Exception {
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var stream = response.body()) {
            bytes = stream.readNBytes(limit + 1);
        }
        if (bytes.length > limit) {
            throw new IOException("Cloud response exceeds limit");
        }
        String body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String state = null, submissionId = null, reason = null;
        if (scope != null && (response.statusCode() == 200 || response.statusCode() == 202)) {
            var document = JsonParser.parseString(body).getAsJsonObject();
            if (document.has("serverId")) {
                validateServer(document);
            }
            if (document.has("state")) {
                state = document.get("state").getAsString();
            } else if (document.has("published")) {
                state = document.get("published").getAsBoolean() ? "published" : "pending_review";
            }
            if (document.has("reason") && !document.get("reason").isJsonNull()) {
                reason = document.get("reason").getAsString();
                if (reason.length() > 500) {
                    reason = reason.substring(0, 500);
                }
            }
            if (document.has("submissionId") && !document.get("submissionId").isJsonNull()) {
                submissionId = document.get("submissionId").getAsString();
            }
        }
        return new ResponseData(new WarpApiClient.ReportResult(response.statusCode(), response.headers().firstValue(
            "Retry-After").orElse(null), state, submissionId, reason), body);
    }

    private static final class ListChangedException extends IOException {}
}
