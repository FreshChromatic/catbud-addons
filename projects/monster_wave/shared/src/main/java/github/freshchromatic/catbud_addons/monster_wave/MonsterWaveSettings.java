package github.freshchromatic.catbud_addons.monster_wave;

import github.freshchromatic.catbud_addons.catbud_core.CatbudCore;
import github.freshchromatic.catbud_addons.catbud_core.Setting;
import github.freshchromatic.catbud_addons.catbud_core.SettingsAddon;
import github.freshchromatic.catbud_addons.catbud_core.SettingsStore;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class MonsterWaveSettings {

    private MonsterWaveSettings() {
    }

    static void register(String version) {
        var schema = List.of(Setting.toggle("enabled", "general", true, value -> true));
        var store = new SettingsStore(CatbudCore.configPath("monster_wave"), schema, values -> MonsterWaveClient
            .session().enabled(values.bool("enabled")));
        MonsterWaveClient.session().enabled(store.current().bool("enabled"));
        CatbudCore.register(new SettingsAddon("monster_wave", tr("name"), version, List.of("general", "status"), schema,
            store, MonsterWaveSettings::status));
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("status.monster_wave." + key, args);
    }

    private static Component duration(Long millis) {
        return millis == null ? tr("unknown")
            : Component.literal(String.format(Locale.ROOT, "%.3f s", millis / 1000.0));
    }

    private static SettingsAddon.StatusRow info(String key, Supplier<Component> value) {
        return SettingsAddon.StatusRow.info(tr(key), value);
    }

    private static boolean available() {
        var observation = MonsterWavePlatform.observation();
        return MonsterWaveClient.session().enabled() && observation.available() && observation.multiplayer();
    }

    private static SettingsAddon.StatusRow action(String key, Runnable action, BooleanSupplier available, boolean confirm) {
        var row = new SettingsAddon.StatusRow(tr(key), () -> tr(key), action, available, () -> tr("action_note"));
        return confirm ? row.withConfirmation(new SettingsAddon.ActionConfirmation(tr(key), tr(key + ".message"), tr(
            key))) : row;
    }

    private static List<SettingsAddon.StatusRow> status() {
        var session = MonsterWaveClient.session();
        var auto = action("auto", session::useAuto, () -> available() && session.mode() != MonsterWaveSession.Mode.AUTO,
            false);
        var force = action("force", () -> session.forceStart(MonsterWavePlatform.observation()), () -> available()
            && session.mode() != MonsterWaveSession.Mode.FORCED, true);
        var stop = action("stop", session::forceStop, () -> available() && session
            .mode() != MonsterWaveSession.Mode.OFF, true);
        var rows = new java.util.ArrayList<SettingsAddon.StatusRow>();
        rows.add(info("mode", () -> tr("mode." + session.mode().name().toLowerCase(Locale.ROOT))));
        rows.add(info("phase", () -> tr("phase." + session.phase().name().toLowerCase(Locale.ROOT))));
        rows.add(info("health", MonsterWaveSettings::health));
        rows.add(info("risk", () -> tr("risk.value", session.healthRisk().predictedLoss(),
            LowHealthDisconnect.RESERVE)));
        rows.add(info("protection", () -> tr(session.active() && available() ? "on" : "off")));
        rows.add(info("reconnect", () -> tr(session.awaitingResume() ? "reconnect.waiting" : "reconnect.none")));
        rows.add(info("elapsed", () -> session.phase() == MonsterWaveSession.Phase.IDLE ? tr("unknown")
            : duration(session.elapsed())));
        rows.add(info("active_elapsed", () -> duration(session.activeElapsed())));
        rows.add(info("last_reason", MonsterWaveSettings::lastReason));
        rows.add(info("last_elapsed", MonsterWaveSettings::lastElapsed));
        rows.add(auto);
        rows.add(SettingsAddon.StatusRow.section("monster_wave.manual", tr("manual"), () -> tr("manual.note"), List.of(
            force, stop)));
        return List.copyOf(rows);
    }

    private static Component health() {
        var observation = MonsterWavePlatform.observation();
        return observation.available() ? tr("health.value", observation.health(), LowHealthDisconnect.THRESHOLD)
            : tr("unknown");
    }

    private static Component lastReason() {
        var summary = MonsterWaveClient.session().lastSummary();
        return summary == null ? tr("unknown") : tr("reason." + summary.reason().name().toLowerCase(Locale.ROOT));
    }

    private static Component lastElapsed() {
        var summary = MonsterWaveClient.session().lastSummary();
        return summary == null ? tr("unknown") : duration(summary.totalDurationMs());
    }
}
