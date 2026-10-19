package github.freshchromatic.catbud_addons.update;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;

public final class ManifestVerifier {

    private final Map<String, PublicKey> keys;
    private final Gson                   gson = new Gson();

    public ManifestVerifier(Map<String, PublicKey> keys) {
        this.keys = Map.copyOf(keys);
    }

    public record Envelope(String manifest, String keyId, String signature) {}

    public static PublicKey publicKey(String base64) throws Exception {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(
            base64)));
    }

    public ReleaseManifest verify(Envelope envelope) throws Exception {
        if (envelope == null || envelope.manifest() == null || envelope.manifest().length() > 128 * 1024 || envelope
            .signature() == null || envelope.signature().length() > 128) {
            throw new IllegalArgumentException("Invalid signed manifest envelope");
        }
        PublicKey key = keys.get(envelope.keyId());
        if (key == null) {
            throw new IllegalArgumentException("Unknown release signing key");
        }
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(envelope.manifest().getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(Base64.getDecoder().decode(envelope.signature()))) {
            throw new IllegalArgumentException("Invalid release signature");
        }
        ReleaseManifest manifest = gson.fromJson(envelope.manifest(), ReleaseManifest.class);
        validate(manifest);
        return manifest;
    }

    public static void validate(ReleaseManifest manifest) {
        if (manifest == null || manifest.schemaVersion() != 1 || !ReleaseManifest.REPOSITORY.equals(manifest
            .repository()) || !"stable".equals(manifest.channel()) || !ReleaseVersion.parse(manifest.version()).stable()
            || !manifest.version().matches("[0-9]+\\.[0-9]+\\.[0-9]+") || !("v" + manifest.version()).equals(manifest
                .tag()) || !manifest.tag().equals(manifest.releaseId()) || !manifest.commit().matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Unsupported release manifest");
        }
        Instant.parse(manifest.publishedAt());
        String releaseRoot = "https://github.com/" + ReleaseManifest.REPOSITORY + "/releases/";
        if (!(releaseRoot + "tag/" + manifest.tag()).equals(manifest.releasePage()) || manifest.artifacts().isEmpty()
            || manifest.artifacts().size() > 16) {
            throw new IllegalArgumentException("Invalid release metadata");
        }
        var combinations = new HashSet<String>();
        for (var artifact : manifest.artifacts()) {
            AddonModule module = AddonModule.require(artifact.module());
            String file = "catbud-" + module.id() + "-" + artifact.minecraft() + "-" + artifact.version() + ".jar";
            if (!module.modId().equals(artifact.modId()) || !ReleaseManifest.MINECRAFT_VERSIONS.contains(artifact
                .minecraft()) || !manifest.version().equals(artifact.version()) || !file.equals(artifact.file())
                || !(module.id() + ":" + artifact.minecraft()).equals(artifact.id()) || !combinations.add(artifact.id())
                || artifact.size() < 1 || artifact.size() > ReleaseManifest.MAX_ARTIFACT_BYTES || !artifact.sha256()
                    .matches("[0-9a-f]{64}") || !(releaseRoot + "download/" + manifest.tag() + "/" + file).equals(
                        artifact.url()) || artifact.javaMinimum() != (artifact.minecraft().equals("1.21.11") ? 21 : 25)
                || !artifact.loaderRange().matches(">=[0-9]+\\.[0-9]+\\.[0-9]+")) {
                throw new IllegalArgumentException("Invalid release artifact");
            }
            var provided = new HashSet<String>();
            for (var mod : artifact.provides()) {
                if (AddonModule.forMod(mod.modId()).isEmpty() || !provided.add(mod.modId()) || !artifact.version()
                    .equals(mod.version())) {
                    throw new IllegalArgumentException("Invalid embedded addon metadata");
                }
            }
            boolean embeddedCore = module != AddonModule.CORE && provided.contains(AddonModule.CORE.modId());
            if (!provided.contains(module.modId()) || provided.size() != (embeddedCore ? 2 : 1) || !artifact
                .loaderRange().equals(artifact.requires().get("fabricloader")) || !("~" + artifact.minecraft()).equals(
                    artifact.requires().get("minecraft")) || !(">=" + artifact.javaMinimum()).equals(artifact.requires()
                        .get("java")) || !"*".equals(artifact.requires().get("fabric-api"))
                || (module != AddonModule.CORE && !(">=" + artifact.version()).equals(artifact.requires().get(
                    AddonModule.CORE.modId())))) {
                throw new IllegalArgumentException("Incomplete release dependency metadata");
            }
            int dependencyCount = module == AddonModule.CORE ? 4 : 5;
            if (artifact.requires().size() != dependencyCount) {
                throw new IllegalArgumentException("Unsupported release dependency");
            }
        }
        if (manifest.notes().size() > 4) {
            throw new IllegalArgumentException("Invalid release notes");
        }
        for (var note : manifest.notes().entrySet()) {
            AddonModule.require(note.getKey());
            if (note.getValue().length() > 4000) {
                throw new IllegalArgumentException("Invalid release notes");
            }
        }
    }
}
