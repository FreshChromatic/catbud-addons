package github.freshchromatic.catbud_addons.catbud_core;

import github.freshchromatic.catbud_addons.update.UpdateCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class AddonUpdateRows {

    private AddonUpdateRows() {
    }

    static List<SettingsAddon.StatusRow> updates(Screen parent) {
        var coordinator = CatbudCore.updates().coordinator();
        var snapshot = coordinator.snapshot();
        var rows = new ArrayList<SettingsAddon.StatusRow>();
        rows.add(SettingsAddon.StatusRow.info(tr("updates.core.status"), () -> tr("updates.core.check." + coordinator
            .snapshot().check().name().toLowerCase(java.util.Locale.ROOT))));
        rows.add(SettingsAddon.StatusRow.info(tr("updates.core.install"), () -> tr("updates.core.install." + coordinator
            .snapshot().install().name().toLowerCase(java.util.Locale.ROOT))));
        addInstallationActions(rows, coordinator, parent);
        String detail = snapshot.detail() + " " + coordinator.notifications().error() + " " + CatbudCore.updates()
            .initializationError();
        if (!detail.isBlank()) {
            rows.add(new SettingsAddon.StatusRow(tr("updates.core.details"), () -> Component.literal(detail), null,
                () -> true, () -> Component.literal(detail)));
        }
        rows.add(action("updates.core.check_now", () -> tr("updates.core.check_now"), () -> coordinator.check(true),
            () -> coordinator.canCheck() && coordinator.snapshot().install() != UpdateCoordinator.InstallState.READY));
        rows.add(SettingsAddon.StatusRow.info(tr("updates.core.last_check"), () -> {
            long checked = coordinator.snapshot().lastChecked();
            return checked == 0 ? tr("updates.core.never")
                : Component.literal(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(checked)));
        }));
        var plan = snapshot.plan();
        if (plan != null && !plan.replacements().isEmpty()) {
            rows.add(SettingsAddon.StatusRow.info(tr("updates.core.size"), () -> Component.literal(String.format(
                java.util.Locale.ROOT, "%.2f MiB", plan.downloadBytes() / 1048576.0))));
            for (var replacement : plan.replacements()) {
                var artifact = replacement.artifact();
                rows.add(SettingsAddon.StatusRow.info(tr("updates.module." + artifact.module()), () -> Component
                    .translatable("updates.core.version", AddonUpdateClient.version(artifact.modId()), artifact
                        .releaseRevision().displayVersion())));
                String note = plan.notes().getOrDefault(artifact.module(), "");
                rows.add(new SettingsAddon.StatusRow(tr("updates.core.notes"), () -> Component.literal(note), null,
                    () -> true, () -> Component.literal(note)));
            }
            rows.add(action("updates.core.skip", () -> tr("updates.core.skip"), () -> {
                for (var item : plan.replacements()) {
                    coordinator.notifications().skip(item.artifact().module(), item.artifact().releaseRevision().key());
                }
            }, () -> true));
            rows.add(action("updates.core.tomorrow", () -> tr("updates.core.tomorrow"), () -> {
                for (var item : plan.replacements()) {
                    coordinator.notifications().snooze(item.artifact().reminderId(), System.currentTimeMillis());
                }
            }, () -> true));
        }
        rows.add(action("updates.core.automatic", () -> tr(coordinator.notifications().automatic() ? "options.on"
            : "options.off"), () -> coordinator.notifications().toggleAutomatic(), () -> true));
        rows.add(action("updates.core.github", () -> tr("updates.core.github"), () -> ConfirmLinkScreen.confirmLinkNow(
            parent, URI.create("https://github.com/FreshChromatic/catbud-addons/releases")), () -> true));
        return rows;
    }

    private static void addInstallationActions(List<SettingsAddon.StatusRow> rows, UpdateCoordinator coordinator, Screen parent) {
        var snapshot = coordinator.snapshot();
        if (!CatbudCore.updates().guarded()) {
            rows.add(new SettingsAddon.StatusRow(tr("updates.core.guard"), () -> tr("updates.core.guard_required"),
                null, () -> true, () -> tr("updates.core.guard_help")));
            rows.add(action("updates.core.copy_guard", () -> tr("updates.core.copy_guard"), () -> Minecraft
                .getInstance().keyboardHandler.setClipboard(CatbudCore.updates().launcherCommand()), () -> !CatbudCore
                    .updates().launcherCommand().isEmpty()));
            rows.add(action("updates.core.github", () -> tr("updates.core.github"), () -> ConfirmLinkScreen
                .confirmLinkNow(parent, URI.create("https://github.com/FreshChromatic/catbud-addons/releases")),
                () -> true));
            return;
        }
        if (snapshot.install() == UpdateCoordinator.InstallState.READY) {
            rows.add(action("updates.core.exit", () -> tr("updates.core.exit"), () -> ClientPlatform.show(Minecraft
                .getInstance(), new ConfirmScreen(accepted -> {
                    if (accepted && coordinator.snapshot().install() == UpdateCoordinator.InstallState.READY) {
                        Minecraft.getInstance().stop();
                    } else {
                        ClientPlatform.show(Minecraft.getInstance(), parent);
                    }
                }, tr("updates.core.exit"), tr("updates.core.exit_help"))), () -> true));
        } else if (snapshot.install() == UpdateCoordinator.InstallState.DOWNLOADING || snapshot
            .install() == UpdateCoordinator.InstallState.VERIFYING) {
            rows.add(SettingsAddon.StatusRow.info(tr("updates.core.progress"), () -> {
                var current = coordinator.snapshot();
                return Component.literal(current.total() == 0 ? "—"
                    : current.downloaded() * 100 / current.total() + "%");
            }));
        } else if (snapshot.plan() != null && !snapshot.plan().replacements().isEmpty() && snapshot
            .install() != UpdateCoordinator.InstallState.REPAIR_REQUIRED) {
            rows.add(action("updates.core.download", () -> tr("updates.core.download"), coordinator::download,
                () -> true));
        }
        if (List.of(UpdateCoordinator.InstallState.DOWNLOADING, UpdateCoordinator.InstallState.VERIFYING,
            UpdateCoordinator.InstallState.READY).contains(snapshot.install())) {
            rows.add(action("updates.core.cancel", () -> tr("updates.core.cancel"), coordinator::requestCancel,
                () -> true));
        }
    }

    static List<SettingsAddon.StatusRow> notices(Screen parent) {
        var coordinator = CatbudCore.updates().coordinator();
        var rows = new ArrayList<SettingsAddon.StatusRow>();
        if (!coordinator.noticeError().isEmpty()) {
            String error = coordinator.noticeError();
            rows.add(new SettingsAddon.StatusRow(tr("notices.core.unavailable"), () -> tr("notices.core.cached"), null,
                () -> true, () -> Component.literal(error)));
        }
        rows.add(action("updates.core.check_now", () -> tr("updates.core.check_now"), () -> coordinator.check(true),
            coordinator::canCheck));
        rows.add(action("notices.core.toasts", () -> tr(coordinator.notifications().toasts() ? "options.on"
            : "options.off"), () -> coordinator.notifications().toggleToasts(), () -> true));
        rows.add(SettingsAddon.StatusRow.info(tr("notices.core.unread"), () -> Component.literal(Integer.toString(
            coordinator.unreadCount()))));
        rows.add(action("notices.core.mark_all", () -> tr("notices.core.mark_all"), () -> coordinator.notifications()
            .readAll(coordinator.notices()), () -> coordinator.unreadCount() > 0));
        for (var notice : coordinator.notices()) {
            rows.add(new SettingsAddon.StatusRow(Component.literal(notice.title()), () -> tr(coordinator.notifications()
                .unread(notice) ? "notices.core.new" : "notices.core.read"), () -> ClientPlatform.show(Minecraft
                    .getInstance(), new NoticeScreen(parent, notice)), () -> true, () -> Component.literal(notice
                        .body())));
        }
        if (coordinator.notices().isEmpty()) {
            rows.add(SettingsAddon.StatusRow.info(tr("notices.core.empty"), Component::empty));
        }
        return rows;
    }

    private static SettingsAddon.StatusRow action(String label, Supplier<Component> value, Runnable action, BooleanSupplier available) {
        return new SettingsAddon.StatusRow(tr(label), value, action, available, Component::empty);
    }

    private static Component tr(String key) {
        return Component.translatable(key);
    }
}
