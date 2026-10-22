package github.freshchromatic.catbud_addons.update;

import github.freshchromatic.catbud_addons.update.install.FileIntegrity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public record InstalledAddonInventory(Path mods, List<InstalledJar> jars, List<String> problems,
    List<CompatibilityRule> compatibility) {

    public InstalledAddonInventory {
        jars = List.copyOf(jars);
        problems = List.copyOf(problems);
        compatibility = List.copyOf(compatibility);
    }

    public InstalledAddonInventory(Path mods, List<InstalledJar> jars, List<String> problems) {
        this(mods, jars, problems, List.of());
    }

    public record CompatibilityRule(String owner, String modId, boolean required,
        java.util.function.Predicate<String> matches) {}

    public record InstalledJar(Path path, String sha256, JarMetadata metadata) {}

    public static InstalledAddonInventory scan(Path mods, List<String> loadedAddonIds) throws IOException {
        Path root = mods.toRealPath();
        if (Files.isSymbolicLink(mods) || !Files.isDirectory(root)) {
            throw new IOException("Unsupported mods directory");
        }
        var jars = new ArrayList<InstalledJar>();
        var problems = new ArrayList<String>();
        var roots = new HashSet<String>();
        var found = new HashSet<String>();
        try (var paths = Files.list(root)) {
            for (Path path : paths.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                JarMetadata metadata;
                try {
                    metadata = JarMetadata.inspectInstalled(path);
                } catch (IOException error) {
                    problems.add("Cannot safely inspect " + path.getFileName() + ": " + error.getMessage());
                    continue;
                }
                if (metadata.provides().isEmpty()) {
                    continue;
                }
                if (AddonModule.forMod(metadata.modId()).isEmpty()) {
                    problems.add("Addon is bundled inside an unsupported parent: " + path.getFileName());
                    continue;
                }
                if (!roots.add(metadata.modId())) {
                    problems.add("Duplicate top-level addon: " + metadata.modId());
                }
                for (var mod : metadata.provides()) {
                    found.add(mod.modId());
                }
                jars.add(new InstalledJar(path.toRealPath(), FileIntegrity.sha256(path), metadata));
            }
        }
        if (!found.equals(new HashSet<>(loadedAddonIds))) {
            problems.add("Loaded addon origins differ from the inspected mods directory");
        }
        return new InstalledAddonInventory(root, jars, problems);
    }
}
