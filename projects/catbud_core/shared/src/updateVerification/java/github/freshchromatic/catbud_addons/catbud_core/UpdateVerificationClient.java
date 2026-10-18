package github.freshchromatic.catbud_addons.catbud_core;

import github.freshchromatic.catbud_addons.update.Notice;
import github.freshchromatic.catbud_addons.update.ReleaseManifest;
import github.freshchromatic.catbud_addons.update.UpdateCoordinator;
import github.freshchromatic.catbud_addons.update.UpdatePlan;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class UpdateVerificationClient implements ClientModInitializer {

    private int                ticks;
    private int                step;
    private Notice             notice;
    private UpdatePlan         plan;
    private final List<String> results = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!UpdateVerificationPlatform.ready(client) || ++ticks % 35 != 0) {
                return;
            }
            try {
                run(client);
            } catch (Throwable error) {
                results.add("FAIL " + error);
                try {
                    Files.write(client.gameDirectory.toPath().resolve("updates-verification.txt"), results);
                } catch (java.io.IOException failed) {
                    error.addSuppressed(failed);
                }
                client.stop();
                throw new IllegalStateException("Update UI verification failed", error);
            }
        });
    }

    private void run(Minecraft client) throws Exception {
        var coordinator = CatbudCore.updates().coordinator();
        switch (step++) {
            case 0 -> {
                if (coordinator.notifications().automatic()) {
                    coordinator.notifications().toggleAutomatic();
                }
                client.options.pauseOnLostFocus = false;
                client.options.guiScale().set(2);
                UpdateVerificationPlatform.size(client, 1139, 755);
                UpdateVerificationPlatform.resize(client);
                notice = new Notice(java.util.UUID.randomUUID().toString(), 1, "Update and notice center",
                    "Updates are verified before installation.\nClose the game to install; wait before starting again.\n\n"
                        + "此畫面使用隔離測試資料，驗證中英文字體、公告捲動和已讀狀態。\n".repeat(15), "important", List.of(), List.of(), List.of(),
                    0, Long.MAX_VALUE, "", false);
                var artifact = new ReleaseManifest.Artifact("core:26.2", "core", "catbud_core", "26.2", ">=0.19.5", 25,
                    "1.0.4", "catbud-core-26.2-1.0.4.jar", "", 17000000, "a".repeat(64), List.of(), Map.of());
                plan = new UpdatePlan("v1.0.4", "26.2", Path.of("mods"), List.of(new UpdatePlan.Replacement(Path.of(
                    "mods/old.jar"), "b".repeat(64), artifact)), Map.of("core",
                        "Adds verified updates and persistent announcements."));
                snapshot(coordinator, UpdateCoordinator.InstallState.IDLE, 0);
                open(client, "updates");
                results.add("PASS initialized update UI with isolated fixture data");
            }
            case 1 -> {
                UpdateVerificationPlatform.capture(client, "01-updates.png");
                snapshot(coordinator, UpdateCoordinator.InstallState.DOWNLOADING, 8500000);
            }
            case 2 -> {
                UpdateVerificationPlatform.capture(client, "02-download.png");
                snapshot(coordinator, UpdateCoordinator.InstallState.READY, 17000000);
            }
            case 3 -> {
                UpdateVerificationPlatform.capture(client, "03-ready.png");
                open(client, "notifications");
                if (coordinator.unreadCount() != 1) {
                    throw new AssertionError("Expected one unread announcement");
                }
            }
            case 4 -> {
                UpdateVerificationPlatform.capture(client, "04-notices.png");
                ClientPlatform.show(client, new NoticeScreen(ClientPlatform.screen(client), notice));
            }
            case 5 -> {
                UpdateVerificationPlatform.capture(client, "05-notice-detail.png");
                if (coordinator.unreadCount() != 0) {
                    throw new AssertionError("Rendered announcement was not marked as read");
                }
                results.add("PASS detail rendering marks only the displayed announcement as read");
                UpdateVerificationPlatform.size(client, 854, 480);
                UpdateVerificationPlatform.resize(client);
                open(client, "updates");
            }
            case 6 -> {
                UpdateVerificationPlatform.capture(client, "06-small-window.png");
                results.add("PASS update, download, ready, announcement and small-window screens rendered");
                Files.write(client.gameDirectory.toPath().resolve("updates-verification.txt"), results);
                client.stop();
            }
            default -> {
            }
        }
    }

    private static void open(Minecraft client, String category) throws Exception {
        var screen = new SettingsScreen(null);
        var field = SettingsScreen.class.getDeclaredField("category");
        field.setAccessible(true);
        field.set(screen, category);
        ClientPlatform.show(client, screen);
    }

    private void snapshot(UpdateCoordinator coordinator, UpdateCoordinator.InstallState state, long progress)
        throws Exception {
        var field = UpdateCoordinator.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        field.set(coordinator, new UpdateCoordinator.Snapshot(UpdateCoordinator.CheckState.AVAILABLE, state, "", plan,
            List.of(notice), System.currentTimeMillis(), progress, 17000000));
    }
}
