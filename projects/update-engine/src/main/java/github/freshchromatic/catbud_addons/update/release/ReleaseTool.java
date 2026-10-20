package github.freshchromatic.catbud_addons.update.release;

import com.google.gson.GsonBuilder;
import github.freshchromatic.catbud_addons.update.AddonModule;
import github.freshchromatic.catbud_addons.update.JarMetadata;
import github.freshchromatic.catbud_addons.update.ManifestVerifier;
import github.freshchromatic.catbud_addons.update.ReleaseManifest;
import github.freshchromatic.catbud_addons.update.ReleaseRevision;
import github.freshchromatic.catbud_addons.update.ReleaseVersion;
import github.freshchromatic.catbud_addons.update.install.FileIntegrity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReleaseTool {

    private ReleaseTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("keygen")) {
            generateKey(Path.of(args[1]), args[2]);
            return;
        }
        if ((args.length < 9 || args.length > 11) || !args[0].equals("manifest")) {
            throw new IllegalArgumentException(
                "Expected keygen directory keyId or manifest version commit privateKey keyId artifactDir outputDir notesJson publicKeysJson [modulesJson [revision]]");
        }
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String version = args[1];
        if (!ReleaseVersion.parse(version).stable()) {
            throw new IllegalArgumentException("Only stable releases are supported");
        }
        var revision = new ReleaseRevision(version, args.length == 11 ? Integer.parseInt(args[10]) : 1);
        Path artifacts = Path.of(args[5]);
        Path output = Path.of(args[6]);
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Release output directory must be new");
        }
        var entries = new ArrayList<ReleaseManifest.Artifact>();
        List<AddonModule> modules = args.length >= 10 ? modules(Path.of(args[9])) : List.of(AddonModule.values());
        for (String minecraft : ReleaseManifest.MINECRAFT_VERSIONS) {
            for (AddonModule module : modules) {
                String file = revision.file(module.id(), minecraft);
                Path jar = artifacts.resolve(new ReleaseRevision(version, 1).file(module.id(), minecraft));
                JarMetadata metadata = JarMetadata.read(jar);
                entries.add(new ReleaseManifest.Artifact(module.id() + ":" + minecraft, module.id(), metadata.modId(),
                    minecraft, metadata.requires().get("fabricloader"), minecraft.equals("1.21.11") ? 21 : 25, metadata
                        .version(), file, "https://github.com/" + ReleaseManifest.REPOSITORY + "/releases/download/"
                            + revision.tag() + "/" + file, Files.size(jar), FileIntegrity.sha256(jar), metadata
                                .provides(), metadata.requires()));
            }
        }
        var notesJson = com.google.gson.JsonParser.parseString(Files.readString(Path.of(args[7]))).getAsJsonObject();
        Map<String, String> notes = new LinkedHashMap<>();
        for (var note : notesJson.entrySet()) {
            if (modules.contains(AddonModule.require(note.getKey()))) {
                notes.put(note.getKey(), note.getValue().getAsString());
            }
        }
        var manifest = new ReleaseManifest(1, revision.tag(), version, "stable", Instant.now().toString(),
            ReleaseManifest.REPOSITORY, revision.tag(), "https://github.com/" + ReleaseManifest.REPOSITORY
                + "/releases/tag/" + revision.tag(), args[2], entries, notes);
        ManifestVerifier.validate(manifest);
        byte[] raw = (gson.toJson(manifest) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] encodedKey = Base64.getDecoder().decode(Files.readString(Path.of(args[3])).strip());
        var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(raw);
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        var publicKeys = new LinkedHashMap<String, java.security.PublicKey>();
        for (var entry : com.google.gson.JsonParser.parseString(Files.readString(Path.of(args[8]))).getAsJsonObject()
            .entrySet()) {
            publicKeys.put(entry.getKey(), ManifestVerifier.publicKey(entry.getValue().getAsString()));
        }
        new ManifestVerifier(publicKeys).verify(new ManifestVerifier.Envelope(new String(raw, StandardCharsets.UTF_8),
            args[4], signature));
        var signatureFile = Map.of("keyId", args[4], "signature", signature);
        Files.createDirectory(output);
        Files.write(output.resolve("release-manifest.json"), raw, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("release-manifest.sig"), gson.toJson(signatureFile) + "\n",
            StandardOpenOption.CREATE_NEW);
        for (var artifact : entries) {
            String sourceFile = new ReleaseRevision(version, 1).file(artifact.module(), artifact.minecraft());
            Files.copy(artifacts.resolve(sourceFile), output.resolve(artifact.file()));
        }
        System.out.println("Prepared " + entries.size() + " verified release artifacts in " + output.toAbsolutePath());
    }

    private static List<AddonModule> modules(Path file) throws Exception {
        var modules = new ArrayList<AddonModule>();
        for (var value : com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
            AddonModule module = AddonModule.require(value.getAsString());
            if (modules.contains(module)) {
                throw new IllegalArgumentException("Duplicate release module: " + module.id());
            }
            modules.add(module);
        }
        if (!modules.containsAll(List.of(AddonModule.CORE, AddonModule.MAGIC_TOWER, AddonModule.PUBLIC_WARPS))) {
            throw new IllegalArgumentException("A release requires Core, Magic Tower and Public Warps");
        }
        return List.copyOf(modules);
    }

    private static void generateKey(Path directory, String keyId) throws Exception {
        if (Files.exists(directory) || !keyId.matches("[a-z0-9-]{1,40}")) {
            throw new IllegalArgumentException("Key directory must be new and keyId must be a simple identifier");
        }
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var gson = new GsonBuilder().setPrettyPrinting().create();
        Files.createDirectory(directory);
        Files.writeString(directory.resolve("private-key.pkcs8"), Base64.getEncoder().encodeToString(pair.getPrivate()
            .getEncoded()), StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve("release-keys.json"), gson.toJson(Map.of(keyId, Base64.getEncoder()
            .encodeToString(pair.getPublic().getEncoded()))) + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println(
            "Created signing key files. Keep private-key.pkcs8 outside the repository and protect its permissions.");
    }
}
