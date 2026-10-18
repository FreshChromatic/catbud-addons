package github.freshchromatic.catbud_addons.catbud_core;

import com.google.gson.JsonParser;
import github.freshchromatic.catbud_addons.update.AddonModule;
import github.freshchromatic.catbud_addons.update.InstalledAddonInventory;
import github.freshchromatic.catbud_addons.update.ManifestVerifier;
import github.freshchromatic.catbud_addons.update.NotificationStateStore;
import github.freshchromatic.catbud_addons.update.ReleaseCatalogClient;
import github.freshchromatic.catbud_addons.update.ReleaseHttpClient;
import github.freshchromatic.catbud_addons.update.UpdateCoordinator;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class AddonUpdateClient implements AutoCloseable {

    private final UpdateCoordinator                                              coordinator;
    private final Set<String>                                                    loadedIds;
    private final github.freshchromatic.catbud_addons.update.LauncherIntegration launcher;
    private String                                                               initializationError = "";

    public AddonUpdateClient() {
        FabricLoader loader = FabricLoader.getInstance();
        loadedIds = new HashSet<>();
        Set<String> modules = new HashSet<>();
        for (AddonModule module : AddonModule.values()) {
            if (loader.isModLoaded(module.modId())) {
                loadedIds.add(module.modId());
                modules.add(module.id());
            }
        }
        Path config = loader.getConfigDir().resolve("catbud");
        github.freshchromatic.catbud_addons.update.LauncherIntegration integration;
        try {
            integration = new github.freshchromatic.catbud_addons.update.LauncherIntegration(loader.getGameDir(),
                config, installerResource());
        } catch (IOException failed) {
            integration = null;
            initializationError = "Launcher update guard could not be initialized";
        }
        launcher = integration;
        String minecraft = version("minecraft");
        var http = new ReleaseHttpClient();
        var catalog = new ReleaseCatalogClient(http, new ManifestVerifier(signingKeys()), minecraft, config.resolve(
            "release-catalog.json"));
        coordinator = new UpdateCoordinator(http, catalog, new NotificationStateStore(config.resolve(
            "notifications.json")), this::inventory, new github.freshchromatic.catbud_addons.update.UpdateEnvironment(
                minecraft, version("fabricloader"), config.resolve("updates"), modules, guarded()),
            AddonUpdateClient::installerResource);
    }

    private static byte[] installerResource() throws IOException {
        try (var input = AddonUpdateClient.class.getResourceAsStream("/catbud/update-installer.jar")) {
            if (input == null) {
                throw new IOException("Bundled update installer is missing");
            }
            return input.readAllBytes();
        }
    }

    public boolean guarded() {
        return launcher != null && launcher.guarded();
    }

    public String launcherCommand() {
        return launcher == null ? "" : launcher.command();
    }

    private HashMap<String, PublicKey> signingKeys() {
        var keys = new HashMap<String, PublicKey>();
        try (var input = AddonUpdateClient.class.getResourceAsStream("/assets/catbud_core/release-keys.json")) {
            if (input == null) {
                throw new IOException("Release signing keys are missing");
            }
            var object = JsonParser.parseString(new String(input.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (var key : object.entrySet()) {
                keys.put(key.getKey(), ManifestVerifier.publicKey(key.getValue().getAsString()));
            }
        } catch (Exception error) {
            initializationError = "Release signing keys could not be loaded";
        }
        return keys;
    }

    private InstalledAddonInventory inventory() throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isDevelopmentEnvironment()) {
            throw new IllegalArgumentException("Development environments require manual updates");
        }
        Path requestedMods = loader.getGameDir().resolve("mods").toAbsolutePath().normalize();
        github.freshchromatic.catbud_addons.update.install.InstallationPlan.requireRealDirectory(requestedMods);
        Path mods = requestedMods.toRealPath();
        var inventory = InstalledAddonInventory.scan(mods, loadedIds.stream().sorted().toList());
        for (String id : loadedIds) {
            var container = loader.getModContainer(id).orElseThrow();
            while (container.getContainingMod().isPresent()) {
                container = container.getContainingMod().orElseThrow();
            }
            for (Path origin : container.getOrigin().getPaths()) {
                if (!Files.isRegularFile(origin) || Files.isSymbolicLink(origin) || inventory.jars().stream().noneMatch(
                    jar -> jar.path().equals(origin.toAbsolutePath().normalize()))) {
                    throw new IllegalArgumentException("Loaded addon origin requires a manual update: " + id);
                }
            }
        }
        var rules = new java.util.ArrayList<InstalledAddonInventory.CompatibilityRule>();
        for (var mod : loader.getAllMods()) {
            for (var dependency : mod.getMetadata().getDependencies()) {
                if (AddonModule.forMod(dependency.getModId()).isEmpty()) {
                    continue;
                }
                var kind = dependency.getKind();
                if (kind != net.fabricmc.loader.api.metadata.ModDependency.Kind.DEPENDS
                    && kind != net.fabricmc.loader.api.metadata.ModDependency.Kind.BREAKS) {
                    continue;
                }
                rules.add(new InstalledAddonInventory.CompatibilityRule(mod.getMetadata().getId(), dependency
                    .getModId(), kind == net.fabricmc.loader.api.metadata.ModDependency.Kind.DEPENDS, candidate -> {
                        try {
                            return dependency.matches(net.fabricmc.loader.api.Version.parse(candidate));
                        } catch (net.fabricmc.loader.api.VersionParsingException error) {
                            return false;
                        }
                    }));
            }
        }
        return new InstalledAddonInventory(inventory.mods(), inventory.jars(), inventory.problems(), rules);
    }

    public static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(mod -> mod.getMetadata().getVersion()
            .getFriendlyString()).orElse("unknown");
    }

    public UpdateCoordinator coordinator() {
        return coordinator;
    }

    public String initializationError() {
        return initializationError;
    }

    public void tick(Minecraft client) {
        String server = server(client);
        coordinator.tick(server);
        if (client.player == null || server.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        var state = coordinator.notifications();
        var delivery = github.freshchromatic.catbud_addons.update.NotificationPolicy.select(coordinator.snapshot(),
            coordinator.notices(), state, now);
        if (!delivery.updates().isEmpty()) {
            ClientPlatform.updateMessage(client, Component.translatable("updates.core.chat_group", delivery.updates()
                .size(), delivery.updates().getFirst().version(), CatbudCore.openKeyName()));
        }
        if (!delivery.notices().isEmpty()) {
            ClientPlatform.updateMessage(client, Component.translatable("notices.core.chat_group", delivery.notices()
                .size(), CatbudCore.openKeyName()));
            if (state.toasts()) {
                delivery.notices().stream().filter(notice -> notice.severity().equals("important")).findFirst()
                    .ifPresent(notice -> ClientPlatform.updateToast(client, Component.literal(notice.title()), Component
                        .translatable("category.catbud_core.notifications")));
            }
        }
        if (!delivery.installationId().isEmpty()) {
            ClientPlatform.updateMessage(client, Component.translatable("updates.core.download_ready", CatbudCore
                .openKeyName()));
        }
        github.freshchromatic.catbud_addons.update.NotificationPolicy.delivered(delivery, state, now);
    }

    private static String server(Minecraft client) {
        var data = client.getCurrentServer();
        if (data == null) {
            return "";
        }
        String host = data.ip.toLowerCase(Locale.ROOT).split(":")[0];
        for (String domain : new String[] { "catbud", "catrealm" }) {
            if (host.equals(domain + ".net") || host.endsWith("." + domain + ".net")) {
                return domain;
            }
        }
        return "";
    }

    @Override
    public void close() {
        coordinator.close();
    }
}
