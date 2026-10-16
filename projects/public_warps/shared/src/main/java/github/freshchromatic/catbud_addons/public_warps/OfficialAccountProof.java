package github.freshchromatic.catbud_addons.public_warps;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class OfficialAccountProof {

    public record Certificate(String publicKey, long expiresAt, String signature) {}

    public record SignedProof(Certificate certificate, String signature) {

        @Override
        public String toString() {
            return "OwnerSignedProof";
        }
    }

    private final HttpClient http;
    private final UUID       uuid;
    private final String     accessToken;

    public OfficialAccountProof(HttpClient http, UUID uuid, String accessToken) {
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Official account requests must not follow redirects");
        }
        this.http = http;
        this.uuid = uuid;
        this.accessToken = accessToken;
    }

    public SignedProof sign(String payload) throws Exception {
        if (accessToken == null || accessToken.isBlank() || accessToken.equals("0")) {
            throw new OwnerEditException(401);
        }
        if (!payload.startsWith("catbud-owner-edit-proof-v1\n") || !payload.contains("\n" + uuid + "\n")) {
            throw new OwnerEditException(403);
        }
        var request = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/player/certificates"))
            .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + accessToken).header("Content-Type",
                "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) {
                throw new OwnerEditException(response.statusCode() == 403 || response.statusCode() == 401 ? 401 : 503);
            }
            byte[] bytes = body.readNBytes(65537);
            if (bytes.length > 65536) {
                throw new OwnerEditException(503);
            }
            var data = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            return signCertificate(data, payload);
        }
    }

    static SignedProof signCertificate(JsonObject data, String payload) throws Exception {
        var pair = data.getAsJsonObject("keyPair");
        var factory = KeyFactory.getInstance("RSA");
        var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(pem(pair.get("privateKey").getAsString(),
            "PRIVATE")));
        var publicKey = factory.generatePublic(new X509EncodedKeySpec(pem(pair.get("publicKey").getAsString(),
            "PUBLIC")));
        long expires = Instant.parse(data.get("expiresAt").getAsString()).toEpochMilli();
        if (expires <= System.currentTimeMillis()) {
            throw new OwnerEditException(401);
        }
        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        var certificate = new Certificate(Base64.getEncoder().encodeToString(publicKey.getEncoded()), expires, data.get(
            "publicKeySignatureV2").getAsString());
        return new SignedProof(certificate, Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static byte[] pem(String value, String type) {
        String start = "-----BEGIN RSA " + type + " KEY-----";
        String end = "-----END RSA " + type + " KEY-----";
        if (!value.startsWith(start) || !value.stripTrailing().endsWith(end)) {
            throw new IllegalArgumentException("Invalid official signing key");
        }
        return Base64.getMimeDecoder().decode(value.substring(start.length(), value.indexOf(end)));
    }
}
