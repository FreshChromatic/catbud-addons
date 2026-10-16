package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Tag("cloud-integration")
class CloudProtocolIntegrationTest {

    private static final String ID         = "a29a6851-0d71-411c-953c-b87bab0d12f3";
    private static final String CREDENTIAL = "a".repeat(64);

    @Test
    void twoJavaClientsShareAnApprovedPreviewAndReceiveAuthoritativePassChanges(@TempDir Path temp) throws Exception {
        Path fixture = fixturePath();
        Path errors = temp.resolve("fixture-errors.log");
        var process = new ProcessBuilder("node", fixture.toString()).redirectError(errors.toFile()).start();
        var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var readerHttp = HttpClient.newHttpClient()) {
            String ready = CompletableFuture.supplyAsync(() -> {
                try {
                    return output.readLine();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }).get(30, TimeUnit.SECONDS);
            assertNotNull(ready, () -> readErrors(errors));
            int port = JsonParser.parseString(ready).getAsJsonObject().get("port").getAsInt();
            String service = "http://127.0.0.1:" + port;
            var contributor = new CloudWarpApiClient(http, WarpServerScope.CATBUD);
            var reader = new CloudWarpApiClient(readerHttp, WarpServerScope.CATBUD);
            var realmReader = new CloudWarpApiClient(readerHttp, WarpServerScope.CATREALM);
            var identity = new CloudWarpApiClient.Player(ID, "Neko_1212");
            assertEquals(202, contributor.discover(service, CREDENTIAL, new CloudWarpApiClient.Discoveries(List.of(
                identity))).status());
            var snapshot = new BlockSnapshot(50, 50, 50, "minecraft:overworld");
            snapshot.palette.add("minecraft:stone");
            snapshot.entities.add(new BlockSnapshot.SnapshotEntity("minecraft:armor_stand", 1, 1, 1, 0, 0, null, null,
                Map.of("equipment", "{head:{id:\"minecraft:leather_helmet\",count:1,components:{"
                    + "\"minecraft:dyed_color\":123,\"minecraft:item_name\":\"private-name\"}}}")));
            byte[] encoded = snapshot.encode();
            try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(encoded))) {
                String json = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
                int start = json.indexOf("\"blocks\":[") + 10;
                int end = json.indexOf(']', start);
                for (int index = start; index < end; index++) {
                    char value = json.charAt(index);
                    assertTrue(value == '0' || value == ',', "Encoded character " + index + ": " + json.substring(Math
                        .max(start, index - 15), Math.min(end, index + 15)));
                }
            }
            assertArrayEquals(snapshot.blocks, BlockSnapshot.decode(encoded).blocks);
            var report = new WarpStore.Report(ID, identity.name(), true, true, Base64.getEncoder().encodeToString(
                encoded), System.currentTimeMillis());
            var submitted = contributor.upload(service, CREDENTIAL, report);
            assertEquals(202, submitted.status(), () -> readErrors(errors));
            assertEquals("pending_review", submitted.state());
            assertEquals("pending_review", contributor.submission(service, CREDENTIAL, submitted.submissionId())
                .state());
            assertTrue(reader.fetchWarps(service, null).warps().isEmpty());
            control(http, service, "approve");
            var published = reader.fetchWarps(service, null);
            assertEquals(1, published.warps().size());
            var warp = published.warps().getFirst();
            assertTrue(warp.canTeleport());
            var downloaded = BlockSnapshot.decode(reader.fetchPreview(service, warp.snapshotHash));
            assertEquals("catbud", warp.serverId);
            assertTrue(realmReader.fetchWarps(service, null).warps().isEmpty());
            assertThrows(java.io.IOException.class, () -> realmReader.fetchPreview(service, warp.snapshotHash));
            assertEquals(125000, downloaded.blocks.length);
            assertEquals(4, downloaded.version);
            var equipment = downloaded.entities.getFirst().appearance().get("equipment");
            assertTrue(equipment.contains("minecraft:leather_helmet"));
            assertFalse(equipment.contains("private-name"));
            var store = new WarpStore();
            store.mergeCloud(published);
            control(http, service, "disable");
            assertEquals("unavailable", reader.fetchWarps(service, null).warps().getFirst().availability);
            var restored = contributor.availability(service, CREDENTIAL, new CloudWarpApiClient.Availability(ID,
                identity.name(), "successful_arrival", java.util.UUID.randomUUID().toString()));
            assertEquals(200, restored.status());
            assertEquals("available", reader.fetchWarps(service, null).warps().getFirst().availability);
            control(http, service, "no-pass");
            var changed = reader.fetchWarps(service, published.etag(), published.revision());
            assertFalse(changed.full());
            store.mergeCloud(changed);
            assertEquals(1, store.warps.size());
            assertFalse(store.warps.getFirst().canTeleport());
            assertEquals("warps.status.no_pass", store.warps.getFirst().warningKey());
            assertEquals(warp.snapshotHash, store.warps.getFirst().snapshotHash);
        } finally {
            if (process.isAlive()) {
                try {
                    process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                    process.getOutputStream().flush();
                    process.getOutputStream().close();
                } catch (java.io.IOException closedFixture) {
                    process.destroy();
                }
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            output.close();
        }
    }

    private static void control(HttpClient http, String service, String action) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(service + "/fixture/" + action)).timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + CREDENTIAL).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(200, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private static Path fixturePath() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path fixture = directory.resolve("services/public-warps-cloud/test/client-fixture.mjs");
            if (Files.isRegularFile(fixture)) {
                return fixture;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Local cloud fixture missing");
    }

    private static String readErrors(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception failure) {
            return "Fixture did not start";
        }
    }
}
