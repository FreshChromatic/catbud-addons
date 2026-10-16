package github.freshchromatic.catbud_addons.public_warps;

import com.google.gson.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Account and server scoped durable cache; writes replace atomically whenever supported. */
public final class WarpStore {

    public String                         serverId, contributorHash;
    public Map<String, SubmissionReceipt> submissions = new HashMap<>();

    public record SubmissionReceipt(String state, String id, long at, long nextCheck, String reason) {

        public SubmissionReceipt(String state, String id, long at, long nextCheck) {
            this(state, id, at, nextCheck, null);
        }
    }

    static final long                  PREVIEW_CACHE_BYTES   = 24L * 1024 * 1024;
    static final long                  PENDING_PREVIEW_BYTES = 4L * 1024 * 1024;
    public List<WarpData>              warps                 = new ArrayList<>();
    public Map<String, WarpData.Visit> visits                = new HashMap<>();
    public Map<String, String>         previews              = new LinkedHashMap<>();
    public Map<String, String>         previewVersions       = new HashMap<>();
    public Map<String, PreviewReport>  previewReports        = new HashMap<>();

    public record PreviewReport(String digest, long at, int version) {

        public PreviewReport(String digest, long at) {
            this(digest, at, 1);
        }
    }

    public List<Report>             pending             = new ArrayList<>();
    public String                   etag;
    public long                     refreshed;
    public Long                     sharedRevision;
    public Set<String>              sharedIds           = new HashSet<>();
    public Map<String, Long>        discoveries         = new LinkedHashMap<>();
    public Set<String>              rejectedNames       = new HashSet<>();
    public List<AvailabilityReport> availabilityReports = new ArrayList<>();

    public record AvailabilityReport(String uuid, String name, String reason, long created, String submissionId) {

        public AvailabilityReport(String uuid, String name, String reason, long created) {
            this(uuid, name, reason, created, UUID.randomUUID().toString());
        }
    }

    public void queueDiscovery(String name, long now) {
        if (discoveries.size() < 100) {
            discoveries.putIfAbsent(name.toLowerCase(Locale.ROOT), now);
        }
    }

    public void queueAvailability(WarpData warp, String reason, long now) {
        if (warp.uuid == null) {
            return;
        }
        availabilityReports.removeIf(report -> report.uuid().equals(warp.uuid) && report.reason().equals(reason));
        availabilityReports.add(new AvailabilityReport(warp.uuid, warp.name, reason, now));
        while (availabilityReports.size() > 100) {
            availabilityReports.removeFirst();
        }
    }

    public void resolveIdentity(WarpData warp, String uuid) {
        String previousKey = warp.key();
        for (var existing : new ArrayList<>(warps)) {
            if (existing != warp && uuid.equals(existing.uuid)) {
                mergeNameVisits(existing.name, uuid);
                warp.confirmedTeleport |= existing.confirmedTeleport || existing.lastSuccess > 0;
                if (existing.checkedAt > warp.checkedAt) {
                    warp.checkedAt = existing.checkedAt;
                    warp.passState = existing.passState;
                    warp.nextCheck = existing.nextCheck;
                }
                if (Math.max(existing.lastSuccess, existing.lastFailure) > Math.max(warp.lastSuccess,
                    warp.lastFailure)) {
                    warp.lastSuccess = existing.lastSuccess;
                    warp.lastFailure = existing.lastFailure;
                    warp.availability = existing.availability;
                }
                warps.remove(existing);
            }
        }
        warp.uuid = uuid;
        mergeNameVisits(warp.name, uuid);
        String preview = previews.remove(previousKey);
        String version = previewVersions.remove(previousKey);
        if (preview != null) {
            cachePreview(uuid, preview, version);
        }
    }

    public record Report(String uuid, String name, boolean bossbar, boolean actionbar, String snapshot, long created,
        String submissionId) {

        public Report(String uuid, String name, boolean bossbar, boolean actionbar, String snapshot, long created) {
            this(uuid, name, bossbar, actionbar, snapshot, created, UUID.randomUUID().toString());
        }
    }

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public void rejectFirstAttempt(WarpData warp) {
        rejectedNames.add(warp.name.toLowerCase(Locale.ROOT));
        discoveries.remove(warp.name.toLowerCase(Locale.ROOT));
        warps.remove(warp);
    }

    public boolean rejected(String name) {
        return rejectedNames.contains(name.toLowerCase(Locale.ROOT));
    }

    public WarpData remember(String name) {
        rejectedNames.remove(name.toLowerCase(Locale.ROOT));
        for (var warp : warps) {
            if (warp.name.equalsIgnoreCase(name)) {
                warp.locallyDiscovered = true;
                return warp;
            }
        }
        var warp = new WarpData();
        warp.name = name;
        warp.locallyDiscovered = true;
        warps.add(warp);
        return warp;
    }

    public void mergeShared(List<WarpData> incoming) {
        for (var shared : incoming) {
            if (rejected(shared.name) && shared.lastSuccess == 0) {
                continue;
            }
            rejectedNames.remove(shared.name.toLowerCase(Locale.ROOT));
            WarpData local = warps.stream().filter(warp -> Objects.equals(warp.uuid, shared.uuid) || warp.name
                .equalsIgnoreCase(shared.name)).findFirst().orElse(null);
            if (local != null) {
                shared.locallyDiscovered = local.locallyDiscovered;
                shared.confirmedTeleport |= local.confirmedTeleport || local.lastSuccess > 0;
                resolveIdentity(local, shared.uuid);
                if (local.lastFailure > Math.max(shared.lastSuccess, shared.lastFailure)
                    && local.lastFailure > local.lastSuccess) {
                    shared.lastFailure = local.lastFailure;
                    shared.availability = local.availability;
                }
                if (local.lastSuccess > Math.max(shared.lastSuccess, shared.lastFailure)) {
                    shared.lastSuccess = local.lastSuccess;
                    shared.availability = local.availability;
                }
                if (local.checkedAt > shared.checkedAt) {
                    shared.checkedAt = local.checkedAt;
                    shared.passState = local.passState;
                    shared.nextCheck = local.nextCheck;
                }
                warps.remove(local);
            }
            warps.add(shared);
            mergeNameVisits(shared.name, shared.uuid);
        }
    }

    public Set<String> mergeCloud(WarpApiClient.WarpList response) {
        var removed = new HashSet<>(response.deleted());
        var incoming = new HashSet<String>();
        for (var warp : response.warps()) {
            incoming.add(warp.uuid);
        }
        if (response.full()) {
            removed.addAll(sharedIds);
            removed.removeAll(incoming);
        }
        for (String uuid : removed) {
            for (var warp : new ArrayList<>(warps)) {
                if (!uuid.equals(warp.uuid)) {
                    continue;
                }
                boolean personalHistory = warp.locallyDiscovered || (visits.containsKey(uuid) && visits.get(
                    uuid).count > 0) || (previews.containsKey(uuid) && !previewVersions.containsKey(uuid));
                if (personalHistory) {
                    warp.locallyDiscovered = true;
                    warp.snapshotHash = null;
                    warp.displayName = null;
                    warp.description = null;
                    warp.tags.clear();
                    warp.status = "candidate";
                    warp.passState = "unknown";
                    warp.checkedAt = 0;
                    warp.nextCheck = 0;
                } else {
                    warps.remove(warp);
                }
            }
            if (previewVersions.remove(uuid) != null) {
                previews.remove(uuid);
            }
            sharedIds.remove(uuid);
        }
        mergeShared(response.warps());
        sharedIds.addAll(incoming);
        sharedRevision = response.revision();
        return removed;
    }

    public void mergeNameVisits(String name, String uuid) {
        var nameVisit = visits.remove(name.toLowerCase(Locale.ROOT));
        if (nameVisit == null) {
            return;
        }
        var uuidVisit = visits.computeIfAbsent(uuid, key -> new WarpData.Visit());
        uuidVisit.count += nameVisit.count;
        uuidVisit.last = Math.max(uuidVisit.last, nameVisit.last);
    }

    public boolean mayReportPreview(String uuid, BlockSnapshot preview, String digest, long now) {
        var previous = previewReports.get(uuid);
        if (preview == null || previous == null) {
            return true;
        }
        boolean formatUpgrade = previous.version() < preview.version;
        boolean changedAfterCooldown = !Objects.equals(previous.digest(), digest) && now - previous.at() >= 1800000;
        return formatUpgrade || changedAfterCooldown;
    }

    public static WarpStore load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new WarpStore();
        }
        if (Files.size(path) > 32 * 1024 * 1024) {
            throw new IOException("Warp cache too large");
        }
        try (var reader = Files.newBufferedReader(path)) {
            var store = JSON.fromJson(reader, WarpStore.class);
            if (store == null || store.warps == null || store.visits == null || store.previews == null
                || store.pending == null || store.previewVersions == null || store.previewReports == null
                || store.discoveries == null || store.availabilityReports == null || store.sharedIds == null
                || (store.sharedRevision != null && store.sharedRevision < 0)) {
                throw new IOException("Invalid warp cache");
            }
            if (store.submissions == null) {
                store.submissions = new HashMap<>();
            }
            if (store.submissions.size() > 5200) {
                throw new IOException("Too many submission receipts");
            }
            for (var entry : store.submissions.entrySet()) {
                var receipt = entry.getValue();
                if (entry.getKey() == null || receipt == null || receipt.state() == null || receipt.state()
                    .length() > 40 || receipt.at() < 0 || receipt.nextCheck() < 0 || (receipt.reason() != null
                        && receipt.reason().length() > 500)) {
                    throw new IOException("Invalid submission receipt");
                }
                UUID.fromString(entry.getKey());
                if (receipt.id() != null) {
                    UUID.fromString(receipt.id());
                }
            }
            store.pending = new ArrayList<>(store.pending.stream().map(report -> report.submissionId() == null
                ? new Report(report.uuid(), report.name(), report.bossbar(), report.actionbar(), report.snapshot(),
                    report.created()) : report).toList());
            store.availabilityReports = new ArrayList<>(store.availabilityReports.stream().map(report -> report
                .submissionId() == null ? new AvailabilityReport(report.uuid(), report.name(), report.reason(), report
                    .created()) : report).toList());
            if (store.warps.stream().anyMatch(w -> w == null || w.name == null || !w.name.matches("[A-Za-z0-9_]{1,16}")
                || w.tags == null) || store.visits.values().stream().anyMatch(v -> v == null || v.count < 0
                    || v.last < 0) || store.previews.values().stream().anyMatch(Objects::isNull) || store.previewReports
                        .values().stream().anyMatch(r -> r == null || r.digest() == null) || store.pending.stream()
                            .anyMatch(r -> r == null || r.uuid() == null || r.name() == null)) {
                throw new IOException("Invalid warp cache entries");
            }
            if (store.discoveries.size() > 100 || store.sharedIds.size() > 5000 || store.sharedIds.stream().anyMatch(
                id -> id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                || store.availabilityReports.size() > 100 || store.pending.size() > 100 || store.previews.size() > 64
                || store.discoveries.entrySet().stream().anyMatch(entry -> entry.getKey() == null || !entry.getKey()
                    .matches("[a-z0-9_]{1,16}") || entry.getValue() == null || entry.getValue() < 0)
                || store.availabilityReports.stream().anyMatch(report -> report == null || report.uuid() == null
                    || report.name() == null || report.created() < 0 || !Set.of("target_no_pass_or_set",
                        "successful_arrival").contains(report.reason()))) {
                throw new IOException("Invalid pending warp metadata");
            }
            store.pending.removeIf(r -> r.created() < System.currentTimeMillis() - 7 * 86400000L);
            store.previews = new LinkedHashMap<>(store.previews);
            store.trimPreviews();
            store.trimPendingPreviews();
            for (var warp : store.warps) {
                var history = store.visits.get(warp.key());
                warp.confirmedTeleport |= warp.lastSuccess > 0 || (history != null && history.count > 0);
                if (warp.uuid == null || "candidate".equals(warp.status)) {
                    warp.locallyDiscovered = true;
                }
            }
            for (var warp : new ArrayList<>(store.warps)) {
                if (!warp.listed()) {
                    store.rejectFirstAttempt(warp);
                }
            }
            return store;
        } catch (RuntimeException e) {
            throw new IOException("Invalid warp cache", e);
        }
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(temp)) {
            JSON.toJson(this, writer);
        }
        if (Files.size(temp) > 32L * 1024 * 1024) {
            Files.delete(temp);
            throw new IOException("Warp cache too large");
        }
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void arrival(String key, long now) {
        var visit = visits.computeIfAbsent(key, k -> new WarpData.Visit());
        visit.count++;
        visit.last = now;
    }

    public void queue(Report report) {
        if (report.snapshot() == null) {
            var existing = pending.stream().filter(r -> r.uuid().equals(report.uuid()) && r.snapshot() != null)
                .findFirst();
            if (existing.isPresent()) {
                return;
            }
        }
        pending.removeIf(r -> r.uuid().equals(report.uuid()));
        pending.add(report);
        while (pending.size() > 100) {
            pending.removeFirst();
        }
        trimPendingPreviews();
    }

    public void cachePreview(String key, String encoded, String hash) {
        previews.remove(key);
        previews.put(key, encoded);
        if (hash == null) {
            previewVersions.remove(key);
        } else {
            previewVersions.put(key, hash);
        }
        trimPreviews();
    }

    public String cachedPreview(String key) {
        String encoded = previews.remove(key);
        if (encoded != null) {
            previews.put(key, encoded);
        }
        return encoded;
    }

    private void trimPreviews() {
        long bytes = previews.values().stream().mapToLong(String::length).sum();
        while (previews.size() > 64 || bytes > PREVIEW_CACHE_BYTES) {
            String remove = previews.keySet().iterator().next();
            bytes -= previews.get(remove).length();
            previews.remove(remove);
            previewVersions.remove(remove);
        }
    }

    private void trimPendingPreviews() {
        long bytes = pending.stream().filter(report -> report.snapshot() != null).mapToLong(report -> report.snapshot()
            .length()).sum();
        for (int index = 0; index < pending.size() && bytes > PENDING_PREVIEW_BYTES; index++) {
            Report report = pending.get(index);
            if (report.snapshot() != null) {
                bytes -= report.snapshot().length();
                pending.set(index, new Report(report.uuid(), report.name(), report.bossbar(), report.actionbar(), null,
                    report.created()));
            }
        }
    }
}
