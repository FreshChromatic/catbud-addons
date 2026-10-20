package github.freshchromatic.catbud_addons.update;

import com.google.gson.Gson;
import github.freshchromatic.catbud_addons.update.install.FileIntegrity;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class UpdatePlannerTest {

    @TempDir Path temporary;

    @Test
    void plansChangedFilesForANewerRevisionAndSkipsIdenticalFiles() throws Exception {
        Path mods = Files.createDirectory(temporary.resolve("revision-mods"));
        var original = manifest("1.0.4", false);
        for (var artifact : original.artifacts()) {
            Files.copy(temporary.resolve("assets-1.0.4-standalone").resolve(artifact.file()), mods.resolve(artifact
                .file()));
        }
        var inventory = InstalledAddonInventory.scan(mods, List.of("catbud_core", "catbud_addons",
            "catbud_public_warps", "catbud_monster_wave"));
        var revised = revision(original, 2);
        var plan = UpdatePlanner.plan(revised, inventory, "26.2", "0.19.5", 25);
        assertEquals("v1.0.4-r2", plan.releaseId());
        assertEquals(1, plan.replacements().size());
        assertEquals("catbud-core-26.2-1.0.4-r2.jar", plan.replacements().getFirst().artifact().file());
        assertTrue(UpdatePlanner.plan(original, inventory, "26.2", "0.19.5", 25).replacements().isEmpty());

        Path core = mods.resolve("catbud-core-26.2-1.0.4.jar");
        Files.move(core, mods.resolve("catbud-core-26.2-1.0.4-r3.jar"));
        var newer = InstalledAddonInventory.scan(mods, List.of("catbud_core", "catbud_addons", "catbud_public_warps",
            "catbud_monster_wave"));
        assertThrows(IllegalArgumentException.class, () -> UpdatePlanner.plan(revised, newer, "26.2", "0.19.5", 25));
        assertThrows(IllegalArgumentException.class, () -> UpdatePlanner.plan(revision(original, 3), newer, "26.2",
            "0.19.5", 25));
        assertEquals(1, UpdatePlanner.plan(revision(original, 4), newer, "26.2", "0.19.5", 25).replacements().size());
    }

    @Test
    void revisionMetadataCannotReuseOldFilenamesOrDownloadUrls() throws Exception {
        var revised = revision(manifest("1.0.4", false), 2);
        ManifestVerifier.validate(revised);
        var invalid = new Gson().toJsonTree(revised).getAsJsonObject();
        invalid.getAsJsonArray("artifacts").get(0).getAsJsonObject().addProperty("file", "catbud-core-26.2-1.0.4.jar");
        var wrongFile = new Gson().fromJson(invalid, ReleaseManifest.class);
        assertThrows(IllegalArgumentException.class, () -> ManifestVerifier.validate(wrongFile));
        invalid = new Gson().toJsonTree(revised).getAsJsonObject();
        invalid.addProperty("tag", "v1.0.5-r2");
        var wrongVersion = new Gson().fromJson(invalid, ReleaseManifest.class);
        assertThrows(IllegalArgumentException.class, () -> ManifestVerifier.validate(wrongVersion));
    }

    private static ReleaseManifest revision(ReleaseManifest original, int number) {
        var revision = new ReleaseRevision(original.version(), number);
        var artifacts = original.artifacts().stream().map(artifact -> {
            String file = revision.file(artifact.module(), artifact.minecraft());
            return new ReleaseManifest.Artifact(artifact.id(), artifact.module(), artifact.modId(), artifact
                .minecraft(), artifact.loaderRange(), artifact.javaMinimum(), artifact.version(), file,
                "https://github.com/FreshChromatic/catbud-addons/releases/download/" + revision.tag() + "/" + file,
                artifact.size(), artifact.module().equals("core") ? "b".repeat(64) : artifact.sha256(), artifact
                    .provides(), artifact.requires());
        }).toList();
        return new ReleaseManifest(1, revision.tag(), original.version(), "stable", original.publishedAt(), original
            .repository(), revision.tag(), "https://github.com/FreshChromatic/catbud-addons/releases/tag/" + revision
                .tag(), original.commit(), artifacts, original.notes());
    }

    @Test
    void plansEachSupportedInstallationTopologyWithoutAddingModules() throws Exception {
        var combinations = List.of(List.of(AddonModule.CORE), List.of(AddonModule.MAGIC_TOWER), List.of(
            AddonModule.MAGIC_TOWER, AddonModule.PUBLIC_WARPS), List.of(AddonModule.CORE, AddonModule.MAGIC_TOWER,
                AddonModule.PUBLIC_WARPS, AddonModule.MONSTER_WAVE));
        int index = 0;
        for (var modules : combinations) {
            Path mods = Files.createDirectory(temporary.resolve("mods" + index++));
            var loaded = new ArrayList<String>();
            loaded.add("catbud_core");
            for (var module : modules) {
                jar(mods, module, "1.0.3");
                if (module != AddonModule.CORE) {
                    loaded.add(module.modId());
                }
            }
            var inventory = InstalledAddonInventory.scan(mods, loaded);
            assertEquals(List.of(), inventory.problems());
            var plan = UpdatePlanner.plan(manifest("1.0.4"), inventory, "26.2", "0.19.5", 25);
            assertEquals(modules.size(), plan.replacements().size());
            assertEquals(modules.stream().map(AddonModule::id).sorted().toList(), plan.replacements().stream().map(
                item -> item.artifact().module()).sorted().toList());
        }
    }

    @Test
    void neverDowngradesAndRejectsMissingArtifactsOrWrongRuntime() throws Exception {
        Path mods = Files.createDirectory(temporary.resolve("mods"));
        jar(mods, AddonModule.MAGIC_TOWER, "1.0.3");
        var inventory = InstalledAddonInventory.scan(mods, List.of("catbud_core", "catbud_addons"));
        assertThrows(IllegalArgumentException.class, () -> UpdatePlanner.plan(manifest("1.0.1"), inventory, "26.2",
            "0.19.5", 25));
        assertThrows(IllegalArgumentException.class, () -> UpdatePlanner.plan(manifest("1.0.4"), inventory, "26.3",
            "0.19.5", 25));
        assertThrows(IllegalArgumentException.class, () -> UpdatePlanner.plan(manifest("1.0.4"), inventory, "26.2",
            "0.19.4", 25));
        assertThrows(IllegalArgumentException.class, () -> UpdatePlanner.plan(manifest("1.0.4"), inventory, "26.2",
            "0.19.5", 21));
    }

    @Test
    void blocksDuplicateAndUnrecognizedLoadedOrigins() throws Exception {
        Path mods = Files.createDirectory(temporary.resolve("duplicates"));
        Path original = jar(mods, AddonModule.MAGIC_TOWER, "1.0.3");
        Files.copy(original, mods.resolve("renamed-copy.jar"));
        var duplicate = InstalledAddonInventory.scan(mods, List.of("catbud_core", "catbud_addons"));
        assertFalse(duplicate.problems().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> UpdatePlanner.plan(manifest("1.0.4"), duplicate, "26.2",
            "0.19.5", 25));
        var unknown = InstalledAddonInventory.scan(mods, List.of("catbud_core", "catbud_addons",
            "catbud_public_warps"));
        assertTrue(unknown.problems().stream().anyMatch(problem -> problem.contains("origins")));
    }

    @Test
    void verifiesRawSignedBytesAndRejectsTamperedMetadataAndWrongKeys() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String raw = new Gson().toJson(manifest("1.0.4"));
        var signature = Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(raw.getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.getEncoder().encodeToString(signature.sign());
        var verifier = new ManifestVerifier(Map.of("test", pair.getPublic()));
        assertEquals("1.0.4", verifier.verify(new ManifestVerifier.Envelope(raw, "test", encoded)).version());
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(new ManifestVerifier.Envelope(raw + " ",
            "test", encoded)));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(new ManifestVerifier.Envelope(raw, "unknown",
            encoded)));
        var invalid = new Gson().toJsonTree(manifest("1.0.4")).getAsJsonObject();
        invalid.getAsJsonArray("artifacts").get(0).getAsJsonObject().addProperty("url", "https://example.com/mod.jar");
        assertThrows(IllegalArgumentException.class, () -> ManifestVerifier.validate(new Gson().fromJson(invalid,
            ReleaseManifest.class)));
    }

    @Test
    void honorsOtherInstalledModsDependencyConstraints() throws Exception {
        Path mods = Files.createDirectory(temporary.resolve("external-dependency"));
        jar(mods, AddonModule.CORE, "1.0.3");
        var scanned = InstalledAddonInventory.scan(mods, List.of("catbud_core"));
        var incompatible = new InstalledAddonInventory(scanned.mods(), scanned.jars(), scanned.problems(), List.of(
            new InstalledAddonInventory.CompatibilityRule("third_party", "catbud_core", true, candidate -> candidate
                .equals("1.0.3"))));
        assertThrows(IllegalArgumentException.class, () -> UpdatePlanner.plan(manifest("1.0.4"), incompatible, "26.2",
            "0.19.5", 25));
        var compatible = new InstalledAddonInventory(scanned.mods(), scanned.jars(), scanned.problems(), List.of(
            new InstalledAddonInventory.CompatibilityRule("third_party", "catbud_core", true, candidate -> candidate
                .startsWith("1.0."))));
        assertEquals(1, UpdatePlanner.plan(manifest("1.0.4"), compatible, "26.2", "0.19.5", 25).replacements().size());
    }

    @Test
    void updatesStandaloneCoreAndThinAddonsTogether() throws Exception {
        Path mods = Files.createDirectory(temporary.resolve("standalone-core"));
        for (var module : List.of(AddonModule.CORE, AddonModule.MAGIC_TOWER, AddonModule.PUBLIC_WARPS)) {
            jar(mods, module, "1.0.3", false);
        }
        var inventory = InstalledAddonInventory.scan(mods, List.of("catbud_core", "catbud_addons",
            "catbud_public_warps"));
        assertTrue(inventory.problems().isEmpty());
        var plan = UpdatePlanner.plan(manifest("1.0.4", false), inventory, "26.2", "0.19.5", 25);
        assertEquals(3, plan.replacements().size());
        assertTrue(plan.replacements().stream().allMatch(item -> item.artifact().provides().size() == 1));
    }

    @Test
    void removesEmbeddedCopiesOnlyWhenAStandaloneCoreIsAlreadyInstalled() throws Exception {
        Path mods = Files.createDirectory(temporary.resolve("embedded-to-standalone"));
        Path magic = jar(mods, AddonModule.MAGIC_TOWER, "1.0.3");
        var embedded = InstalledAddonInventory.scan(mods, List.of("catbud_core", "catbud_addons"));
        var release = manifest("1.0.4", false);
        String hash = FileIntegrity.sha256(magic);
        var error = assertThrows(IllegalArgumentException.class, () -> UpdatePlanner.plan(release, embedded, "26.2",
            "0.19.5", 25));
        assertTrue(error.getMessage().contains("install standalone Catbud Core"));
        assertEquals(hash, FileIntegrity.sha256(magic));
        jar(mods, AddonModule.CORE, "1.0.3", false);
        var standalone = InstalledAddonInventory.scan(mods, List.of("catbud_core", "catbud_addons"));
        var plan = UpdatePlanner.plan(release, standalone, "26.2", "0.19.5", 25);
        assertEquals(2, plan.replacements().size());
        assertTrue(plan.replacements().stream().allMatch(item -> item.artifact().provides().size() == 1));
    }

    @Test
    void thinAddonsStillRequireCoreAndCannotClaimOtherAddonIds() throws Exception {
        var manifest = manifest("1.0.4", false);
        ManifestVerifier.validate(manifest);
        var invalid = new Gson().toJsonTree(manifest).getAsJsonObject();
        var magic = invalid.getAsJsonArray("artifacts").get(1).getAsJsonObject();
        magic.getAsJsonObject("requires").remove("catbud_core");
        assertThrows(IllegalArgumentException.class, () -> ManifestVerifier.validate(new Gson().fromJson(invalid,
            ReleaseManifest.class)));
        var forged = new Gson().toJsonTree(manifest).getAsJsonObject();
        forged.getAsJsonArray("artifacts").get(1).getAsJsonObject().getAsJsonArray("provides").add(new Gson()
            .toJsonTree(new ReleaseManifest.ProvidedMod("catbud_public_warps", "1.0.4")));
        assertThrows(IllegalArgumentException.class, () -> ManifestVerifier.validate(new Gson().fromJson(forged,
            ReleaseManifest.class)));
    }

    private ReleaseManifest manifest(String version) throws Exception {
        return manifest(version, true);
    }

    private ReleaseManifest manifest(String version, boolean embeddedCore) throws Exception {
        Path assets = temporary.resolve("assets-" + version + (embeddedCore ? "-embedded" : "-standalone"));
        Files.createDirectories(assets);
        var artifacts = new ArrayList<ReleaseManifest.Artifact>();
        for (AddonModule module : AddonModule.values()) {
            Path jar = jar(assets, module, version, embeddedCore);
            var metadata = JarMetadata.read(jar);
            String file = jar.getFileName().toString();
            artifacts.add(new ReleaseManifest.Artifact(module.id() + ":26.2", module.id(), module.modId(), "26.2",
                ">=0.19.5", 25, version, file, "https://github.com/FreshChromatic/catbud-addons/releases/download/v"
                    + version + "/" + file, Files.size(jar), FileIntegrity.sha256(jar), metadata.provides(), metadata
                        .requires()));
        }
        return new ReleaseManifest(1, "v" + version, version, "stable", "2026-10-18T00:00:00Z",
            ReleaseManifest.REPOSITORY, "v" + version, "https://github.com/FreshChromatic/catbud-addons/releases/tag/v"
                + version, "a".repeat(40), artifacts, Map.of("core", "Test release"));
    }

    private static Path jar(Path directory, AddonModule module, String version) throws Exception {
        return jar(directory, module, version, true);
    }

    private static Path jar(Path directory, AddonModule module, String version, boolean embeddedCore) throws Exception {
        Path file = directory.resolve("catbud-" + module.id() + "-26.2-" + version + ".jar");
        Files.write(file, archive(module, version, embeddedCore));
        return file;
    }

    private static byte[] archive(AddonModule module, String version, boolean embeddedCore) throws Exception {
        var requires = new LinkedHashMap<String, String>();
        requires.put("fabricloader", ">=0.19.5");
        requires.put("minecraft", "~26.2");
        requires.put("java", ">=25");
        requires.put("fabric-api", "*");
        if (module != AddonModule.CORE) {
            requires.put("catbud_core", ">=" + version);
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("schemaVersion", 1);
        metadata.put("id", module.modId());
        metadata.put("version", version);
        metadata.put("depends", requires);
        if (module != AddonModule.CORE && embeddedCore) {
            metadata.put("jars", List.of(Map.of("file", "META-INF/jars/core.jar")));
        }
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(new Gson().toJson(metadata).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (module != AddonModule.CORE && embeddedCore) {
                zip.putNextEntry(new ZipEntry("META-INF/jars/core.jar"));
                zip.write(archive(AddonModule.CORE, version, false));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
