package github.freshchromatic.catbud_addons.update;

import github.freshchromatic.catbud_addons.update.install.InstallationPlan;
import github.freshchromatic.catbud_addons.update.install.UpdateJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

public final class InstallerLauncher {

    private final Path             directory;
    private final Callable<byte[]> helperResource;

    public InstallerLauncher(Path directory, Callable<byte[]> helperResource) {
        this.directory = directory;
        this.helperResource = helperResource;
    }

    public Path launch(UpdateDownloadService.DownloadBatch batch, BooleanSupplier cancelled) throws Exception {
        Path location = batch.directory();
        var journal = new UpdateJournal(location, batch.installation());
        journal.create();
        Path helper = location.resolve("installer.jar");
        Files.write(helper, helperResource.call(), StandardOpenOption.CREATE_NEW);
        Files.writeString(location.resolve("RECOVERY.txt"), "Close Minecraft, then run:\n\"" + javaExecutable()
            + "\" -jar \"" + helper + "\" recover \"" + location
            + "\" 0\nDo not run recovery while Minecraft is running. Backups and logs are kept with this batch.\n");
        Process process = new ProcessBuilder(javaExecutable(), "-jar", helper.toString(), "install", location
            .toString(), Long.toString(ProcessHandle.current().pid())).redirectErrorStream(true).redirectOutput(location
                .resolve("helper.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (process.isAlive() && !Files.exists(location.resolve("ready")) && System.nanoTime() < deadline) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("Installation preparation cancelled");
                }
                Thread.sleep(50);
            }
            if (!process.isAlive() || !Files.exists(location.resolve("ready")) || cancelled.getAsBoolean()) {
                throw new IOException("Installer did not confirm readiness; original files were kept");
            }
            InstallationPlan.requireRealDirectory(directory.toRealPath());
            Files.writeString(directory.resolve("pending"), batch.installation().batchId(),
                StandardOpenOption.CREATE_NEW);
            return location;
        } catch (Exception error) {
            try {
                Files.writeString(location.resolve("cancel"), "cancel");
            } catch (IOException failed) {
                error.addSuppressed(failed);
            }
            process.destroyForcibly();
            throw error;
        }
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "javaw.exe"
            : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
