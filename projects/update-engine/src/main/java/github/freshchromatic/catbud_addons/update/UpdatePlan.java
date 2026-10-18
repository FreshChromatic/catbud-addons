package github.freshchromatic.catbud_addons.update;

import java.nio.file.Path;
import java.util.List;

public record UpdatePlan(String releaseId, String minecraft, Path mods, List<Replacement> replacements,
    java.util.Map<String, String> notes) {

    public UpdatePlan {
        replacements = List.copyOf(replacements);
        notes = java.util.Map.copyOf(notes);
    }

    public record Replacement(Path oldJar, String oldHash, ReleaseManifest.Artifact artifact) {}

    public long downloadBytes() {
        return replacements.stream().mapToLong(replacement -> replacement.artifact().size()).sum();
    }
}
