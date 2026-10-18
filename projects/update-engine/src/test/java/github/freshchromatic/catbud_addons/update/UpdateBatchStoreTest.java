package github.freshchromatic.catbud_addons.update;

import github.freshchromatic.catbud_addons.update.install.FileIntegrity;
import github.freshchromatic.catbud_addons.update.install.InstallationPlan;
import github.freshchromatic.catbud_addons.update.install.UpdateInstaller;
import github.freshchromatic.catbud_addons.update.install.UpdateJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class UpdateBatchStoreTest {

    @TempDir Path directory;

    @Test
    void prunesOnlyExpiredVerifiedBatchesAndKeepsTheLatestBackup() throws Exception {
        Path mods = Files.createDirectory(directory.resolve("mods")).toRealPath();
        Path updates = Files.createDirectory(directory.resolve("updates")).toRealPath();
        Files.writeString(mods.resolve("core-1.jar"), "original");
        long now = System.currentTimeMillis();
        var first = installed(mods, updates, "core-1.jar", "core-2.jar", now - 8L * 86400000);
        var latest = installed(mods, updates, "core-2.jar", "core-3.jar", now - 86400000);
        new UpdateBatchStore(updates).prune(now);
        assertFalse(Files.exists(first.directory()));
        assertTrue(Files.exists(latest.directory()));
        assertEquals("core-3.jar", Files.readString(mods.resolve("core-3.jar")));
        assertTrue(Files.exists(mods.resolve(".catbud-update-" + latest.plan().batchId()).resolve(
            "core-2.jar.backup")));
    }

    @Test
    void unknownFilesPreventCleanupBeforeAnyBackupIsDeleted() throws Exception {
        Path mods = Files.createDirectory(directory.resolve("mods")).toRealPath();
        Path updates = Files.createDirectory(directory.resolve("updates")).toRealPath();
        Files.writeString(mods.resolve("core-1.jar"), "original");
        long now = System.currentTimeMillis();
        var first = installed(mods, updates, "core-1.jar", "core-2.jar", now - 8L * 86400000);
        installed(mods, updates, "core-2.jar", "core-3.jar", now - 86400000);
        Files.writeString(first.directory().resolve("keep.txt"), "user notes");
        assertThrows(IOException.class, () -> new UpdateBatchStore(updates).prune(now));
        assertEquals("user notes", Files.readString(first.directory().resolve("keep.txt")));
        assertTrue(Files.exists(mods.resolve(".catbud-update-" + first.plan().batchId()).resolve("core-1.jar.backup")));
    }

    private static UpdateJournal installed(Path mods, Path updates, String oldName, String newName, long confirmed)
        throws Exception {
        String id = UUID.randomUUID().toString();
        Path batch = Files.createDirectory(updates.resolve(id));
        Path downloads = Files.createDirectory(batch.resolve("downloads"));
        Path fresh = Files.writeString(downloads.resolve(newName), newName);
        var item = new InstallationPlan.Item(oldName, FileIntegrity.sha256(mods.resolve(oldName)), newName,
            FileIntegrity.sha256(fresh), Files.size(fresh));
        var journal = new UpdateJournal(batch, new InstallationPlan(id, "v1.0.4", "26.2", mods, List.of(item)));
        journal.create();
        try (var installer = new UpdateInstaller(journal)) {
            installer.install();
        }
        Files.writeString(batch.resolve("confirmed-at"), Long.toString(confirmed));
        return journal;
    }
}
