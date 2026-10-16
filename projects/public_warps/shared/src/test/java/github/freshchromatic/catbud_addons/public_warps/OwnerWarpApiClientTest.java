package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

class OwnerWarpApiClientTest {

    @Test
    void signsChallengeWithOfficialCertificateEncodingWithoutSendingPrivateKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        String privatePem = "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(keys.getPrivate()
            .getEncoded()) + "\n-----END RSA PRIVATE KEY-----\n";
        String publicPem = "-----BEGIN RSA PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(keys.getPublic()
            .getEncoded()) + "\n-----END RSA PUBLIC KEY-----\n";
        var json = new Gson();
        var data = json.toJsonTree(Map.of("keyPair", Map.of("privateKey", privatePem, "publicKey", publicPem),
            "expiresAt", Instant.now().plusSeconds(3600).toString(), "publicKeySignatureV2", "official-fixture"))
            .getAsJsonObject();
        var proof = OfficialAccountProof.signCertificate(data, "unique-owner-challenge");
        var verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update("unique-owner-challenge".getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(proof.signature())));
        assertEquals(Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), proof.certificate()
            .publicKey());
        assertFalse(json.toJson(proof).contains("privateKey"));
        assertFalse(json.toJson(proof).contains("accessToken"));
        assertEquals("OwnerSignedProof", proof.toString());
    }

    @Test
    void verifiesDomainSeparatedChallengeAndOnlySendsOwnerEditingFields() throws Exception {
        UUID uuid = UUID.fromString("257205c3-c703-4034-8af8-14ee22720ceb");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        var forged = new AtomicBoolean();
        server.createContext("/v2/servers/catbud/owner/", exchange -> {
            try {
                String requestText = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var input = requestText.isEmpty() ? null : JsonParser.parseString(requestText).getAsJsonObject();
                String route = exchange.getRequestURI().getPath();
                String result;
                if (route.endsWith("challenge")) {
                    assertFalse(input.has("accessToken"));
                    String serverNonce = "2".repeat(64);
                    String hashInput = String.join("\n", "catbud-owner-edit-v1", origin, "catbud", uuid.toString(),
                        input.get("clientNonce").getAsString(), serverNonce);
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(hashInput
                        .getBytes(StandardCharsets.UTF_8))).substring(0, 40);
                    if (forged.get()) {
                        hash = "0".repeat(40);
                    }
                    result = "{\"serverId\":\"catbud\",\"uuid\":\"" + uuid + "\",\"challengeId\":\"" + UUID.randomUUID()
                        + "\",\"proofKey\":\"" + "3".repeat(64) + "\",\"serverNonce\":\"" + serverNonce
                        + "\",\"serverHash\":\"" + hash + "\"}";
                } else if (route.endsWith("verify")) {
                    assertEquals(4, input.size());
                    assertFalse(input.has("accessToken"));
                    assertFalse(input.getAsJsonObject("certificate").has("privateKey"));
                    result = "{\"serverId\":\"catbud\",\"uuid\":\"" + uuid + "\",\"ownerToken\":\"" + "4".repeat(64)
                        + "\",\"expiresAt\":" + (System.currentTimeMillis() + 300000) + "}";
                } else {
                    assertEquals("Bearer " + "4".repeat(64), exchange.getRequestHeaders().getFirst("Authorization"));
                    if (route.endsWith("metadata")) {
                        assertEquals(4, input.size());
                        assertFalse(input.has("uuid"));
                        assertFalse(input.has("tags"));
                        assertEquals("Market", input.get("displayName").getAsString());
                    }
                    result = "{\"serverId\":\"catbud\",\"warp\":{\"serverId\":\"catbud\",\"uuid\":\"" + uuid
                        + "\",\"name\":\"YuzuCha323\",\"displayName\":\"Market\",\"tags\":[],\"revision\":2}}";
                }
                byte[] response = result.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable failure) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var http = HttpClient.newHttpClient()) {
            var api = new OwnerWarpApiClient(http, origin, WarpServerScope.CATBUD, uuid);
            var challenge = api.challenge("YuzuCha323");
            assertTrue(challenge.payload().startsWith("catbud-owner-edit-proof-v1\n" + origin));
            var certificate = new OfficialAccountProof.Certificate("public-key", System.currentTimeMillis() + 60000,
                "certificate-signature");
            var session = api.verify(challenge, new OfficialAccountProof.SignedProof(certificate,
                "challenge-signature"));
            assertFalse(session.toString().contains(session.ownerToken()));
            assertEquals(uuid.toString(), api.current(session).uuid);
            assertEquals("Market", api.save(session, "Market", "Trading area", 1).displayName);
            forged.set(true);
            assertEquals(403, assertThrows(OwnerEditException.class, () -> api.challenge("YuzuCha323")).status());
        } finally {
            server.stop(0);
        }
    }
}
