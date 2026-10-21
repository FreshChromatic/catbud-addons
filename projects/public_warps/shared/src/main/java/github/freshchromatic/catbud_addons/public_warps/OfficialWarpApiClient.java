package github.freshchromatic.catbud_addons.public_warps;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class OfficialWarpApiClient {

    private final HttpClient http;
    private final URI        endpoint;

    public OfficialWarpApiClient(HttpClient http) {
        this(http, URI.create("https://api.catbud.net/publics"));
    }

    OfficialWarpApiClient(HttpClient http, URI endpoint) {
        this.http = http;
        this.endpoint = endpoint;
    }

    public Map<String, String> fetch() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var stream = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Official warp list unavailable: " + response.statusCode());
            }
            bytes = stream.readNBytes(1048577);
        }
        return parseResponse(bytes);
    }

    static Map<String, String> parseResponse(byte[] bytes) throws IOException {
        if (bytes.length > 1048576) {
            throw new IOException("Official warp list exceeds response limit");
        }
        try {
            var document = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            var entries = document.getAsJsonArray("publics");
            if (entries == null || entries.size() > 5000) {
                throw new IOException("Invalid official warp list size");
            }
            var players = new LinkedHashMap<String, String>();
            var names = new HashSet<String>();
            for (var entry : entries) {
                var player = entry.getAsJsonObject();
                var uuidValue = player.get("uuid");
                var nameValue = player.get("name");
                if (uuidValue == null || !uuidValue.isJsonPrimitive() || !uuidValue.getAsJsonPrimitive().isString()
                    || nameValue == null || !nameValue.isJsonPrimitive() || !nameValue.getAsJsonPrimitive()
                        .isString()) {
                    throw new IOException("Invalid official warp identity");
                }
                String uuid = uuidValue.getAsString();
                String name = nameValue.getAsString();
                String normalized = UUID.fromString(uuid).toString();
                if (!normalized.equalsIgnoreCase(uuid) || !name.matches("[A-Za-z0-9_]{1,16}") || players.putIfAbsent(
                    normalized, name) != null || !names.add(name.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IOException("Invalid or duplicate official warp identity");
                }
            }
            return Map.copyOf(players);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid official warp list", invalid);
        }
    }
}
