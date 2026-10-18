package github.freshchromatic.catbud_addons.update;

import github.freshchromatic.catbud_addons.update.install.FileIntegrity;
import github.freshchromatic.catbud_addons.update.install.InstallationPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

public final class UpdateDownloadService {

    public record DownloadBatch(Path directory, InstallationPlan installation) {}

    private final ReleaseHttpClient http;
    private final Path              directory;

    public UpdateDownloadService(ReleaseHttpClient http, Path directory) {
        this.http = http;
        this.directory = directory;
    }

    public DownloadBatch download(UpdatePlan plan, BooleanSupplier cancelled, LongConsumer progress) throws Exception {
        Files.createDirectories(directory);
        String id = UUID.randomUUID().toString();
        Path batch = Files.createDirectory(directory.resolve(id)).toRealPath();
        Path downloads = Files.createDirectory(batch.resolve("downloads"));
        try {
            if (Files.getFileStore(batch).getUsableSpace() < plan.downloadBytes() + 32L * 1024 * 1024) {
                throw new IOException("Not enough download disk space");
            }
            long completed = 0;
            var items = new ArrayList<InstallationPlan.Item>();
            for (var replacement : plan.replacements()) {
                FileIntegrity.require(replacement.oldJar(), replacement.oldHash());
                long base = completed;
                http.download(replacement.artifact(), downloads.resolve(replacement.artifact().file()), cancelled,
                    count -> progress.accept(base + count));
                completed += replacement.artifact().size();
                items.add(new InstallationPlan.Item(replacement.oldJar().getFileName().toString(), replacement
                    .oldHash(), replacement.artifact().file(), replacement.artifact().sha256(), replacement.artifact()
                        .size()));
            }
            if (cancelled.getAsBoolean()) {
                throw new IOException("Download cancelled");
            }
            if (Files.getFileStore(plan.mods()).getUsableSpace() < plan.downloadBytes() + 32L * 1024 * 1024) {
                throw new IOException("Not enough installation disk space");
            }
            return new DownloadBatch(batch, new InstallationPlan(id, plan.releaseId(), plan.minecraft(), plan.mods(),
                items));
        } catch (Exception error) {
            try {
                new UpdateBatchStore(directory.toRealPath()).cleanCancelledDownloads(batch, plan);
            } catch (Exception cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }
}
