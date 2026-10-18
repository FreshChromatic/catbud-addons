package github.freshchromatic.catbud_addons.update;

import github.freshchromatic.catbud_addons.update.install.FileIntegrity;
import github.freshchromatic.catbud_addons.update.install.InstallationPlan;
import github.freshchromatic.catbud_addons.update.install.UpdateJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class UpdateBatchStore {

    private final Path directory;

    public UpdateBatchStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public void cleanCancelledDownloads(Path batch, UpdatePlan plan) throws IOException {
        requireBatch(batch);
        Path downloads = batch.resolve("downloads");
        InstallationPlan.requireRealDirectory(downloads);
        var files = new ArrayList<Path>();
        for (var replacement : plan.replacements()) {
            Path path = downloads.resolve(replacement.artifact().file());
            if (Files.exists(path)) {
                FileIntegrity.require(path, replacement.artifact().sha256());
                files.add(path);
            }
        }
        requireContents(downloads, files.stream().map(path -> path.getFileName().toString()).collect(
            java.util.stream.Collectors.toSet()));
        for (Path file : files) {
            Files.delete(file);
        }
        Files.delete(downloads);
        requireContents(batch, Set.of());
        Files.delete(batch);
    }

    public void prune(long now) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        InstallationPlan.requireRealDirectory(directory);
        try (var children = Files.list(directory)) {
            var confirmed = new ArrayList<Path>();
            for (Path batch : children.filter(Files::isDirectory).toList()) {
                try {
                    requireBatch(batch);
                    Path marker = batch.resolve("confirmed-at");
                    if (Files.exists(marker) && Files.size(marker) < 32 && !Files.isSymbolicLink(marker)) {
                        confirmed.add(batch);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            confirmed.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
            long newest = 0;
            for (Path batch : confirmed) {
                newest = Math.max(newest, Long.parseLong(Files.readString(batch.resolve("confirmed-at")).strip()));
            }
            for (Path batch : confirmed) {
                long time = Long.parseLong(Files.readString(batch.resolve("confirmed-at")).strip());
                if (time < newest && time < now - 7L * 86400000) {
                    cleanSuccessful(batch);
                }
            }
        }
    }

    private void cleanSuccessful(Path batch) throws IOException {
        var journal = UpdateJournal.read(batch);
        if (!UpdateJournal.properties(batch.resolve("result.properties")).getProperty("status").equals("INSTALLED")) {
            return;
        }
        Path stage = journal.plan().mods().resolve(".catbud-update-" + journal.plan().batchId());
        InstallationPlan.requireRealDirectory(stage);
        Path downloads = batch.resolve("downloads");
        InstallationPlan.requireRealDirectory(downloads);
        var stageNames = new HashSet<String>();
        var downloadNames = new HashSet<String>();
        for (var item : journal.plan().items()) {
            String name = item.oldFile() + ".backup";
            stageNames.add(name);
            downloadNames.add(item.newFile());
            FileIntegrity.require(stage.resolve(name), item.oldHash());
            FileIntegrity.require(downloads.resolve(item.newFile()), item.newHash());
        }
        requireContents(stage, stageNames);
        requireContents(downloads, downloadNames);
        requireContents(batch, Set.of("plan.properties", "result.properties", "installer.jar", "RECOVERY.txt",
            "helper.log", "ready", "confirmed-at", "downloads"));
        for (String name : stageNames) {
            Files.delete(stage.resolve(name));
        }
        Files.delete(stage);
        for (String name : downloadNames) {
            Files.delete(downloads.resolve(name));
        }
        Files.delete(downloads);
        try (var files = Files.list(batch)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(batch);
    }

    private void requireBatch(Path batch) throws IOException {
        if (!batch.toAbsolutePath().normalize().getParent().equals(directory)) {
            throw new IOException("Update batch is outside the owned directory");
        }
        UUID.fromString(batch.getFileName().toString());
        InstallationPlan.requireRealDirectory(batch);
    }

    private static void requireContents(Path directory, Set<String> names) throws IOException {
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                if (Files.isSymbolicLink(entry) || !names.contains(entry.getFileName().toString())) {
                    throw new IOException("Update directory contains unrecognized files; automatic cleanup skipped");
                }
            }
        }
    }
}
