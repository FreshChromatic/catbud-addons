package github.freshchromatic.catbud_addons.update;

import github.freshchromatic.catbud_addons.update.install.FileIntegrity;
import github.freshchromatic.catbud_addons.update.install.InstallationPlan;
import github.freshchromatic.catbud_addons.update.install.UpdateJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateCoordinator implements AutoCloseable {

    public enum CheckState {
        UNCHECKED, CHECKING, CURRENT, AVAILABLE, NO_COMPATIBLE_RELEASE, FAILED, MANUAL_REQUIRED
    }

    public enum InstallState {
        IDLE, DOWNLOADING, VERIFYING, READY, INSTALLED, ROLLED_BACK, FAILED, REPAIR_REQUIRED
    }

    public record Snapshot(CheckState check, InstallState install, String detail, UpdatePlan plan, List<Notice> notices,
        long lastChecked, long downloaded, long total) {

        public Snapshot {
            notices = List.copyOf(notices);
        }
    }

    private final ReleaseHttpClient                 http;
    private final ReleaseCatalogClient              catalog;
    private final NoticeRepository                  noticeRepository;
    private final NotificationStateStore            notifications;
    private final Callable<InstalledAddonInventory> inventory;
    private final String                            minecraft;
    private final String                            loaderVersion;
    private final Path                              directory;
    private final Callable<byte[]>                  helperResource;
    private final Set<String>                       installedModules;
    private final ExecutorService                   worker    = Executors.newSingleThreadExecutor(work -> {
                                                                  var thread = new Thread(work, "catbud-update");
                                                                  thread.setDaemon(true);
                                                                  return thread;
                                                              });
    private final AtomicBoolean                     cancelled = new AtomicBoolean();

    private volatile Snapshot snapshot  = new Snapshot(CheckState.UNCHECKED, InstallState.IDLE, "", null, List.of(), 0,
        0, 0);
    private volatile boolean  busy;
    private volatile String   server    = "";
    private volatile long     nextCheck = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(15000,
        45001);
    private volatile long     lastAttempt;
    private int               failures;
    private Path              pending;

    public UpdateCoordinator(ReleaseHttpClient http, ReleaseCatalogClient catalog, NotificationStateStore notifications, Callable<InstalledAddonInventory> inventory, UpdateEnvironment environment, Callable<byte[]> helperResource) {
        this.http = http;
        this.catalog = catalog;
        this.noticeRepository = new NoticeRepository(http, environment.minecraft(), environment.directory()
            .getParent());
        this.notifications = notifications;
        this.inventory = inventory;
        this.minecraft = environment.minecraft();
        this.loaderVersion = environment.loaderVersion();
        this.directory = environment.directory();
        this.helperResource = helperResource;
        this.installedModules = environment.installedModules();
        worker.execute(this::restore);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public NotificationStateStore notifications() {
        return notifications;
    }

    public String noticeError() {
        return noticeRepository.error();
    }

    public List<Notice> notices() {
        long now = System.currentTimeMillis();
        return snapshot.notices().stream().filter(notice -> notice.applies(installedModules, minecraft, server, now))
            .toList();
    }

    public int unreadCount() {
        return (int) notices().stream().filter(notifications::unread).count();
    }

    public int updateCount() {
        return snapshot.plan() == null ? 0
            : (int) snapshot.plan().replacements().stream().filter(item -> !notifications.skipped(item.artifact()
                .module(), item.artifact().releaseRevision().key())).count();
    }

    public void tick(String server) {
        if (!this.server.equals(server)) {
            this.server = server;
            if (notifications.automatic()) {
                nextCheck = Math.min(nextCheck, Math.max(lastAttempt + 30000, System.currentTimeMillis()));
            }
        }
        if (notifications.automatic() && System.currentTimeMillis() >= nextCheck) {
            check(false);
        }
    }

    public synchronized void check(boolean manual) {
        long now = System.currentTimeMillis();
        if (busy || now - lastAttempt < 30000) {
            return;
        }
        busy = true;
        lastAttempt = now;
        snapshot = new Snapshot(snapshot.install() == InstallState.READY ? snapshot.check() : CheckState.CHECKING,
            snapshot.install(), "", snapshot.plan(), snapshot.notices(), snapshot.lastChecked(), 0, 0);
        String scope = server;
        worker.execute(() -> refresh(manual, scope));
    }

    public boolean canCheck() {
        return !busy && System.currentTimeMillis() - lastAttempt >= 30000;
    }

    private void refresh(boolean fresh, String scope) {
        List<Notice> notices = noticeRepository.refresh(scope);
        notifications.remember(notices, System.currentTimeMillis());
        try {
            if (snapshot.install() == InstallState.READY) {
                snapshot = new Snapshot(snapshot.check(), snapshot.install(), snapshot.detail(), snapshot.plan(),
                    notices, snapshot.lastChecked(), snapshot.downloaded(), snapshot.total());
                nextCheck = System.currentTimeMillis() + 1800000;
                return;
            }
            var received = catalog.check(fresh);
            CheckState check;
            UpdatePlan plan = null;
            String detail = "";
            if (received.manifest() == null) {
                check = received.state().equals("release_unavailable") ? CheckState.FAILED
                    : CheckState.NO_COMPATIBLE_RELEASE;
            } else {
                try {
                    plan = UpdatePlanner.plan(received.manifest(), inventory.call(), minecraft, loaderVersion, Runtime
                        .version().feature());
                    check = plan.replacements().isEmpty() ? CheckState.CURRENT : CheckState.AVAILABLE;
                } catch (IllegalArgumentException error) {
                    check = CheckState.MANUAL_REQUIRED;
                    detail = error.getMessage();
                }
            }
            snapshot = new Snapshot(check, snapshot.install(), detail, plan, notices, System.currentTimeMillis(), 0, 0);
            failures = 0;
            nextCheck = System.currentTimeMillis() + 1800000 + ThreadLocalRandom.current().nextLong(60000);
        } catch (Exception error) {
            snapshot = new Snapshot(CheckState.FAILED, snapshot.install(), message(error), snapshot.plan(), notices,
                snapshot.lastChecked(), 0, 0);
            int[] retryMinutes = { 1, 5, 15, 30 };
            long delay = retryMinutes[Math.min(failures++, 3)] * 60000L;
            if (error instanceof ReleaseHttpClient.RequestFailure failed) {
                delay = Math.max(delay, failed.retrySeconds() * 1000);
            }
            nextCheck = System.currentTimeMillis() + delay;
        } finally {
            busy = false;
        }
    }

    public synchronized boolean canDownload() {
        UpdatePlan plan = snapshot.plan();
        return !busy && plan != null && !plan.replacements().isEmpty() && List.of(InstallState.IDLE,
            InstallState.FAILED, InstallState.ROLLED_BACK, InstallState.INSTALLED).contains(snapshot.install());
    }

    public synchronized void download() {
        if (!canDownload()) {
            return;
        }
        UpdatePlan plan = snapshot.plan();
        busy = true;
        pending = null;
        cancelled.set(false);
        snapshot = new Snapshot(snapshot.check(), InstallState.VERIFYING, "", plan, snapshot.notices(), snapshot
            .lastChecked(), 0, plan.downloadBytes());
        worker.execute(() -> prepare(plan));
    }

    private void prepare(UpdatePlan plan) {
        UpdateDownloadService.DownloadBatch batch = null;
        try {
            requireCurrent(plan);
            progress(InstallState.DOWNLOADING, plan, 0);
            batch = new UpdateDownloadService(http, directory).download(plan, cancelled::get, count -> progress(
                InstallState.DOWNLOADING, plan, count));
            progress(InstallState.VERIFYING, plan, plan.downloadBytes());
            requireCurrent(plan);
            if (cancelled.get()) {
                throw new IOException("Download cancelled");
            }
            pending = new InstallerLauncher(directory, helperResource).launch(batch, cancelled::get);
            if (cancelled.get()) {
                cancel();
                return;
            }
            snapshot = new Snapshot(snapshot.check(), InstallState.READY, "", plan, snapshot.notices(), snapshot
                .lastChecked(), plan.downloadBytes(), plan.downloadBytes());
        } catch (Exception error) {
            snapshot = new Snapshot(snapshot.check(), InstallState.FAILED, message(error), plan, snapshot.notices(),
                snapshot.lastChecked(), 0, plan.downloadBytes());
            if (batch != null && !Files.exists(batch.directory().resolve("plan.properties"))) {
                try {
                    new UpdateBatchStore(directory.toRealPath()).cleanCancelledDownloads(batch.directory(), plan);
                } catch (Exception cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
        } finally {
            busy = false;
        }
    }

    private void requireCurrent(UpdatePlan plan) throws Exception {
        var current = catalog.check(true);
        if (current.manifest() == null || !current.manifest().releaseId().equals(plan.releaseId())) {
            throw new IOException("Recommended release changed; check for updates again");
        }
        for (var replacement : plan.replacements()) {
            if (!current.manifest().artifacts().contains(replacement.artifact())) {
                throw new IOException("Release artifacts changed after planning");
            }
        }
    }

    private void progress(InstallState state, UpdatePlan plan, long count) {
        snapshot = new Snapshot(snapshot.check(), state, "", plan, snapshot.notices(), snapshot.lastChecked(), count,
            plan.downloadBytes());
    }

    public void cancel() throws IOException {
        cancelled.set(true);
        http.cancelDownload();
        if (pending != null) {
            Files.writeString(pending.resolve("cancel"), "cancel");
            Files.deleteIfExists(directory.resolve("pending"));
            snapshot = new Snapshot(snapshot.check(), InstallState.IDLE, "", snapshot.plan(), snapshot.notices(),
                snapshot.lastChecked(), 0, 0);
        }
    }

    public void requestCancel() {
        try {
            cancel();
        } catch (IOException error) {
            snapshot = new Snapshot(snapshot.check(), snapshot.install(), "Cancellation failed: " + message(error),
                snapshot.plan(), snapshot.notices(), snapshot.lastChecked(), snapshot.downloaded(), snapshot.total());
        }
    }

    private void restore() {
        try {
            snapshot = new Snapshot(snapshot.check(), snapshot.install(), "", null, noticeRepository.cached(""), 0, 0,
                0);
        } catch (IOException ignored) {
        }
        try {
            var cached = catalog.cached();
            if (cached != null && cached.manifest() != null) {
                var plan = UpdatePlanner.plan(cached.manifest(), inventory.call(), minecraft, loaderVersion, Runtime
                    .version().feature());
                snapshot = new Snapshot(CheckState.UNCHECKED, InstallState.IDLE,
                    "Cached release; fresh approval required before installation", plan, snapshot.notices(), cached
                        .checkedAt(), 0, 0);
            }
        } catch (Exception ignored) {
        }
        try {
            new UpdateBatchStore(directory).prune(System.currentTimeMillis());
        } catch (Exception cleanup) {
            snapshot = new Snapshot(snapshot.check(), snapshot.install(), "Backup cleanup skipped: " + message(cleanup),
                snapshot.plan(), snapshot.notices(), snapshot.lastChecked(), 0, 0);
        }
        try {
            Path marker = directory.resolve("pending");
            if (!Files.exists(marker)) {
                return;
            }
            String batch = Files.readString(marker).strip();
            UUID.fromString(batch);
            pending = directory.resolve(batch);
            var journal = UpdateJournal.read(pending);
            String result = UpdateJournal.properties(pending.resolve("result.properties")).getProperty("status");
            InstallState state = switch (result) {
                case "INSTALLED" -> InstallState.INSTALLED;
                case "ROLLED_BACK" -> InstallState.ROLLED_BACK;
                case "REPAIR_REQUIRED", "INSTALLING" -> InstallState.REPAIR_REQUIRED;
                case "READY" -> InstallState.REPAIR_REQUIRED;
                default -> InstallState.FAILED;
            };
            if (state == InstallState.INSTALLED) {
                InstalledAddonInventory loaded = inventory.call();
                if (!loaded.problems().isEmpty()) {
                    throw new IOException("Updated addon origins could not be verified");
                }
                for (var item : journal.plan().items()) {
                    FileIntegrity.require(journal.plan().mods().resolve(item.newFile()), item.newHash());
                    if (loaded.jars().stream().noneMatch(jar -> jar.path().getFileName().toString().equals(item
                        .newFile()))) {
                        throw new IOException("Updated addon was not loaded");
                    }
                }
            }
            snapshot = new Snapshot(CheckState.UNCHECKED, state, "", null, List.of(), 0, 0, 0);
            if (List.of(InstallState.INSTALLED, InstallState.ROLLED_BACK, InstallState.FAILED).contains(state)) {
                if (state == InstallState.INSTALLED) {
                    Files.writeString(pending.resolve("confirmed-at"), Long.toString(System.currentTimeMillis()));
                }
                Files.delete(marker);
                pending = null;
            }
        } catch (Exception error) {
            snapshot = new Snapshot(CheckState.UNCHECKED, InstallState.REPAIR_REQUIRED, message(error), null, List.of(),
                0, 0, 0);
        }
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    public void close() {
        worker.shutdownNow();
        http.close();
    }
}
