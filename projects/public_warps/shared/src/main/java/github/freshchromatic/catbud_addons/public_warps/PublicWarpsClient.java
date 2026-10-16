package github.freshchromatic.catbud_addons.public_warps;

import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import github.freshchromatic.catbud_addons.catbud_core.*;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.Identifier;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public final class PublicWarpsClient implements ClientModInitializer {

    public static final PublicWarpsClient INSTANCE = new PublicWarpsClient();
    private static final Gson             JSON     = new Gson();
    private static final org.slf4j.Logger LOG      = org.slf4j.LoggerFactory.getLogger("Catbud Public Warps");
    private final ExecutorService         io       = Executors.newFixedThreadPool(2, r -> {
                                                       var t = new Thread(r, "Catbud Warps IO");
                                                       t.setDaemon(true);
                                                       return t;
                                                   });
    private final HttpClient              http     = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ExecutorService         cacheIO  = Executors.newSingleThreadExecutor(r -> {
                                                       var t = new Thread(r, "Catbud Warps Cache");
                                                       t.setDaemon(true);
                                                       return t;
                                                   });
    private final WarpApiClient           api      = new WarpApiClient(http);
    private WarpServerScope               serverScope;

    private record CloudRequest(CloudWarpApiClient client, String service, String credential) {}

    private CloudRequest cloudRequest() {
        return new CloudRequest(new CloudWarpApiClient(http, serverScope), service, contributorCredential);
    }

    public String serverLabel() {
        return serverScope == null ? "Catbud" : serverScope.label;
    }

    private boolean                                   cloudService, submissionDisabled;
    private String                                    contributorCredential = "";
    private boolean                                   discoveryBusy, availabilityBusy;
    private long                                      nextDiscovery;
    private final PreviewCache<String, BlockSnapshot> snapshots             = new PreviewCache<>(64, 48L * 1024 * 1024,
        (key, snapshot) -> snapshot.estimatedBytes(), (key, snapshot) -> {
                                                                                });
    private final Map<String, PreviewRequest>         downloading           = new HashMap<>();
    private final Set<PreviewLoad>                    activePreviewLoads    = new HashSet<>();
    private final List<String>                        suggestions           = new ArrayList<>();
    private WarpStore                                 store                 = new WarpStore();
    private Path                                      cache;
    private String                                    session               = "", noticeKey = "warps.ready",
        service = "http://127.0.0.1:8787";
    private long                                      sessionGeneration, nextRefresh, nextRetry, nextMetadataRetry,
        lastManual, suggestionDeadline;
    private boolean                                   refreshBusy, reportBusy, saveBusy, dirty;
    private boolean                                   reducedMotion;

    public boolean reducedMotion() {
        return reducedMotion;
    }

    private int             requestId = 0x6C000000, pendingRequest = -1;
    private int             reportFailures;
    private int             metadataFailures;
    private KeyMapping      openKey;
    private TeleportAttempt attempt;
    private PendingCapture  pendingCapture;

    private record PendingCapture(String target, String key, TeleportAttempt operation, long generation,
        SnapshotCaptureJob job) {}

    private UUID          teleportBoss;
    private TeleportTrace trace;
    private long          traceOperation = System.currentTimeMillis();
    private BlockPos      correctedPosition;
    private String        correctedDimension;
    private long          saveDue;

    public String notice() {
        if (cloudService && ("warps.ready".equals(noticeKey) || "warps.synced".equals(noticeKey))) {
            if (contributorCredential.isEmpty()) {
                return Component.translatable("warps.download_only").getString();
            }
            int queued = store.pending.size() + store.availabilityReports.size() + store.discoveries.size();
            if (queued > 0) {
                return Component.translatable("warps.sync_counts", store.warps.size(), store.sharedIds.size(), queued)
                    .getString();
            }
        }
        return noticeKey.isEmpty() ? "" : Component.translatable(noticeKey).getString();
    }

    public WarpStore store() {
        return store;
    }

    @Override
    public void onInitializeClient() {
        INSTANCE.initialize();
    }

    private void initialize() {
        loadServiceSettings();
        registerControls();
        registerSettingsPanel();
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stop());
    }

    private void loadServiceSettings() {
        Path settingsFile = FabricLoader.getInstance().getConfigDir().resolve("catbud/public-warps-service.json");
        try {
            if (!Files.exists(settingsFile)) {
                Files.createDirectories(settingsFile.getParent());
                Files.writeString(settingsFile,
                    "{\"serviceUrl\":\"https://api.cbwp.workers.dev\",\"protocol\":\"cloud\",\"traceTeleport\":false}\n");
            }
            var settings = JsonParser.parseString(Files.readString(settingsFile)).getAsJsonObject();
            cloudService = settings.has("protocol") && "cloud".equals(settings.get("protocol").getAsString());
            if (settings.has("contributorCredential")) {
                contributorCredential = settings.get("contributorCredential").getAsString();
                if (!contributorCredential.isEmpty() && !contributorCredential.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("Invalid contributor credential format");
                }
            }
            if (settings.has("reducedMotion")) {
                reducedMotion = settings.get("reducedMotion").getAsBoolean();
            }
            URI endpoint = URI.create(settings.get("serviceUrl").getAsString());
            if (!allowedEndpoint(endpoint)) {
                throw new IllegalArgumentException("Use HTTPS or local HTTP");
            }
            service = endpoint.toString().replaceAll("/+$", "");
            if (settings.has("traceTeleport") && settings.get("traceTeleport").getAsBoolean()) {
                trace = new TeleportTrace(settingsFile.resolveSibling("public-warps-teleport.jsonl"));
            }
            trace("enabled", Map.of("format", 1, "minecraftVersion", installedVersion("minecraft"), "modVersion",
                installedVersion("catbud_public_warps")));
        } catch (Exception e) {
            LOG.warn("Cannot load public warps service settings", e);
            noticeKey = "warps.settings_failed";
        }
    }

    private static String installedVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(mod -> mod.getMetadata().getVersion()
            .getFriendlyString()).orElse("unknown");
    }

    private static boolean allowedEndpoint(URI endpoint) {
        if (endpoint.getHost() == null) {
            return false;
        }
        boolean secure = "https".equals(endpoint.getScheme());
        boolean local = Set.of("localhost", "127.0.0.1", "::1").contains(endpoint.getHost());
        boolean localHttp = "http".equals(endpoint.getScheme()) && local;
        return secure || localHttp;
    }

    private void registerControls() {
        openKey = ClientPlatform.registerKey("key.catbud_public_warps.open", InputConstants.KEY_X, Identifier
            .fromNamespaceAndPath("catbud_public_warps", "general"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            var command = LiteralArgumentBuilder.<FabricClientCommandSource>literal("too").executes(context -> {
                open();
                return 1;
            });
            dispatcher.register(command);
        });
    }

    private void registerSettingsPanel() {
        var settingsStore = new SettingsStore(CatbudCore.configPath("public_warps"), List.of(), values -> {
        });
        var status = new SettingsAddon.StatusRow(Component.translatable("warps.title"), () -> Component.translatable(
            "screen.catbud_core.open"), this::open, () -> Minecraft.getInstance().player != null, () -> Component
                .literal("X / /too"));
        var addon = new SettingsAddon("public_warps", Component.translatable("warps.title"), "1.0.3", List.of(
            "general"), List.of(), settingsStore, List::of, category -> List.of(status));
        CatbudCore.register(addon);
    }

    private void stop() {
        persistCurrent();
        cacheIO.shutdown();
        try {
            cacheIO.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        io.shutdownNow();
    }

    public void open() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return;
        }
        updateSession();
        refresh(false);
        requestSuggestions();
        ClientPlatform.show(mc, new WarpScreen());
    }

    public boolean canEditOwnWarp(WarpData warp) {
        var mc = Minecraft.getInstance();
        if (!cloudService || serverScope == null || mc.player == null || warp == null || warp.uuid == null) {
            return false;
        }
        UUID account = mc.getUser().getProfileId();
        return account != null && account.toString().equals(warp.uuid) && account.equals(mc.player.getUUID())
            && store.sharedIds.contains(warp.uuid) && suggestions.stream().anyMatch(name -> name.equalsIgnoreCase(
                warp.name));
    }

    public OwnerEditSession ownerEditSession(WarpData warp) {
        if (!canEditOwnWarp(warp)) {
            throw new IllegalStateException("Own warp is not available for editing");
        }
        var mc = Minecraft.getInstance();
        var account = mc.getUser().getProfileId();
        var scope = serverScope;
        var endpoint = service;
        long generation = sessionGeneration;
        var client = new OwnerWarpApiClient(http, endpoint, scope, account);
        var proof = new OfficialAccountProof(http, account, mc.getUser().getAccessToken());
        return new OwnerEditSession(io, mc::execute, client, proof, new OwnerEditSession.Context(
            () -> generation == sessionGeneration && scope == serverScope && endpoint.equals(service)
                && mc.player != null && account.equals(mc.player.getUUID()) && account.equals(mc.getUser()
                    .getProfileId()), updated -> {
                        store.mergeShared(List.of(updated));
                        markDirty();
                        noticeKey = "warps.owner.saved";
                    }, warp.name));
    }

    private boolean supportedServer() {
        var current = Minecraft.getInstance().getCurrentServer();
        var scope = current == null ? null : WarpServerScope.fromAddress(current.ip);
        return scope != null && (cloudService || scope == WarpServerScope.CATBUD);
    }

    private void updateSession() {
        var mc = Minecraft.getInstance();
        var nextScope = mc.getCurrentServer() == null ? null : WarpServerScope.fromAddress(mc.getCurrentServer().ip);
        String now = mc.player != null && supportedServer() ? mc.player.getUUID() + "@" + nextScope.id : "";
        if (now.equals(session)) {
            return;
        }
        if (attempt != null) {
            trace("session_changed", Map.of());
        }
        persistCurrent();
        dirty = false;
        // Pending cache saves capture their own store/path; never cross account/session boundaries.
        session = now;
        serverScope = nextScope;
        submissionDisabled = false;
        sessionGeneration++;
        if (attempt != null) {
            attempt.cancel();
        }
        attempt = null;
        pendingCapture = null;
        teleportBoss = null;
        pendingRequest = -1;
        snapshots.clear();
        downloading.clear();
        activePreviewLoads.clear();
        suggestions.clear();
        refreshBusy = reportBusy = false;
        discoveryBusy = availabilityBusy = false;
        reportFailures = 0;
        nextRefresh = nextRetry = nextMetadataRetry = 0;
        metadataFailures = 0;
        cache = null;
        store = new WarpStore();
        if (now.isEmpty()) {
            return;
        }
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("catbud/warps");
        cache = ServerWarpCache.path(directory, mc.player.getUUID(), serverScope);
        try {
            store = ServerWarpCache.load(directory, mc.player.getUUID(), serverScope, mc.getCurrentServer().ip);
            repairUploads();
        } catch (Exception e) {
            noticeKey = "warps.cache_failed";
            LOG.warn("Cannot read warp cache; preserving original", e);
            try {
                Files.move(cache, cache.resolveSibling(cache.getFileName() + ".invalid-" + System.currentTimeMillis()));
            } catch (Exception copyFailure) {
                cache = null;
                LOG.warn("Cannot preserve failed cache; saving disabled", copyFailure);
            }
        }
    }

    private void tick() {
        var mc = Minecraft.getInstance();
        updateSession();
        while (openKey.consumeClick()) {
            if (ClientPlatform.screen(mc) == null) {
                open();
            }
        }
        long now = System.currentTimeMillis();
        if (pendingRequest != -1 && now > suggestionDeadline) {
            pendingRequest = -1;
            noticeKey = "warps.suggestions_timeout";
        }
        if (attempt != null) {
            if (mc.player != null && !mc.player.isAlive()) {
                if (!attempt.cancelled()) {
                    trace("death", Map.of());
                }
                attempt.cancel();
            }
            if (!attempt.live(now)) {
                trace("attempt_closed", Map.of("cancelled", attempt.cancelled()));
                attempt = null;
            } else if (mc.level != null && correctedPosition != null && correctedDimension != null && correctedDimension
                .equals(ClientPlatform.dimension(mc).toString()) && attempt.confirmArrival(now, BlockSnapshotCapture
                    .areaLoaded(mc, correctedPosition))) {
                String target = attempt.target;
                TeleportAttempt completed = attempt;
                attempt = null;
                trace("arrival_counted", Map.of("target", target, "dimension", correctedDimension, "position",
                    correctedPosition.toShortString()));
                WarpData warp = store.remember(target);
                warp.successfulArrival(now);
                if (cloudService) {
                    store.queueAvailability(warp, "successful_arrival", now);
                }
                String visitKey = warp.key();
                store.arrival(visitKey, now);
                markDirty();
                try {
                    pendingCapture = new PendingCapture(target, visitKey, completed, sessionGeneration,
                        new SnapshotCaptureJob(mc, correctedPosition));
                } catch (RuntimeException e) {
                    noticeKey = "warps.preview_failed";
                    LOG.warn("Cannot capture arrival preview", e);
                    return;
                }
            }
        }
        advanceCapture(mc);
        if (cloudService && canSubmit() && !session.isEmpty() && now >= nextMetadataRetry) {
            submitCloudMetadata(now);
        }
        if (!session.isEmpty() && !store.pending.isEmpty() && now >= nextRetry && !reportBusy && (!cloudService
            || canSubmit()) && !discoveryBusy && !availabilityBusy) {
            submitPending();
        }
        if (dirty && now >= saveDue && !saveBusy && cache != null) {
            save();
        }
    }

    private void advanceCapture(Minecraft client) {
        PendingCapture capture = pendingCapture;
        if (capture == null) {
            return;
        }
        try {
            if (capture.generation() != sessionGeneration || capture.operation().cancelled()) {
                pendingCapture = null;
                return;
            }
            if (!capture.job().advance(client)) {
                return;
            }
            pendingCapture = null;
            BlockSnapshot preview = capture.job().result();
            io.execute(() -> encodeCapturedPreview(capture, preview));
        } catch (RuntimeException failure) {
            pendingCapture = null;
            noticeKey = "warps.preview_failed";
            LOG.warn("Cannot finish arrival preview", failure);
        }
    }

    private void encodeCapturedPreview(PendingCapture capture, BlockSnapshot preview) {
        try {
            PreviewPayload payload = PreviewPayload.from(preview);
            Minecraft.getInstance().execute(() -> applyCapturedPreview(capture, preview, payload));
        } catch (Exception failure) {
            LOG.warn("Cannot encode arrival preview", failure);
            Minecraft.getInstance().execute(() -> {
                if (capture.generation() == sessionGeneration) {
                    noticeKey = "warps.preview_failed";
                }
            });
        }
    }

    private void applyCapturedPreview(PendingCapture capture, BlockSnapshot preview, PreviewPayload payload) {
        if (capture.generation() != sessionGeneration || capture.operation().cancelled()) {
            return;
        }
        WarpData warp = store.remember(capture.target());
        String key = warp.key();
        store.cachePreview(key, payload.encoded(), null);
        downloading.remove(key);
        snapshots.put(key, preview);
        markDirty();
        qualify(capture.target(), preview, capture.operation());
    }

    public void command(String command) {
        if (!supportedServer()) {
            return;
        }
        // Any later to/go/land/beacon command invalidates ambiguous pending teleport evidence.
        String raw = command.replaceFirst("^/", "");
        String target = TeleportAttempt.target(command);
        TeleportAttempt next = target == null ? null : new TeleportAttempt(target, System.currentTimeMillis());
        boolean reuse = next != null && teleportBoss != null && next.retainLaunchFrom(attempt, System
            .currentTimeMillis());
        if (raw.matches("(?i)^(to|go|land|beacon)(\\s.*)?$")) {
            if (pendingCapture != null) {
                pendingCapture.operation().cancel();
                pendingCapture = null;
            }
            if (attempt != null) {
                trace("superseded", Map.of());
                attempt.cancel();
            }
            attempt = null;
            correctedPosition = null;
            correctedDimension = null;
            if (!reuse) {
                teleportBoss = null;
            }
        }
        if (target != null) {
            traceOperation++;
            attempt = next;
            var mc = Minecraft.getInstance();
            trace("command", mc.player != null && mc.level != null ? Map.of("target", target, "position", mc.player
                .blockPosition().toShortString(), "dimension", ClientPlatform.dimension(mc).toString(),
                "retainedLaunch", reuse) : Map.of("target", target, "retainedLaunch", reuse));
        }
    }

    public void message(Component text, boolean bossbar, boolean actionbar) {
        if (attempt == null) {
            return;
        }
        var keys = new ArrayList<String>();
        translationKeys(text, keys);
        if (!keys.isEmpty()) {
            trace("translated_message", Map.of("source", messageSource(bossbar, actionbar), "keys", List.copyOf(keys)));
        }
        if (contains(text, "commands.to.target_no_pass_or_set") || contains(text,
            "plugins.player_database.canceled_teleport")) {
            if (contains(text, "commands.to.target_no_pass_or_set")) {
                var warp = store.remember(attempt.target);
                warp.failedTeleport(System.currentTimeMillis());
                if (!warp.listed()) {
                    store.rejectFirstAttempt(warp);
                }
                if (cloudService) {
                    store.queueAvailability(warp, "target_no_pass_or_set", System.currentTimeMillis());
                }
                markDirty();
            }
            attempt.cancel();
            noticeKey = "warps.teleport_cancelled";
            return;
        }
        if (contains(text, "plugins.player_database.launch_teleporting")) {
            if (bossbar) {
                attempt.launch(true, System.currentTimeMillis());
            }
            if (actionbar) {
                attempt.launch(false, System.currentTimeMillis());
            }
            if (bossbar) {
                attempt.refreshRetainedLaunch(System.currentTimeMillis());
            }
            if (attempt.qualified(System.currentTimeMillis())) {
                var warp = store.remember(attempt.target);
                boolean changed = !warp.confirmedTeleport || "unavailable".equals(warp.availability);
                warp.confirmTeleport();
                if (changed && warp.uuid != null) {
                    store.queueAvailability(warp, "valid_launch", System.currentTimeMillis());
                }
                markDirty();
            }
        }
    }

    public void boss(UUID id, Component text) {
        if (attempt != null && contains(text, "plugins.player_database.launch_teleporting")) {
            teleportBoss = id;
        }
        message(text, true, false);
    }

    public void bossRemoved(UUID id) {
        if (attempt != null && id.equals(teleportBoss)) {
            trace("boss_removed", Map.of());
            attempt.bossEnded(System.currentTimeMillis());
        }
        if (id.equals(teleportBoss)) {
            teleportBoss = null;
        }
    }

    public void bossProgress(UUID id, float progress) {
        if (attempt == null) {
            return;
        }
        boolean matching = id.equals(teleportBoss);
        trace("boss_progress", Map.of("matching", matching, "progress", progress));
        if (matching) {
            if (attempt.refreshRetainedLaunch(System.currentTimeMillis())) {
                trace("retained_launch_refreshed", Map.of());
            }
        }
    }

    private static void translationKeys(Component text, List<String> keys) {
        if (keys.size() >= 64) {
            return;
        }
        if (text.getContents() instanceof TranslatableContents t) {
            keys.add(t.getKey());
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component nested) {
                    translationKeys(nested, keys);
                }
            }
        }
        for (Component child : text.getSiblings()) {
            translationKeys(child, keys);
        }
    }

    private void trace(String event, Map<String, ?> details) {
        if (trace == null) {
            return;
        }
        long operation = traceOperation, at = System.currentTimeMillis();
        cacheIO.execute(() -> {
            try {
                trace.append(at, operation, event, details);
            } catch (Exception e) {
                LOG.debug("Cannot write local teleport trace", e);
            }
        });
    }

    private static String messageSource(boolean bossbar, boolean actionbar) {
        if (bossbar) {
            return "bossbar";
        }
        return actionbar ? "actionbar" : "chat";
    }

    private static boolean contains(Component c, String key) {
        if (c.getContents() instanceof TranslatableContents t) {
            if (t.getKey().equals(key)) {
                return true;
            }
            for (Object a : t.getArgs()) {
                if (a instanceof Component nested && contains(nested, key)) {
                    return true;
                }
            }
        }
        for (Component s : c.getSiblings()) {
            if (contains(s, key)) {
                return true;
            }
        }
        return false;
    }

    public void correction() {
        var mc = Minecraft.getInstance();
        if (attempt == null || mc.player == null || mc.level == null) {
            return;
        }
        boolean qualified = attempt.qualified(System.currentTimeMillis());
        trace("position_correction", Map.of("qualified", qualified, "dimension", ClientPlatform.dimension(mc)
            .toString(), "position", mc.player.blockPosition().toShortString()));
        if (!qualified) {
            return;
        }
        attempt.positionCorrection(System.currentTimeMillis());
        correctedPosition = mc.player.blockPosition();
        correctedDimension = ClientPlatform.dimension(mc).toString();
    }

    public void teleport(WarpData w) {
        sendTeleport(w);
    }

    public boolean canRetryTeleport(WarpData warp) {
        return !warp.canTeleport();
    }

    public void retryTeleport(WarpData warp) {
        if (canRetryTeleport(warp)) {
            sendTeleport(warp);
        }
    }

    private void sendTeleport(WarpData w) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null || !supportedServer()) {
            return;
        }
        mc.getConnection().sendCommand("to by " + w.name);
        ClientPlatform.show(mc, null);
    }

    private WarpData find(String name) {
        return store.warps.stream().filter(w -> w.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public List<WarpData> warps() {
        Map<String, WarpData> all = new LinkedHashMap<>();
        for (WarpData w : store.warps) {
            all.put(w.name.toLowerCase(Locale.ROOT), w);
        }
        for (String s : suggestions) {
            if (store.rejected(s)) {
                continue;
            }
            all.computeIfAbsent(s.toLowerCase(Locale.ROOT), k -> {
                var w = new WarpData();
                w.name = s;
                return w;
            });
        }
        return all.values().stream().filter(WarpData::listed).sorted(Comparator.comparing(w -> w.name.toLowerCase(
            Locale.ROOT))).toList();
    }

    public List<WarpData> frequent() {
        return warps().stream().filter(w -> w.canTeleport() && visits(w).count > 0).sorted(Comparator
            .<WarpData>comparingLong(w -> visits(w).count).reversed().thenComparing(Comparator.<WarpData>comparingLong(
                w -> visits(w).last).reversed())).limit(3).toList();
    }

    public WarpData.Visit visits(WarpData w) {
        return store.visits.getOrDefault(w.key(), new WarpData.Visit());
    }

    public void requestSuggestions() {
        var mc = Minecraft.getInstance();
        if (!supportedServer() || pendingRequest != -1 || mc.getConnection() == null) {
            return;
        }
        pendingRequest = ++requestId;
        suggestionDeadline = System.currentTimeMillis() + 5000;
        mc.getConnection().send(new ServerboundCommandSuggestionPacket(pendingRequest, "/to by "));
    }

    public boolean suggestions(ClientboundCommandSuggestionsPacket packet) {
        if (packet.id() != pendingRequest) {
            return false;
        }
        pendingRequest = -1;
        suggestions.clear();
        var node = Minecraft.getInstance().getConnection().getCommands().getRoot().getChild("to");
        if (node != null) {
            node = node.getChild("by");
        }
        var commandNode = node;
        for (var s : packet.toSuggestions().getList()) {
            String value = s.getText();
            boolean literal = commandNode != null && commandNode.getChild(
                value) instanceof com.mojang.brigadier.tree.LiteralCommandNode<?>;
            if (!literal && value.matches("[A-Za-z0-9_]{1,16}") && !value.equalsIgnoreCase("set")) {
                suggestions.add(value);
                if (store.rejected(value)) {
                    continue;
                }
                boolean newlyRecorded = find(value) == null;
                store.remember(value);
                if (newlyRecorded && cloudService) {
                    store.queueDiscovery(value, System.currentTimeMillis());
                    if (nextDiscovery == 0) {
                        nextDiscovery = System.currentTimeMillis() + 15000 + java.util.concurrent.ThreadLocalRandom
                            .current().nextInt(45001);
                    }
                }
            }
        }
        trace("suggestions", Map.of("received", packet.toSuggestions().getList().size(), "accepted", suggestions.size(),
            "names", suggestions.stream().limit(256).toList()));
        noticeKey = "";
        markDirty();
        return true;
    }

    public void refresh(boolean manual) {
        long now = System.currentTimeMillis();
        boolean throttled = manual ? now - lastManual < 30000 : now < nextRefresh;
        if (refreshBusy || session.isEmpty() || throttled) {
            return;
        }
        if (manual) {
            lastManual = now;
            downloading.clear();
        }
        nextRefresh = now + 300000;
        refreshBusy = true;
        long generation = sessionGeneration;
        String etag = store.etag;
        Long revision = store.sharedRevision;
        var cloud = cloudService ? cloudRequest() : null;
        io.execute(() -> fetchWarpList(generation, etag, revision, cloud));
    }

    private void fetchWarpList(long generation, String etag, Long revision, CloudRequest cloud) {
        try {
            var response = cloudService ? cloud.client().fetchWarps(cloud.service(), etag, revision)
                : api.fetchWarps(service, etag);
            Minecraft.getInstance().execute(() -> applyWarpList(generation, response));
        } catch (Exception failure) {
            Minecraft.getInstance().execute(() -> failWarpList(generation));
        }
    }

    private void applyWarpList(long generation, WarpApiClient.WarpList response) {
        if (generation != sessionGeneration) {
            return;
        }
        refreshBusy = false;
        if (response.changed()) {
            if (cloudService) {
                for (String removed : store.mergeCloud(response)) {
                    snapshots.remove(removed);
                    downloading.remove(removed);
                }
            } else {
                store.mergeShared(response.warps());
            }
            store.etag = response.etag();
        }
        store.refreshed = System.currentTimeMillis();
        noticeKey = "warps.synced";
        markDirty();
    }

    private void failWarpList(long generation) {
        if (generation == sessionGeneration) {
            refreshBusy = false;
            noticeKey = "warps.offline";
        }
    }

    private record QualificationRequest(String target, String knownUuid, BlockSnapshot preview,
        TeleportAttempt operation, long generation) {}

    private void qualify(String target, BlockSnapshot preview, TeleportAttempt operation) {
        var info = Minecraft.getInstance().getConnection().getPlayerInfo(target);
        String knownUuid = info == null ? null : info.getProfile().id().toString();
        var request = new QualificationRequest(target, knownUuid, preview, operation, sessionGeneration);
        io.execute(() -> verifyQualification(request));
    }

    private void verifyQualification(QualificationRequest request) {
        try {
            var player = cloudService ? api.resolvePlayer(request.target(), request.knownUuid())
                : api.verifyPlayer(request.target(), request.knownUuid());
            var payload = PreviewPayload.from(request.preview(), cloudService);
            Minecraft.getInstance().execute(() -> applyQualification(request, player, payload));
        } catch (Exception failure) {
            LOG.debug("Warp qualification failed for {}", request.target(), failure);
            Minecraft.getInstance().execute(() -> failQualification(request, failure));
        }
    }

    private void failQualification(QualificationRequest request, Exception failure) {
        if (request.generation() == sessionGeneration) {
            if (failure instanceof WarpApiClient.InactivePassException inactive) {
                var warp = store.remember(request.target());
                warp.uuid = inactive.uuid;
                warp.passState = "inactive";
                warp.checkedAt = System.currentTimeMillis();
                warp.nextCheck = warp.checkedAt + 3600000;
                markDirty();
            }
            noticeKey = "warps.verification_failed";
        }
    }

    private void applyQualification(QualificationRequest request, WarpApiClient.VerifiedPlayer player, PreviewPayload payload) {
        if (request.generation() != sessionGeneration || request.operation().cancelled()) {
            return;
        }
        String target = request.target();
        String id = player.uuid();
        WarpData warp = find(target);
        if (warp == null) {
            warp = new WarpData();
            warp.name = target;
            store.warps.add(warp);
        }
        store.resolveIdentity(warp, id);
        if (!cloudService) {
            warp.passState = "active";
            warp.checkedAt = System.currentTimeMillis();
            warp.nextCheck = warp.checkedAt + 3600000;
            warp.status = "verified_report";
            warp.verifiedAt = System.currentTimeMillis();
        }
        BlockSnapshot preview = request.preview();
        if (preview != null) {
            store.cachePreview(id, payload.encoded(), null);
            downloading.remove(id);
            snapshots.put(id, preview);
        }
        long now = System.currentTimeMillis();
        if (store.mayReportPreview(id, preview, payload.digest(), now)) {
            store.queue(new WarpStore.Report(id, target, true, true, payload.reportEncoded(), now));
            if (preview != null) {
                store.previewReports.put(id, new WarpStore.PreviewReport(payload.digest(), now, preview.version));
            }
        }
        markDirty();
        noticeKey = preview == null ? "warps.verified" : "warps.preview_saved";
    }

    private record PreviewLoad(String key, PreviewRequest source, String downloadedVersion, long generation,
        CloudRequest cloud) {}

    public BlockSnapshot preview(WarpData warp) {
        String key = warp.key();
        store.cachedPreview(key);
        String downloadedVersion = store.previewVersions.get(key);
        boolean outdatedSharedPreview = downloadedVersion != null && !Objects.equals(downloadedVersion,
            warp.snapshotHash);
        if (outdatedSharedPreview) {
            snapshots.remove(key);
            store.previews.remove(key);
            store.previewVersions.remove(key);
            downloading.remove(key);
        }
        BlockSnapshot cached = snapshots.get(key);
        if (cached != null) {
            return cached;
        }
        String local = store.previews.get(key);
        var pending = downloading.get(key);
        if (pending != null && pending.matches(local, warp.snapshotHash)) {
            return null;
        }
        downloading.remove(key);
        boolean validSharedHash = warp.snapshotHash != null && warp.snapshotHash.matches("[0-9a-f]{64}");
        if (local == null && !validSharedHash) {
            return null;
        }
        if (activePreviewLoads.size() >= 2) {
            return null;
        }
        var source = new PreviewRequest(local, warp.snapshotHash);
        downloading.put(key, source);
        var load = new PreviewLoad(key, source, downloadedVersion, sessionGeneration, cloudService ? cloudRequest()
            : null);
        activePreviewLoads.add(load);
        io.execute(() -> loadPreview(load));
        return null;
    }

    private void loadPreview(PreviewLoad load) {
        try {
            String local = load.source().local();
            byte[] bytes;
            if (local != null) {
                bytes = Base64.getDecoder().decode(local);
            } else if (load.cloud() != null) {
                bytes = load.cloud().client().fetchPreview(load.cloud().service(), load.source().hash());
            } else {
                bytes = api.fetchPreview(service, load.source().hash());
            }
            BlockSnapshot snapshot = BlockSnapshot.decode(bytes);
            String encoded = Base64.getEncoder().encodeToString(bytes);
            Minecraft.getInstance().execute(() -> applyPreview(load, snapshot, encoded));
        } catch (Exception failure) {
            Minecraft.getInstance().execute(() -> failPreview(load));
        }
    }

    private boolean isCurrentPreviewLoad(PreviewLoad load) {
        return load.generation() == sessionGeneration && downloading.get(load.key()) == load.source();
    }

    private void applyPreview(PreviewLoad load, BlockSnapshot snapshot, String encoded) {
        activePreviewLoads.remove(load);
        if (!isCurrentPreviewLoad(load)) {
            return;
        }
        String key = load.key();
        String currentHash = store.warps.stream().filter(warp -> warp.key().equals(key)).map(warp -> warp.snapshotHash)
            .filter(Objects::nonNull).findFirst().orElse(null);
        if (!load.source().matches(store.previews.get(key), currentHash)) {
            downloading.remove(key);
            return;
        }
        snapshots.put(key, snapshot);
        String version = load.source().local() == null ? load.source().hash() : load.downloadedVersion();
        store.cachePreview(key, encoded, version);
        downloading.remove(key);
        markDirty();
    }

    private void failPreview(PreviewLoad load) {
        activePreviewLoads.remove(load);
        if (!isCurrentPreviewLoad(load)) {
            return;
        }
        noticeKey = "warps.preview_failed";
        String local = load.source().local();
        if (local != null && Objects.equals(store.previews.get(load.key()), local)) {
            store.previews.remove(load.key());
            store.previewVersions.remove(load.key());
            downloading.remove(load.key());
            markDirty();
        }
        // Failed downloads retain their marker so rendering cannot trigger frame-rate retries.
    }

    private void submitPending() {
        long now = System.currentTimeMillis();
        if (store.pending.removeIf(report -> report.created() < now - 7 * 86400000L)) {
            markDirty();
        }
        if (store.pending.isEmpty()) {
            return;
        }
        reportBusy = true;
        nextRetry = now + 60000;
        WarpStore.Report report = store.pending.getFirst();
        long generation = sessionGeneration;
        var cloud = cloudService ? cloudRequest() : null;
        io.execute(() -> sendReport(report, generation, cloud));
    }

    private boolean pollSubmission(long now) {
        for (var item : store.submissions.entrySet()) {
            var receipt = item.getValue();
            if (!"pending_review".equals(receipt.state()) || receipt.id() == null || receipt.nextCheck() > now) {
                continue;
            }
            String uuid = item.getKey();
            long generation = sessionGeneration;
            var cloud = cloudRequest();
            availabilityBusy = true;
            io.execute(() -> {
                WarpApiClient.ReportResult result;
                try {
                    result = cloud.client().submission(cloud.service(), cloud.credential(), receipt.id());
                } catch (Exception failure) {
                    result = new WarpApiClient.ReportResult(503, null);
                }
                var response = result;
                Minecraft.getInstance().execute(() -> {
                    if (generation != sessionGeneration) {
                        return;
                    }
                    availabilityBusy = false;
                    if (stopForCredential(response) || store.submissions.get(uuid) != receipt) {
                        return;
                    }
                    long checked = System.currentTimeMillis();
                    String nextState = response.state() == null ? receipt.state() : response.state();
                    if (response.status() == 404) {
                        nextState = "expired";
                    }
                    long delay = response.status() == 200 ? 3600000 : 300000;
                    store.submissions.put(uuid, new WarpStore.SubmissionReceipt(nextState, receipt.id(), checked,
                        checked + delay, response.reason() == null ? receipt.reason() : response.reason()));
                    markDirty();
                });
            });
            return true;
        }
        return false;
    }

    public String submissionLabel(WarpData warp) {
        var receipt = warp.uuid == null ? null : store.submissions.get(warp.uuid);
        if (receipt == null) {
            return "";
        }
        String key = switch (receipt.state()) {
            case "published" -> "warps.published";
            case "pending_review" -> "warps.pending_review";
            case "local_only" -> "warps.preview_local_only";
            case "rejected", "rejected_first_attempt" -> "warps.rejected";
            case "expired" -> "warps.submission_expired";
            default -> "warps.verification_failed";
        };
        String label = Component.translatable(key).getString();
        if (receipt.reason() != null && !receipt.reason().isBlank()) {
            return label + ": " + receipt.reason();
        }
        return label;
    }

    private void rememberSubmission(String uuid, WarpApiClient.ReportResult response) {
        String state = response.state();
        if (state == null) {
            state = response.status() == 413 ? "local_only" : "blocked";
        }
        long now = System.currentTimeMillis();
        store.submissions.put(uuid, new WarpStore.SubmissionReceipt(state, response.submissionId(), now, now + 3600000,
            response.reason()));
    }

    private void repairUploads() {
        if (!cloudService || !canSubmit()) {
            return;
        }
        String credentialHash = UUID.nameUUIDFromBytes(contributorCredential.getBytes(
            java.nio.charset.StandardCharsets.UTF_8)).toString();
        boolean newCredential = !credentialHash.equals(store.contributorHash);
        store.contributorHash = credentialHash;
        for (var warp : store.warps) {
            if (newCredential && warp.locallyDiscovered) {
                store.queueDiscovery(warp.name, System.currentTimeMillis());
            }
            if (warp.uuid == null || !warp.confirmedTeleport || store.submissions.containsKey(warp.uuid)
                || store.pending.stream().anyMatch(report -> report.uuid().equals(warp.uuid))
                || store.availabilityReports.stream().anyMatch(report -> report.uuid().equals(warp.uuid))) {
                continue;
            }
            String preview = store.previews.get(warp.key());
            if (warp.lastSuccess > 0 && preview != null) {
                store.queue(new WarpStore.Report(warp.uuid, warp.name, true, true, preview, System
                    .currentTimeMillis()));
            } else {
                store.queueAvailability(warp, warp.lastSuccess > 0 ? "successful_arrival" : "valid_launch", System
                    .currentTimeMillis());
            }
        }
        markDirty();
    }

    private boolean canSubmit() {
        return !contributorCredential.isEmpty() && !submissionDisabled;
    }

    private record DiscoveryTarget(String name, String uuid, long created) {}

    private record DiscoveryWork(List<DiscoveryTarget> targets, long generation, CloudRequest cloud) {}

    private void submitCloudMetadata(long now) {
        if (discoveryBusy || availabilityBusy || reportBusy) {
            return;
        }
        boolean expired = store.discoveries.values().removeIf(created -> created < now - 604800000);
        expired |= store.availabilityReports.removeIf(report -> report.created() < now - 604800000);
        if (expired) {
            markDirty();
        }
        if (pollSubmission(now)) {
            return;
        }
        if (!store.availabilityReports.isEmpty()) {
            var report = store.availabilityReports.getFirst();
            availabilityBusy = true;
            long generation = sessionGeneration;
            var cloud = cloudRequest();
            io.execute(() -> sendAvailability(report, generation, cloud));
            return;
        }
        if (store.discoveries.isEmpty() || now < nextDiscovery) {
            return;
        }
        var targets = new ArrayList<DiscoveryTarget>();
        for (var item : store.discoveries.entrySet()) {
            var warp = find(item.getKey());
            if (warp != null) {
                targets.add(new DiscoveryTarget(warp.name, warp.uuid, item.getValue()));
            }
            if (targets.size() == 8) {
                break;
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        discoveryBusy = true;
        var work = new DiscoveryWork(List.copyOf(targets), sessionGeneration, cloudRequest());
        io.execute(() -> sendDiscoveries(work));
    }

    private void sendDiscoveries(DiscoveryWork work) {
        try {
            var players = new ArrayList<CloudWarpApiClient.Player>();
            for (var target : work.targets()) {
                try {
                    var identity = api.resolvePlayer(target.name(), target.uuid());
                    players.add(new CloudWarpApiClient.Player(identity.uuid(), identity.name()));
                } catch (Exception unresolved) {
                    // One unresolved name must not block the remaining discovered players.
                }
            }
            if (players.isEmpty()) {
                Minecraft.getInstance().execute(() -> failCloudMetadata(work.generation(), true));
                return;
            }
            var response = work.cloud().client().discover(work.cloud().service(), work.cloud().credential(),
                new CloudWarpApiClient.Discoveries(players));
            Minecraft.getInstance().execute(() -> applyDiscoveries(work, players, response));
        } catch (Exception failure) {
            Minecraft.getInstance().execute(() -> failCloudMetadata(work.generation(), true));
        }
    }

    private void applyDiscoveries(DiscoveryWork work, List<CloudWarpApiClient.Player> players, WarpApiClient.ReportResult response) {
        if (work.generation() != sessionGeneration) {
            return;
        }
        discoveryBusy = false;
        if (stopForCredential(response)) {
            return;
        }
        if (response.terminal()) {
            for (var player : players) {
                if (store.rejected(player.name())) {
                    continue;
                }
                var warp = store.remember(player.name());
                store.resolveIdentity(warp, player.uuid());
                store.discoveries.remove(player.name().toLowerCase(Locale.ROOT));
            }
            repairUploads();
            markDirty();
            nextDiscovery = System.currentTimeMillis() + 20000;
            metadataFailures = 0;
            nextMetadataRetry = nextDiscovery;
        } else {
            nextMetadataRetry = System.currentTimeMillis() + RetryDelay.milliseconds(++metadataFailures, response
                .retryAfter(), System.currentTimeMillis());
        }
    }

    private void sendAvailability(WarpStore.AvailabilityReport report, long generation, CloudRequest cloud) {
        try {
            var response = cloud.client().availability(cloud.service(), cloud.credential(),
                new CloudWarpApiClient.Availability(report.uuid(), report.name(), report.reason(), report
                    .submissionId()));
            Minecraft.getInstance().execute(() -> applyAvailability(report, generation, response));
        } catch (Exception failure) {
            Minecraft.getInstance().execute(() -> failCloudMetadata(generation, false));
        }
    }

    private void applyAvailability(WarpStore.AvailabilityReport report, long generation, WarpApiClient.ReportResult response) {
        if (generation != sessionGeneration) {
            return;
        }
        availabilityBusy = false;
        if (stopForCredential(response)) {
            return;
        }
        if (response.terminal()) {
            rememberSubmission(report.uuid(), response);
            store.availabilityReports.remove(report);
            markDirty();
            metadataFailures = 0;
            nextMetadataRetry = System.currentTimeMillis() + 1000;
        } else {
            nextMetadataRetry = System.currentTimeMillis() + RetryDelay.milliseconds(++metadataFailures, response
                .retryAfter(), System.currentTimeMillis());
        }
    }

    private void failCloudMetadata(long generation, boolean discovery) {
        if (generation != sessionGeneration) {
            return;
        }
        if (discovery) {
            discoveryBusy = false;
        } else {
            availabilityBusy = false;
        }
        nextMetadataRetry = System.currentTimeMillis() + RetryDelay.milliseconds(++metadataFailures, null, System
            .currentTimeMillis());
        noticeKey = "warps.upload_pending";
    }

    private boolean stopForCredential(WarpApiClient.ReportResult response) {
        if (response.status() != 401 && response.status() != 403) {
            return false;
        }
        submissionDisabled = true;
        noticeKey = "warps.credential_failed";
        return true;
    }

    private void sendReport(WarpStore.Report report, long generation, CloudRequest cloud) {
        try {
            var response = cloudService ? cloud.client().upload(cloud.service(), cloud.credential(), report)
                : api.submitReport(service, report);
            Minecraft.getInstance().execute(() -> applyReport(report, generation, response));
        } catch (Exception failure) {
            Minecraft.getInstance().execute(() -> failReport(report, generation));
        }
    }

    private void applyReport(WarpStore.Report report, long generation, WarpApiClient.ReportResult response) {
        if (generation != sessionGeneration) {
            return;
        }
        reportBusy = false;
        if (stopForCredential(response)) {
            return;
        }
        if (response.terminal()) {
            rememberSubmission(report.uuid(), response);
            store.pending.remove(report);
            markDirty();
            reportFailures = 0;
            nextRetry = System.currentTimeMillis() + 1000;
            if ("published".equals(response.state())) {
                noticeKey = "warps.published";
            } else if ("pending_review".equals(response.state())) {
                noticeKey = "warps.pending_review";
            } else if (response.status() == 413) {
                noticeKey = "warps.preview_local_only";
            } else if (response.status() == 200 || response.status() == 202) {
                noticeKey = "warps.uploaded";
            } else {
                noticeKey = "warps.verification_failed";
            }
        } else {
            retryReport(report, response.retryAfter());
        }
    }

    private void failReport(WarpStore.Report report, long generation) {
        if (generation == sessionGeneration) {
            reportBusy = false;
            retryReport(report, null);
        }
    }

    private void retryReport(WarpStore.Report report, String retryAfter) {
        reportFailures = Math.min(6, reportFailures + 1);
        nextRetry = System.currentTimeMillis() + RetryDelay.milliseconds(reportFailures, retryAfter, System
            .currentTimeMillis());
        // A transient failure for one target must not permanently hide the rest of the queue.
        if (store.pending.remove(report)) {
            store.pending.add(report);
            markDirty();
        }
        noticeKey = "warps.upload_pending";
    }

    private void persistCurrent() {
        if (cache == null) {
            return;
        }
        var copy = JSON.fromJson(JSON.toJson(store), WarpStore.class);
        Path path = cache;
        cacheIO.execute(() -> {
            try {
                copy.save(path);
            } catch (Exception e) {
                LOG.warn("Cannot flush warp cache", e);
            }
        });
    }

    private void markDirty() {
        dirty = true;
        saveDue = System.currentTimeMillis() + 1000;
    }

    private void save() {
        dirty = false;
        saveBusy = true;
        // Freeze mutable data on the client thread before background IO.
        var copy = JSON.fromJson(JSON.toJson(store), WarpStore.class);
        Path path = cache;
        long generation = sessionGeneration;
        cacheIO.execute(() -> {
            try {
                copy.save(path);
            } catch (Exception e) {
                LOG.warn("Cannot save warp cache", e);
                Minecraft.getInstance().execute(() -> {
                    if (generation == sessionGeneration) {
                        dirty = true;
                        saveDue = System.currentTimeMillis() + 10000;
                    }
                });
            } finally {
                Minecraft.getInstance().execute(() -> saveBusy = false);
            }
        });
    }
}
