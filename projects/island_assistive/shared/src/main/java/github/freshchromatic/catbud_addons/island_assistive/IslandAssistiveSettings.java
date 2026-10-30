package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.catbud_core.CatbudCore;
import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import github.freshchromatic.catbud_addons.catbud_core.Setting;
import github.freshchromatic.catbud_addons.catbud_core.SettingsAddon;
import github.freshchromatic.catbud_addons.catbud_core.SettingsStore;
import github.freshchromatic.catbud_addons.catbud_core.SettingsValues;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.ArrayList;

public final class IslandAssistiveSettings {

    private final MobAlertSession    session;
    private final MobWatchlistStore  watchlist;
    private final AlertSoundPlayer   sound;
    private final SettingsStore      store;
    private final BlockMiningSession mining;

    public IslandAssistiveSettings(String version, MobAlertSession session, MobWatchlistStore watchlist, AlertSoundPlayer sound, BlockMiningSession mining) {
        this.session = session;
        this.watchlist = watchlist;
        this.sound = sound;
        this.mining = mining;
        var schema = new ArrayList<Setting>();
        schema.add(Setting.toggle("enabled", "general", false, values -> true));
        schema.add(Setting.number("radius", "general", 256, 16, 256, 1, values -> true));
        schema.add(Setting.number("volume", "general", 1, 0, 1, 0.05, values -> true));
        schema.add(Setting.toggle("initialNotices", "general", true, values -> true));
        schema.add(Setting.toggle("miningMovement", "mining", true, values -> true));
        schema.add(Setting.number("miningRadius", "mining", 0.5, 0.125, 1, 0.125, values -> values.bool(
            "miningMovement")));
        schema.add(Setting.number("miningTurnSpeed", "mining", 180, 30, 360, 15, values -> true));
        schema.add(Setting.toggle("miningCooldown", "mining", true, values -> true));
        schema.add(Setting.toggle("miningPauseInventory", "mining", true, values -> true));
        schema.add(Setting.toggle("miningPauseScreens", "mining", false, values -> true));
        schema.add(Setting.toggle("miningPauseUsingItem", "mining", false, values -> true));
        schema.add(Setting.toggle("miningPauseUnfocused", "mining", false, values -> true));
        store = new SettingsStore(CatbudCore.configPath("island_assistive"), schema, this::apply);
        apply(store.current());
        CatbudCore.register(new SettingsAddon("island_assistive", Component.translatable("island.name"), version, List
            .of("general", "mining", "status"), schema, store, this::status, category -> category.equals("mining")
                ? miningRows()
                : List.of()));
    }

    private void apply(SettingsValues values) {
        mining.configure(values.bool("miningMovement"), values.number("miningRadius"), values.number("miningTurnSpeed"),
            values.bool("miningCooldown"));
        mining.interruptions(new MiningInterruptionPolicy(values.bool("miningPauseInventory"), values.bool(
            "miningPauseScreens"), values.bool("miningPauseUsingItem"), values.bool("miningPauseUnfocused")));
        sound.volume((float) values.number("volume"));
        session.configure(values.bool("enabled"), values.number("radius"), values.bool("initialNotices"), watchlist
            .rules());
    }

    private List<SettingsAddon.StatusRow> miningRows() {
        var rows = new ArrayList<SettingsAddon.StatusRow>();
        rows.add(SettingsAddon.StatusRow.info(text("mining.state"), mining::status));
        rows.add(SettingsAddon.StatusRow.info(text("mining.target"), mining::targetDescription));
        rows.add(SettingsAddon.StatusRow.info(text("mining.world"), mining::worldDescription));
        rows.add(SettingsAddon.StatusRow.info(text("mining.dimension"), mining::dimensionDescription));
        rows.add(new SettingsAddon.StatusRow(text("mining.select"), () -> text("mining.select"), mining::select,
            () -> Minecraft.getInstance().level != null, () -> text("mining.select_help")));
        rows.add(new SettingsAddon.StatusRow(text("mining.toggle"), () -> text(mining.running() ? "mining.stop"
            : "mining.start"), mining::toggle, () -> Minecraft.getInstance().level != null, () -> text(
                "mining.toggle_help")));
        return List.copyOf(rows);
    }

    private List<SettingsAddon.StatusRow> status() {
        var rows = new ArrayList<SettingsAddon.StatusRow>();
        rows.add(SettingsAddon.StatusRow.info(text("status.state"), this::state));
        rows.add(SettingsAddon.StatusRow.info(text("status.pending"), () -> Component.literal(Integer.toString(session
            .pending().size()))));
        rows.add(SettingsAddon.StatusRow.info(text("status.rules"), () -> text(watchlist.rules().isEmpty()
            ? "status.empty"
            : "status.count", watchlist.rules().size())));
        rows.add(SettingsAddon.StatusRow.info(text("status.watchlist"), () -> text(watchlist.loadFailed()
            ? "watchlist.load_failed"
            : "status.ready")));
        rows.add(new SettingsAddon.StatusRow(text("watchlist.title"), () -> text("watchlist.title"),
            this::openWatchlist, () -> true, () -> text("watchlist.help")));
        rows.add(new SettingsAddon.StatusRow(text("sound.preview"), () -> text("sound.preview"), sound::play,
            () -> true, () -> text("sound.description")));
        return List.copyOf(rows);
    }

    private void openWatchlist() {
        var client = Minecraft.getInstance();
        ClientPlatform.show(client, new MobWatchlistScreen(ClientPlatform.screen(client), watchlist, () -> apply(store
            .current())));
    }

    private static Component text(String key, Object... arguments) {
        return Component.translatable("island." + key, arguments);
    }

    private Component state() {
        if (!session.enabled()) {
            return Component.translatable("island.status.off");
        }
        if (Minecraft.getInstance().level == null) {
            return Component.translatable("island.status.waiting");
        }
        return Component.translatable(session.buildingBaseline() ? "island.status.baseline" : "island.status.watching");
    }
}
