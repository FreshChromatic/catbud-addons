package github.freshchromatic.catbud_addons.update;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

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
        var futureMods = new HashSet<String>();
        for (var jar : inventory.jars()) {
            var artifact = artifacts.get(jar.metadata().modId());
            if (artifact == null) {
                throw new IllegalArgumentException("No compatible artifact for " + jar.metadata().modId());
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
                futureMods.add(provided.modId());
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
        if (!futureMods.contains(AddonModule.CORE.modId())) {
            throw new IllegalArgumentException(
                "Manual update required: install standalone Catbud Core before using addons without embedded Core");
        }
        for (var rule : inventory.compatibility()) {
            boolean present = futureMods.contains(rule.modId());
            boolean matches = present && rule.matches().test(manifest.version());
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
