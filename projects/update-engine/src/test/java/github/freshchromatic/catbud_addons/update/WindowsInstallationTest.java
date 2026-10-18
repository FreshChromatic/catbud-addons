package github.freshchromatic.catbud_addons.update;

import github.freshchromatic.catbud_addons.update.install.FileIntegrity;
import github.freshchromatic.catbud_addons.update.install.InstallationPlan;
import github.freshchromatic.catbud_addons.update.install.UpdateInstaller;
import github.freshchromatic.catbud_addons.update.install.UpdateJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsInstallationTest {

    @TempDir Path directory;

    @Test
    void retriesAReadableJarLockedAgainstDeletionByAnotherWindowsProcess() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path mods = Files.createDirectory(directory.resolve("mods")).toRealPath();
        Path batch = Files.createDirectory(directory.resolve("batch")).toRealPath();
        Path downloads = Files.createDirectory(batch.resolve("downloads"));
        Path old = Files.writeString(mods.resolve("core-old.jar"), "original");
        Path next = Files.writeString(downloads.resolve("core-new.jar"), "updated");
        var item = new InstallationPlan.Item("core-old.jar", FileIntegrity.sha256(old), "core-new.jar", FileIntegrity
            .sha256(next), Files.size(next));
        var journal = new UpdateJournal(batch, new InstallationPlan(UUID.randomUUID().toString(), "v1.0.4", "26.2",
            mods, List.of(item)));
        journal.create();
        Path script = directory.resolve("lock.ps1");
        Files.writeString(script, """
            param([string]$target, [string]$ready, [string]$release)
            $stream = [System.IO.FileStream]::new($target, 'Open', 'Read', 'Read')
            try {
                [System.IO.File]::WriteAllText($ready, 'ready')
                while (-not [System.IO.File]::Exists($release)) { Start-Sleep -Milliseconds 50 }
            } finally { $stream.Dispose() }
            """);
        Path ready = directory.resolve("ready");
        Path release = directory.resolve("release");
        Process[] locker = new Process[1];
        try (var releaseTimer = Executors.newSingleThreadScheduledExecutor();
            var installer = new UpdateInstaller(journal, (step, index) -> {
                if (step.equals("staged")) {
                    locker[0] = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-File", script
                        .toString(), old.toString(), ready.toString(), release.toString()).redirectErrorStream(true)
                        .redirectOutput(directory.resolve("lock.log").toFile()).start();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!Files.exists(ready) && locker[0].isAlive() && System.nanoTime() < deadline) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException error) {
                            throw new java.io.IOException(error);
                        }
                    }
                    assertTrue(Files.exists(ready), "Windows lock process did not start");
                    releaseTimer.schedule(() -> {
                        try {
                            Files.writeString(release, "release");
                        } catch (java.io.IOException error) {
                            throw new java.io.UncheckedIOException(error);
                        }
                    }, 1200, TimeUnit.MILLISECONDS);
                }
            })) {
            long started = System.nanoTime();
            installer.install();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 1200);
            FileIntegrity.require(mods.resolve("core-new.jar"), item.newHash());
            assertFalse(Files.exists(old));
        } finally {
            if (locker[0] != null) {
                locker[0].destroyForcibly();
            }
        }
    }
}
