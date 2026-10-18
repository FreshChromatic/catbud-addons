package github.freshchromatic.catbud_addons.update.install;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class InstallerMain {

    private InstallerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("guard")) {
            LauncherGuard.await(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length != 3 || !(args[0].equals("install") || args[0].equals("recover"))) {
            throw new IllegalArgumentException("Expected install|recover journal-directory game-pid");
        }
        UpdateJournal journal = UpdateJournal.read(Path.of(args[1]));
        try (var installer = new UpdateInstaller(journal)) {
            if (args[0].equals("recover")) {
                long gamePid = Long.parseLong(args[2]);
                if (gamePid < 0) {
                    throw new IllegalArgumentException("Invalid game process identifier");
                }
                if (gamePid > 0) {
                    ProcessHandle.of(gamePid).ifPresent(game -> game.onExit().join());
                }
                installer.recover();
                clearActive(journal);
                return;
            }
            long pid = Long.parseLong(args[2]);
            var game = ProcessHandle.of(pid).orElseThrow(() -> new IOException(
                "Game exited before installer handshake"));
            Files.writeString(journal.plan().mods().resolve(".catbud-update-active"), journal.plan().batchId(),
                java.nio.file.StandardOpenOption.CREATE_NEW);
            UpdateJournal.force(journal.plan().mods().resolve(".catbud-update-active"));
            journal.result("READY", "Waiting for the game to exit");
            Files.writeString(journal.directory().resolve("ready"), Long.toString(ProcessHandle.current().pid()));
            while (game.isAlive() && !journal.cancelled()) {
                Thread.sleep(250);
            }
            if (journal.cancelled()) {
                journal.result("CANCELLED", "Installation cancelled");
                clearActive(journal);
                return;
            }
            requireApproval(journal.plan());
            installer.install();
            clearActive(journal);
        } catch (Exception error) {
            String status = UpdateJournal.properties(journal.directory().resolve("result.properties")).getProperty(
                "status");
            if (!status.equals("ROLLED_BACK") && !status.equals("REPAIR_REQUIRED")) {
                journal.result("FAILED", error.getMessage() == null ? "Installation failed" : error.getMessage());
            }
            if (!status.equals("INSTALLING") && !status.equals("REPAIR_REQUIRED")) {
                clearActive(journal);
            }
            throw error;
        }
    }

    private static void clearActive(UpdateJournal journal) throws IOException {
        Path active = journal.plan().mods().resolve(".catbud-update-active");
        if (Files.exists(active) && !Files.isSymbolicLink(active) && Files.size(active) < 64 && Files.readString(active)
            .strip().equals(journal.plan().batchId())) {
            Files.delete(active);
        }
    }

    private static void requireApproval(InstallationPlan plan) throws IOException, InterruptedException {
        URI uri = URI.create("https://api.cbwp.workers.dev/addons/v1/catalog?minecraft=" + plan.minecraft()
            + "&channel=stable");
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Cache-Control", "no-cache")
            .header("User-Agent", "Catbud-Update-Installer/1").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200 || !response.headers().firstValue("X-Catbud-Release-Id").orElse("").equals(
                plan.releaseId())) {
                throw new IOException("Release approval changed or is unavailable; original files were kept");
            }
        }
    }
}
