package github.freshchromatic.catbud_addons.update;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import github.freshchromatic.catbud_addons.update.install.FileIntegrity;
import github.freshchromatic.catbud_addons.update.install.InstallationPlan;
import github.freshchromatic.catbud_addons.update.install.UpdateInstaller;
import github.freshchromatic.catbud_addons.update.install.UpdateJournal;
import github.freshchromatic.catbud_addons.update.release.ReleaseTool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseRevisionIntegrationTest {

    private static final String            VERSION = "1.0.2";
    private static final List<AddonModule> MODULES = List.of(AddonModule.CORE, AddonModule.MAGIC_TOWER,
        AddonModule.PUBLIC_WARPS);

    @TempDir Path temporary;

    @Test
    void preparesSignedRevisionsAndInstallsOnlyChangedContentWithRecovery() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(temporary.resolve("private.key"), Base64.getEncoder().encodeToString(pair.getPrivate()
            .getEncoded()));
        Files.writeString(temporary.resolve("keys.json"), new Gson().toJson(Map.of("test", Base64.getEncoder()
            .encodeToString(pair.getPublic().getEncoded()))));
        Files.writeString(temporary.resolve("modules.json"), new Gson().toJson(MODULES.stream().map(AddonModule::id)
            .toList()));
        Files.writeString(temporary.resolve("notes.json"), "{\"core\":\"Same-version correction\"}");
        Path artifacts = Files.createDirectory(temporary.resolve("artifacts"));
        for (var minecraft : ReleaseManifest.MINECRAFT_VERSIONS) {
            for (var module : MODULES) {
                writeJar(artifacts, module, minecraft, "original");
            }
        }
        var verifier = new ManifestVerifier(Map.of("test", pair.getPublic()));
        var original = prepare(verifier, 1);
        Path mods = Files.createDirectory(temporary.resolve("mods")).toRealPath();
        for (var module : MODULES) {
            String file = new ReleaseRevision(VERSION, 1).file(module.id(), "26.2");
            Files.copy(artifacts.resolve(file), mods.resolve(file));
        }
        writeJar(artifacts, AddonModule.CORE, "26.2", "corrected");
        var revised = prepare(verifier, 2);
        var inventory = InstalledAddonInventory.scan(mods, MODULES.stream().map(AddonModule::modId).toList());
        var plan = UpdatePlanner.plan(revised, inventory, "26.2", "0.19.5", 25);
        assertEquals(1, plan.replacements().size());
        var replacement = plan.replacements().getFirst();
        assertEquals("core", replacement.artifact().module());
        assertEquals(VERSION, replacement.artifact().version());
        assertEquals("v1.0.2-r2", plan.releaseId());
        assertEquals(12, revised.artifacts().size());
        assertEquals(12, original.artifacts().size());
        requireDownloadWithoutLauncherGuard(revised, inventory, verifier, pair.getPrivate());

        Path batch = Files.createDirectory(temporary.resolve("batch"));
        Path downloads = Files.createDirectory(batch.resolve("downloads"));
        Files.copy(temporary.resolve("release-r2").resolve(replacement.artifact().file()), downloads.resolve(replacement
            .artifact().file()));
        var item = new InstallationPlan.Item(replacement.oldJar().getFileName().toString(), replacement.oldHash(),
            replacement.artifact().file(), replacement.artifact().sha256(), replacement.artifact().size());
        var installation = new InstallationPlan(UUID.randomUUID().toString(), plan.releaseId(), "26.2", mods, List.of(
            item));
        var journal = new UpdateJournal(batch, installation);
        journal.create();
        try (var installer = new UpdateInstaller(UpdateJournal.read(batch))) {
            installer.install();
            assertFalse(Files.exists(replacement.oldJar()));
            FileIntegrity.require(mods.resolve(item.newFile()), item.newHash());
            assertEquals(VERSION, JarMetadata.read(mods.resolve(item.newFile())).version());
            var updated = InstalledAddonInventory.scan(mods, MODULES.stream().map(AddonModule::modId).toList());
            assertTrue(UpdatePlanner.plan(revised, updated, "26.2", "0.19.5", 25).replacements().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> UpdatePlanner.plan(original, updated, "26.2", "0.19.5",
                25));
            installer.recover();
            FileIntegrity.require(replacement.oldJar(), replacement.oldHash());
            assertFalse(Files.exists(mods.resolve(item.newFile())));
        }
    }

    private void requireDownloadWithoutLauncherGuard(ReleaseManifest manifest, InstalledAddonInventory inventory, ManifestVerifier verifier, PrivateKey key)
        throws Exception {
        Path config = Files.createDirectory(temporary.resolve("unguarded-config"));
        Path cache = config.resolve("release-catalog.json");
        var gson = new Gson();
        String body = gson.toJson(manifest);
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(body.getBytes(StandardCharsets.UTF_8));
        var envelope = new ManifestVerifier.Envelope(body, "test", Base64.getEncoder().encodeToString(signer.sign()));
        Files.writeString(cache, gson.toJson(Map.of("schemaVersion", 1, "minecraft", "26.2", "state", "available",
            "checkedAt", System.currentTimeMillis(), "envelope", envelope)));
        var requested = new CountDownLatch(1);
        var http = new ReleaseHttpClient(request -> {
            requested.countDown();
            throw new java.io.IOException("Fresh release approval unavailable");
        });
        var catalog = new ReleaseCatalogClient(http, verifier, "26.2", cache);
        var environment = new UpdateEnvironment("26.2", "0.19.5", config.resolve("updates"), Set.of("core"));
        try (var coordinator = new UpdateCoordinator(http, catalog, new NotificationStateStore(config.resolve(
            "notifications.json")), () -> inventory, environment, () -> {
                fail("An unapproved release must not start the installer");
                return new byte[0];
            })) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!coordinator.canDownload() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(coordinator.canDownload());
            coordinator.download();
            assertTrue(requested.await(5, TimeUnit.SECONDS));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (coordinator.snapshot().install() != UpdateCoordinator.InstallState.FAILED && System
                .nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(UpdateCoordinator.InstallState.FAILED, coordinator.snapshot().install());
            assertEquals("Fresh release approval unavailable", coordinator.snapshot().detail());
            for (var jar : inventory.jars()) {
                FileIntegrity.require(jar.path(), jar.sha256());
            }
        }
    }

    private ReleaseManifest prepare(ManifestVerifier verifier, int revision) throws Exception {
        Path output = temporary.resolve("release-r" + revision);
        ReleaseTool.main(new String[] { "manifest", VERSION, "a".repeat(40), temporary.resolve("private.key")
            .toString(), "test", temporary.resolve("artifacts").toString(), output.toString(), temporary.resolve(
                "notes.json").toString(), temporary.resolve("keys.json").toString(), temporary.resolve("modules.json")
                    .toString(), Integer.toString(revision) });
        var signature = JsonParser.parseString(Files.readString(output.resolve("release-manifest.sig")))
            .getAsJsonObject();
        return verifier.verify(new ManifestVerifier.Envelope(Files.readString(output.resolve("release-manifest.json")),
            signature.get("keyId").getAsString(), signature.get("signature").getAsString()));
    }

    private static void writeJar(Path directory, AddonModule module, String minecraft, String content)
        throws Exception {
        var requires = new LinkedHashMap<String, String>();
        requires.put("fabricloader", ">=0.19.5");
        requires.put("minecraft", "~" + minecraft);
        requires.put("java", ">=" + (minecraft.equals("1.21.11") ? 21 : 25));
        requires.put("fabric-api", "*");
        if (module != AddonModule.CORE) {
            requires.put("catbud_core", ">=" + VERSION);
        }
        var metadata = Map.of("schemaVersion", 1, "id", module.modId(), "version", VERSION, "depends", requires);
        Path file = directory.resolve(new ReleaseRevision(VERSION, 1).file(module.id(), minecraft));
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(new Gson().toJson(metadata).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content.txt"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
