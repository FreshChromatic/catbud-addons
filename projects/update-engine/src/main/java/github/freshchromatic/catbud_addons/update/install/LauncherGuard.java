package github.freshchromatic.catbud_addons.update.install;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class LauncherGuard {

    private LauncherGuard() {
    }

    public static void await(Path mods, Path updates) throws Exception {
        InstallationPlan.requireRealDirectory(mods);
        InstallationPlan.requireRealDirectory(updates);
        Path lock = mods.resolve(".catbud-update.lock");
        if (Files.isSymbolicLink(lock)) {
            throw new IOException("Invalid update lock");
        }
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (true) {
            try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                java.nio.channels.FileLock acquired;
                try {
                    acquired = channel.tryLock();
                } catch (OverlappingFileLockException busy) {
                    acquired = null;
                }
                if (acquired != null) {
                    Path active = mods.resolve(".catbud-update-active");
                    try {
                        if (Files.isSymbolicLink(active)) {
                            throw new IOException("Invalid active update marker");
                        }
                        if (!Files.exists(active)) {
                            issueToken(mods, updates);
                            return;
                        }
                    } finally {
                        acquired.release();
                    }
                    recoverActive(mods, updates);
                }
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("Update is still running; wait before launching Minecraft");
            }
            Thread.sleep(100);
        }
    }

    private static void recoverActive(Path mods, Path updates) throws Exception {
        Path active = mods.resolve(".catbud-update-active");
        if (Files.isSymbolicLink(active) || Files.size(active) > 64) {
            throw new IOException("Invalid active update marker");
        }
        String id = Files.readString(active).strip();
        UUID.fromString(id);
        Path directory = updates.resolve(id);
        UpdateJournal journal = UpdateJournal.read(directory);
        if (!journal.plan().mods().equals(mods) || !journal.plan().batchId().equals(id)) {
            throw new IOException("Active update does not belong to this game instance");
        }
        try (var installer = new UpdateInstaller(journal)) {
            String status = UpdateJournal.properties(directory.resolve("result.properties")).getProperty("status");
            if (status.equals("INSTALLED")) {
                for (var item : journal.plan().items()) {
                    FileIntegrity.require(mods.resolve(item.newFile()), item.newHash());
                }
            } else {
                installer.recover();
            }
            Files.delete(active);
        }
    }

    private static void issueToken(Path mods, Path updates) throws IOException {
        Path token = updates.resolve("launch-ready");
        Path temporary = updates.resolve("launch-ready.tmp");
        if (Files.isSymbolicLink(token) || Files.isSymbolicLink(temporary)) {
            throw new IOException("Invalid launch guard token path");
        }
        Files.writeString(temporary, mods.toString() + "\n" + System.currentTimeMillis() + "\n");
        UpdateJournal.force(temporary);
        UpdateJournal.move(temporary, token, true);
    }
}
