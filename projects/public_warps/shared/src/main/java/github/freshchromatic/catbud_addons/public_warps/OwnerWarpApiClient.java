package github.freshchromatic.catbud_addons.public_warps;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OwnerWarpApiClient {

    private static final Gson     JSON = new Gson();
    private final HttpClient      http;
    private final String          origin;
    private final WarpServerScope scope;
    private final String          uuid;

    public record Challenge(String challengeId, String proofKey, String payload) {

        @Override
        public String toString() {
            return "OwnerChallenge";
        }
    }

    public record Session(String ownerToken, long expiresAt) {

        @Override
        public String toString() {
            return "OwnerSession[expiresAt=" + expiresAt + "]";
        }
    }

    public OwnerWarpApiClient(HttpClient http, String service, WarpServerScope scope, UUID account) {
        this.http = http;
        URI uri = URI.create(service);
        boolean local = List.of("localhost", "127.0.0.1", "::1").contains(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || (!"https".equals(uri.getScheme()) && !(local && "http".equals(uri.getScheme()))) || (!uri.getPath()
                .isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Invalid owner service origin");
        }
        origin = service.endsWith("/") ? service.substring(0, service.length() - 1) : service;
        this.scope = scope;
        uuid = account.toString();
    }

    private JsonObject request(String route, Object value, Session session) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + "/v2/servers/" + scope.id + "/owner/" + route))
            .timeout(Duration.ofSeconds(20));
        if (session != null) {
            builder.header("Authorization", "Bearer " + session.ownerToken());
        }
        if (value == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(
                value), StandardCharsets.UTF_8));
        }
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) {
                throw new OwnerEditException(response.statusCode());
            }
            byte[] bytes = body.readNBytes(65537);
            if (bytes.length > 65536) {
                throw new OwnerEditException(503);
            }
            var document = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!scope.id.equals(document.get("serverId").getAsString())) {
                throw new OwnerEditException(403);
            }
            return document;
        }
    }

    public Challenge challenge(String name) throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String nonce = HexFormat.of().formatHex(random);
        var result = request("challenge", Map.of("uuid", uuid, "name", name, "clientNonce", nonce), null);
        if (!uuid.equals(result.get("uuid").getAsString())) {
            throw new OwnerEditException(403);
        }
        String serverNonce = result.get("serverNonce").getAsString();
        String hash = result.get("serverHash").getAsString();
        String proof = result.get("proofKey").getAsString();
        String id = result.get("challengeId").getAsString();
        UUID.fromString(id);
        if (!serverNonce.matches("[0-9a-f]{64}") || !proof.matches("[0-9a-f]{64}")) {
            throw new OwnerEditException(403);
        }
        String input = String.join("\n", "catbud-owner-edit-v1", origin, scope.id, uuid, nonce, serverNonce);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(
            StandardCharsets.UTF_8))).substring(0, 40);
        // Domain separation prevents an editing backend from relaying another Minecraft login hash.
        if (!expected.equals(hash)) {
            throw new OwnerEditException(403);
        }
        String payload = String.join("\n", "catbud-owner-edit-proof-v1", origin, scope.id, uuid, id, hash);
        return new Challenge(id, proof, payload);
    }

    public Session verify(Challenge challenge, OfficialAccountProof.SignedProof proof) throws Exception {
        var result = request("verify", Map.of("challengeId", challenge.challengeId(), "proofKey", challenge.proofKey(),
            "certificate", proof.certificate(), "signature", proof.signature()), null);
        if (!uuid.equals(result.get("uuid").getAsString())) {
            throw new OwnerEditException(403);
        }
        String token = result.get("ownerToken").getAsString();
        if (!token.matches("[0-9a-f]{64}")) {
            throw new OwnerEditException(403);
        }
        return new Session(token, result.get("expiresAt").getAsLong());
    }

    private WarpData warp(JsonObject result) throws Exception {
        var warp = JSON.fromJson(result.get("warp"), WarpData.class);
        if (warp == null || !uuid.equals(warp.uuid) || !scope.id.equals(warp.serverId) || warp.tags == null) {
            throw new OwnerEditException(403);
        }
        return warp;
    }

    public WarpData current(Session session) throws Exception {
        return warp(request("warp", null, session));
    }

    public WarpData save(Session session, String displayName, String description, int revision) throws Exception {
        return warp(request("metadata", Map.of("displayName", displayName, "description", description,
            "expectedRevision", revision, "requestId", UUID.randomUUID().toString()), session));
    }
}
