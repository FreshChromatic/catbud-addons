package github.freshchromatic.catbud_addons.update;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;

public final class UpdatePlanner {

    private UpdatePlanner() {
    }

    public static UpdatePlan plan(ReleaseManifest manifest, InstalledAddonInventory inventory, String minecraft, String loaderVersion, int javaVersion) {
        ManifestVerifier.validate(manifest);
        if (!inventory.problems().isEmpty() || inventory.jars().isEmpty()) {
            throw new IllegalArgumentException("Manual update required: " + String.join("; ", inventory.problems()));
        }
        var artifacts = new HashMap<String, ReleaseManifest.Artifact>();
        for (var artifact : manifest.artifacts()) {
            if (artifact.minecraft().equals(minecraft)) {
                artifacts.put(artifact.modId(), artifact);
            }
        }
        var replacements = new ArrayList<UpdatePlan.Replacement>();
        var futureVersions = new HashMap<String, String>();
        for (var jar : inventory.jars()) {
            var artifact = artifacts.get(jar.metadata().modId());
            if (artifact == null) {
                boolean publishedModule = manifest.artifacts().stream().anyMatch(candidate -> candidate.modId().equals(
                    jar.metadata().modId()));
                if (publishedModule) {
                    throw new IllegalArgumentException("No compatible artifact for " + jar.metadata().modId());
                }
                for (var provided : jar.metadata().provides()) {
                    futureVersions.putIfAbsent(provided.modId(), provided.version());
                }
                continue;
            }
            int comparison = ReleaseVersion.parse(artifact.version()).compareTo(ReleaseVersion.parse(jar.metadata()
                .version()));
            if (comparison < 0) {
                throw new IllegalArgumentException("Installed addon is newer than the recommended release");
            }
            if (javaVersion < artifact.javaMinimum() || ReleaseVersion.parse(loaderVersion).compareTo(ReleaseVersion
                .parse(artifact.loaderRange().substring(2))) < 0) {
                throw new IllegalArgumentException("Minecraft runtime does not meet the release requirements");
            }
            for (var provided : artifact.provides()) {
                futureVersions.put(provided.modId(), provided.version());
            }
            if (comparison == 0) {
                if (jar.sha256().equals(artifact.sha256())) {
                    continue;
                }
                var installed = ReleaseRevision.installed(jar.metadata().version(), artifact.module(), minecraft, jar
                    .path().getFileName().toString());
                if (artifact.releaseRevision().revision() <= installed.revision()) {
                    throw new IllegalArgumentException(
                        "A newer release revision is required for changed addon content");
                }
            }
            var target = inventory.mods().resolve(artifact.file());
            if (!jar.path().getParent().equals(inventory.mods()) || Files.exists(target)) {
                throw new IllegalArgumentException("Conflicting or unsupported install path: " + target.getFileName());
            }
            replacements.add(new UpdatePlan.Replacement(jar.path(), jar.sha256(), artifact));
        }
        if (!futureVersions.containsKey(AddonModule.CORE.modId())) {
            throw new IllegalArgumentException(
                "Manual update required: install standalone Catbud Core before using addons without embedded Core");
        }
        for (var rule : inventory.compatibility()) {
            String version = futureVersions.get(rule.modId());
            boolean matches = version != null && rule.matches().test(version);
            if ((rule.required() && !matches) || (!rule.required() && matches)) {
                throw new IllegalArgumentException("Update conflicts with installed mod dependency: " + rule.owner());
            }
        }
        var plan = new UpdatePlan(manifest.releaseId(), minecraft, inventory.mods(), replacements, manifest.notes());
        if (plan.downloadBytes() > ReleaseManifest.MAX_BATCH_BYTES) {
            throw new IllegalArgumentException("Update batch size limit exceeded");
        }
        return plan;
    }
}
