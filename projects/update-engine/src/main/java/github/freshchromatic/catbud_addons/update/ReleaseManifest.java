package github.freshchromatic.catbud_addons.update;

import java.util.List;
import java.util.Map;

public record ReleaseManifest(int schemaVersion, String releaseId, String version, String channel, String publishedAt,
    String repository, String tag, String releasePage, String commit, List<Artifact> artifacts,
    Map<String, String> notes) {

    public static final String REPOSITORY = "FreshChromatic/catbud-addons";
    public static final List<String> MINECRAFT_VERSIONS = List.of("1.21.11", "26.1.2", "26.2", "26.3");
    public static final long MAX_ARTIFACT_BYTES = 64L * 1024 * 1024;
    public static final long MAX_BATCH_BYTES = 256L * 1024 * 1024;

    public ReleaseManifest {
        artifacts = List.copyOf(artifacts);
        notes = Map.copyOf(notes);
    }

    public record ProvidedMod(String modId, String version) {}

    public record Artifact(String id, String module, String modId, String minecraft, String loaderRange,
        int javaMinimum, String version, String file, String url, long size, String sha256, List<ProvidedMod> provides,
        Map<String, String> requires) {

        public Artifact {
            provides = List.copyOf(provides);
            requires = Map.copyOf(requires);
        }
    }
}
