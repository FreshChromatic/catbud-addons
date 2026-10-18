package github.freshchromatic.catbud_addons.update;

import github.freshchromatic.catbud_addons.update.install.FileIntegrity;
import github.freshchromatic.catbud_addons.update.install.InstallationPlan;
import github.freshchromatic.catbud_addons.update.install.UpdateInstaller;
import github.freshchromatic.catbud_addons.update.install.UpdateJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class UpdateInstallerTest {

    @TempDir Path temporary;

    private UpdateJournal batch(String name) throws IOException {
        Path root = Files.createDirectory(temporary.resolve(name));
        Path mods = Files.createDirectory(root.resolve("mods")).toRealPath();
        Path directory = Files.createDirectory(root.resolve("update")).toRealPath();
        Path downloads = Files.createDirectory(directory.resolve("downloads"));
        var items = new ArrayList<InstallationPlan.Item>();
        for (String module : List.of("magic_tower", "public_warps")) {
            Path old = Files.writeString(mods.resolve(module + "-old.jar"), "original-" + module);
            Path next = Files.writeString(downloads.resolve(module + "-new.jar"), "updated-" + module);
            items.add(new InstallationPlan.Item(old.getFileName().toString(), FileIntegrity.sha256(old), next
                .getFileName().toString(), FileIntegrity.sha256(next), Files.size(next)));
        }
        var plan = new InstallationPlan(UUID.randomUUID().toString(), "v1.0.4", "26.2", mods, items);
        var journal = new UpdateJournal(directory, plan);
        journal.create();
        return UpdateJournal.read(directory);
    }

    @Test
    void installsBothFilesAndKeepsRecoverableBackups() throws Exception {
        var journal = batch("success");
        try (var installer = new UpdateInstaller(journal)) {
            installer.install();
            for (var item : journal.plan().items()) {
                assertFalse(Files.exists(journal.plan().mods().resolve(item.oldFile())));
                FileIntegrity.require(journal.plan().mods().resolve(item.newFile()), item.newHash());
            }
            assertEquals("INSTALLED", result(journal));
            installer.recover();
            assertOriginal(journal);
        }
    }

    @Test
    void rollsBackFailuresAtEveryFileMutation() throws Exception {
        for (String step : List.of("backed-up", "installed")) {
            for (int index = 0; index < 2; index++) {
                var journal = batch(step + index);
                int failingIndex = index;
                try (var installer = new UpdateInstaller(journal, (current, item) -> {
                    if (step.equals(current) && item == failingIndex) {
                        throw new IOException("Injected interruption");
                    }
                })) {
                    assertThrows(IOException.class, installer::install);
                    assertOriginal(journal);
                    assertEquals("ROLLED_BACK", result(journal));
                }
            }
        }
    }

    @Test
    void recoversAfterCrashBeforeJournalWasUpdated() throws Exception {
        var journal = batch("crash");
        try (var installer = new UpdateInstaller(journal, (step, index) -> {
            if (step.equals("installed") && index == 0) {
                throw new AssertionError("Simulated process termination");
            }
        })) {
            assertThrows(AssertionError.class, installer::install);
        }
        try (var recovery = new UpdateInstaller(UpdateJournal.read(journal.directory()))) {
            recovery.recover();
            assertOriginal(journal);
        }
    }

    @Test
    void doesNotTouchFilesWhenOldFileChangedOrDownloadIsCorrupt() throws Exception {
        var changed = batch("changed");
        var first = changed.plan().items().getFirst();
        Files.writeString(changed.plan().mods().resolve(first.oldFile()), "player replacement");
        try (var installer = new UpdateInstaller(changed)) {
            assertThrows(IOException.class, installer::install);
            assertEquals("player replacement", Files.readString(changed.plan().mods().resolve(first.oldFile())));
        }
        var corrupt = batch("corrupt");
        Files.writeString(corrupt.directory().resolve("downloads").resolve(corrupt.plan().items().getFirst().newFile()),
            "bad");
        try (var installer = new UpdateInstaller(corrupt)) {
            assertThrows(IOException.class, installer::install);
            assertOriginal(corrupt);
        }
    }

    @Test
    void refusesConcurrentInstallerAndConflictingTarget() throws Exception {
        var journal = batch("locked");
        try (var first = new UpdateInstaller(journal)) {
            assertThrows(IOException.class, () -> new UpdateInstaller(journal));
            Path conflict = journal.plan().mods().resolve(journal.plan().items().getFirst().newFile());
            Files.writeString(conflict, "unrelated content");
            assertThrows(IOException.class, first::install);
            assertEquals("unrelated content", Files.readString(conflict));
        }
    }

    @Test
    void rejectsPathTraversalAndDuplicateDestinations() {
        assertThrows(IllegalArgumentException.class, () -> new InstallationPlan.Item("../old.jar", "a".repeat(64),
            "new.jar", "b".repeat(64), 1));
        var item = new InstallationPlan.Item("old.jar", "a".repeat(64), "new.jar", "b".repeat(64), 1);
        assertThrows(IllegalArgumentException.class, () -> new InstallationPlan(UUID.randomUUID().toString(), "v1.0.4",
            "26.2", temporary, List.of(item, item)));
    }

    private static String result(UpdateJournal journal) throws IOException {
        return UpdateJournal.properties(journal.directory().resolve("result.properties")).getProperty("status");
    }

    private static void assertOriginal(UpdateJournal journal) throws IOException {
        for (var item : journal.plan().items()) {
            FileIntegrity.require(journal.plan().mods().resolve(item.oldFile()), item.oldHash());
            assertFalse(Files.exists(journal.plan().mods().resolve(item.newFile())));
        }
    }
}
