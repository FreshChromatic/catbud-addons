package github.freshchromatic.catbud_addons.update.install;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class UpdateInstaller implements AutoCloseable {

    @FunctionalInterface
    public interface Checkpoint {

        void reached(String step, int index) throws IOException;
    }

    private final UpdateJournal journal;
    private final FileChannel   channel;
    private final FileLock      lock;
    private final Checkpoint    checkpoint;

    public UpdateInstaller(UpdateJournal journal) throws IOException {
        this(journal, (step, index) -> {
        });
    }

    public UpdateInstaller(UpdateJournal journal, Checkpoint checkpoint) throws IOException {
        this.journal = journal;
        this.checkpoint = checkpoint;
        Path lockPath = journal.plan().mods().resolve(".catbud-update.lock");
        if (Files.isSymbolicLink(lockPath)) {
            throw new IOException("Invalid installation lock");
        }
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
        } catch (OverlappingFileLockException error) {
            channel.close();
            throw new IOException("Another installer is running", error);
        }
        if (acquired == null) {
            channel.close();
            throw new IOException("Another installer is running");
        }
        lock = acquired;
    }

    public void install() throws IOException {
        preflight();
        Path stage = stageDirectory();
        if (Files.exists(stage)) {
            throw new IOException("An unfinished batch needs recovery before installation");
        }
        Files.createDirectory(stage);
        try {
            stageDownloads(stage);
            checkpoint.reached("staged", -1);
            journal.result("INSTALLING", "");
            for (int i = 0; i < journal.plan().items().size(); i++) {
                var item = journal.plan().items().get(i);
                FileIntegrity.require(journal.plan().mods().resolve(item.oldFile()), item.oldHash());
                moveWithRetry(journal.plan().mods().resolve(item.oldFile()), backup(stage, item));
                checkpoint.reached("backed-up", i);
                journal.result("INSTALLING", "Backed up " + item.oldFile());
            }
            for (int i = 0; i < journal.plan().items().size(); i++) {
                var item = journal.plan().items().get(i);
                moveWithRetry(stage.resolve(item.newFile() + ".part"), journal.plan().mods().resolve(item.newFile()));
                checkpoint.reached("installed", i);
                journal.result("INSTALLING", "Installed " + item.newFile());
            }
            for (var item : journal.plan().items()) {
                FileIntegrity.require(journal.plan().mods().resolve(item.newFile()), item.newHash());
            }
            journal.result("INSTALLED", "Awaiting verification on the next game launch");
        } catch (IOException | RuntimeException error) {
            try {
                recover();
                journal.result("ROLLED_BACK", error.getMessage() == null ? "Installation failed" : error.getMessage());
            } catch (IOException recovery) {
                journal.result("REPAIR_REQUIRED", recovery.getMessage());
                error.addSuppressed(recovery);
            }
            throw error;
        }
    }

    public void recover() throws IOException {
        Path stage = stageDirectory();
        InstallationPlan.requireRealDirectory(journal.plan().mods());
        if (Files.exists(stage)) {
            InstallationPlan.requireRealDirectory(stage);
        }
        // Inspect every file before deleting or moving anything, including after a process crash.
        for (var item : journal.plan().items()) {
            Path old = journal.plan().mods().resolve(item.oldFile());
            Path saved = backup(stage, item);
            Path installed = journal.plan().mods().resolve(item.newFile());
            if (Files.exists(saved)) {
                FileIntegrity.require(saved, item.oldHash());
                if (Files.exists(old)) {
                    throw new IOException("Old file conflicts with recovery: " + item.oldFile());
                }
            } else {
                FileIntegrity.require(old, item.oldHash());
            }
            if (Files.exists(installed)) {
                FileIntegrity.require(installed, item.newHash());
            }
        }
        for (var item : journal.plan().items()) {
            Path installed = journal.plan().mods().resolve(item.newFile());
            if (Files.exists(installed)) {
                Files.delete(installed);
            }
        }
        for (var item : journal.plan().items()) {
            Path saved = backup(stage, item);
            if (Files.exists(saved)) {
                moveWithRetry(saved, journal.plan().mods().resolve(item.oldFile()));
            }
        }
        journal.result("ROLLED_BACK", "Original addon files restored");
    }

    private void preflight() throws IOException {
        InstallationPlan.requireRealDirectory(journal.plan().mods());
        InstallationPlan.requireRealDirectory(journal.directory().resolve("downloads"));
        if (journal.cancelled()) {
            throw new IOException("Installation cancelled");
        }
        long total = 0;
        for (var item : journal.plan().items()) {
            Path old = journal.plan().mods().resolve(item.oldFile());
            Path downloaded = journal.directory().resolve("downloads").resolve(item.newFile());
            FileIntegrity.require(old, item.oldHash());
            FileIntegrity.require(downloaded, item.newHash());
            if (Files.exists(journal.plan().mods().resolve(item.newFile())) || Files.size(downloaded) != item.size()) {
                throw new IOException("Installation target changed");
            }
            total += item.size();
        }
        if (total > 256L * 1024 * 1024 || Files.getFileStore(journal.plan().mods()).getUsableSpace() < total + 32L
            * 1024 * 1024) {
            throw new IOException("Not enough installation disk space");
        }
    }

    private void stageDownloads(Path stage) throws IOException {
        for (var item : journal.plan().items()) {
            Path staged = stage.resolve(item.newFile() + ".part");
            Files.copy(journal.directory().resolve("downloads").resolve(item.newFile()), staged);
            UpdateJournal.force(staged);
            FileIntegrity.require(staged, item.newHash());
        }
    }

    private Path stageDirectory() {
        return journal.plan().mods().resolve(".catbud-update-" + journal.plan().batchId());
    }

    private static Path backup(Path stage, InstallationPlan.Item item) {
        return stage.resolve(item.oldFile() + ".backup");
    }

    private static void moveWithRetry(Path from, Path to) throws IOException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (true) {
            try {
                UpdateJournal.move(from, to, false);
                return;
            } catch (IOException error) {
                if (Files.exists(to) || System.nanoTime() >= deadline) {
                    throw error;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Installation interrupted", interrupted);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
