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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InstallerProcessTest {

    @TempDir Path temporary;

    @Test
    void standaloneHelperConfirmsReadinessAndCancelsWithoutChangingMods() throws Exception {
        var journal = batch();
        Process helper = start("install", journal, Long.toString(ProcessHandle.current().pid()));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (helper.isAlive() && !Files.exists(journal.directory().resolve("ready")) && System
                .nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(journal.directory().resolve("ready")), () -> readLog(journal));
            assertEquals("READY", UpdateJournal.properties(journal.directory().resolve("result.properties"))
                .getProperty("status"));
            Files.writeString(journal.directory().resolve("cancel"), "cancel");
            assertTrue(helper.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, helper.exitValue(), () -> readLog(journal));
            var item = journal.plan().items().getFirst();
            FileIntegrity.require(journal.plan().mods().resolve(item.oldFile()), item.oldHash());
            assertFalse(Files.exists(journal.plan().mods().resolve(item.newFile())));
        } finally {
            helper.destroyForcibly();
        }
    }

    @Test
    void standaloneRecoveryWorksWithoutCoreOrThirdPartyLibraries() throws Exception {
        var journal = batch();
        try (var installer = new UpdateInstaller(journal, (step, index) -> {
            if (step.equals("installed")) {
                throw new AssertionError("Simulated power loss");
            }
        })) {
            assertThrows(AssertionError.class, installer::install);
        }
        Process helper = start("recover", journal, "0");
        try {
            assertTrue(helper.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, helper.exitValue(), () -> readLog(journal));
            var item = journal.plan().items().getFirst();
            FileIntegrity.require(journal.plan().mods().resolve(item.oldFile()), item.oldHash());
            assertFalse(Files.exists(journal.plan().mods().resolve(item.newFile())));
        } finally {
            helper.destroyForcibly();
        }
    }

    private Process start(String mode, UpdateJournal journal, String pid) throws IOException {
        Path executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith(
            "Windows") ? "java.exe" : "java");
        return new ProcessBuilder(executable.toString(), "-jar", System.getProperty("catbud.installerJar"), mode,
            journal.directory().toString(), pid).redirectErrorStream(true).redirectOutput(journal.directory().resolve(
                "test-helper.log").toFile()).start();
    }

    private UpdateJournal batch() throws IOException {
        Path mods = Files.createDirectory(temporary.resolve("mods")).toRealPath();
        Path updates = Files.createDirectory(temporary.resolve("updates")).toRealPath();
        Path directory = Files.createDirectory(updates.resolve(UUID.randomUUID().toString())).toRealPath();
        Path downloads = Files.createDirectory(directory.resolve("downloads"));
        Path old = Files.writeString(mods.resolve("core-old.jar"), "original");
        Path next = Files.writeString(downloads.resolve("core-new.jar"), "updated");
        var item = new InstallationPlan.Item("core-old.jar", FileIntegrity.sha256(old), "core-new.jar", FileIntegrity
            .sha256(next), Files.size(next));
        var journal = new UpdateJournal(directory, new InstallationPlan(directory.getFileName().toString(), "v1.0.4",
            "26.2", mods, List.of(item)));
        journal.create();
        return journal;
    }

    @Test
    void launcherGuardRecoversInterruptedInstallationBeforeIssuingSingleUseLaunchProof() throws Exception {
        var journal = batch();
        try (var installer = new UpdateInstaller(journal, (step, index) -> {
            if (step.equals("installed")) {
                throw new AssertionError("Power loss");
            }
        })) {
            assertThrows(AssertionError.class, installer::install);
        }
        Files.writeString(journal.plan().mods().resolve(".catbud-update-active"), journal.plan().batchId());
        Process guard = startGuard(journal);
        try {
            assertTrue(guard.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, guard.exitValue(), () -> readLog(journal));
            var item = journal.plan().items().getFirst();
            FileIntegrity.require(journal.plan().mods().resolve(item.oldFile()), item.oldHash());
            assertFalse(Files.exists(journal.plan().mods().resolve(item.newFile())));
            assertFalse(Files.exists(journal.plan().mods().resolve(".catbud-update-active")));
            byte[] helper = Files.readAllBytes(Path.of(System.getProperty("catbud.installerJar")));
            var first = new LauncherIntegration(temporary.toRealPath(), temporary.toRealPath(), helper);
            assertTrue(first.guarded());
            var second = new LauncherIntegration(temporary.toRealPath(), temporary.toRealPath(), helper);
            assertFalse(second.guarded());
        } finally {
            guard.destroyForcibly();
        }
    }

    @Test
    void launcherGuardWaitsForRunningHelperAndDoesNotBypassItsLock() throws Exception {
        var journal = batch();
        Process helper = start("install", journal, Long.toString(ProcessHandle.current().pid()));
        Process guard = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(journal.directory().resolve("ready")) && helper.isAlive() && System
                .nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(journal.directory().resolve("ready")), () -> readLog(journal));
            guard = startGuard(journal);
            assertFalse(guard.waitFor(300, TimeUnit.MILLISECONDS));
            Files.writeString(journal.directory().resolve("cancel"), "cancel");
            assertTrue(helper.waitFor(5, TimeUnit.SECONDS));
            assertTrue(guard.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, guard.exitValue());
            assertTrue(Files.exists(journal.directory().getParent().resolve("launch-ready")));
        } finally {
            helper.destroyForcibly();
            if (guard != null) {
                guard.destroyForcibly();
            }
        }
    }

    private Process startGuard(UpdateJournal journal) throws IOException {
        Path executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith(
            "Windows") ? "java.exe" : "java");
        return new ProcessBuilder(executable.toString(), "-jar", System.getProperty("catbud.installerJar"), "guard",
            journal.plan().mods().toString(), journal.directory().getParent().toString()).redirectErrorStream(true)
            .redirectOutput(journal.directory().resolve("test-helper.log").toFile()).start();
    }

    private static String readLog(UpdateJournal journal) {
        try {
            return Files.readString(journal.directory().resolve("test-helper.log"));
        } catch (IOException error) {
            return error.toString();
        }
    }
}
