package github.freshchromatic.catbud_addons.magic_tower;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import github.freshchromatic.catbud_addons.catbud_core.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class MagicTowerSettings {
    static final List<String> TYPES = List.of("nether", "end", "coin", "enchantment", "chest", "mushroom");
    private static SettingsValues values;
    private static SettingsStore store;
    static SettingsValues values() { return values; }
    static boolean enabled() { return values == null || values.bool("enabled"); }
    static boolean routes() { return enabled() && values.bool("routes.enabled"); }
    static boolean autoPortal(WorldTargets.PortalType type) {
        return routes() && values.bool("routes.auto") && values.bool("routes.auto." + (type == WorldTargets.PortalType.NETHER ? "nether" : "end"));
    }
    static String type(WorldTargets targets, net.minecraft.core.BlockPos pos) {
        return switch (targets.targetNameKey(pos)) {
            case "target.catbud_addons.nether_portal" -> "nether";
            case "target.catbud_addons.end_portal" -> "end";
            case "target.catbud_addons.coin_bank" -> "coin";
            case "target.catbud_addons.enchantment_extraction_table" -> "enchantment";
            case "target.catbud_addons.copper_chest" -> "chest";
            default -> "mushroom";
        };
    }
    static boolean selectable(WorldTargets targets, net.minecraft.core.BlockPos pos) {
        return routes() && values.bool("routes.manual") && values.bool("routes.select." + type(targets, pos));
    }
    static int routeColor(WorldTargets targets, net.minecraft.core.BlockPos pos) {
        return values.color("routes.color." + type(targets, pos), "routes.opacity");
    }
    static List<Setting> schema() {
        List<Setting> s = new ArrayList<>();
        Predicate<SettingsValues> always = v -> true;
        Predicate<SettingsValues> enabled = v -> v.bool("enabled");
        Predicate<SettingsValues> radar = v -> enabled.test(v) && v.bool("radar.enabled");
        Predicate<SettingsValues> esp = v -> enabled.test(v) && v.bool("esp.enabled");
        Predicate<SettingsValues> routes = v -> enabled.test(v) && v.bool("routes.enabled");
        Predicate<SettingsValues> auto = v -> routes.test(v) && v.bool("routes.auto");
        Predicate<SettingsValues> manual = v -> routes.test(v) && v.bool("routes.manual");
        Predicate<SettingsValues> hints = v -> routes.test(v) && v.bool("hints.enabled");
        s.add(Setting.toggle("enabled", "general", true, always));
        s.add(Setting.toggle("radar.enabled", "radar", true, enabled));
        for (String id : List.of("nether", "end", "coin", "enchantment", "distanceText", "arrows", "vertical"))
            s.add(Setting.toggle("radar." + id, "radar", true, radar));
        s.add(Setting.number("radar.iconScale", "radar", 1, 0.5, 2, 0.05, radar));
        s.add(Setting.number("radar.textScale", "radar", 1, 0.5, 2, 0.05, radar));
        s.add(Setting.number("radar.opacity", "radar", 1, 0.1, 1, 0.05, radar));
        s.add(Setting.number("radar.distance", "radar", 0, 0, 512, 1, radar));
        s.add(Setting.number("radar.hideNear", "radar", 3, 0, 32, 1, radar));
        s.add(Setting.number("radar.margin", "radar", 28, 8, 100, 1, radar));
        s.add(Setting.number("radar.verticalThreshold", "radar", 5, 1, 32, 1, v -> radar.test(v) && v.bool("radar.vertical")));
        s.add(Setting.color("radar.color.nether", "radar", "#B36AE8", radar));
        s.add(Setting.color("radar.color.end", "radar", "#66D8E9", radar));
        s.add(Setting.toggle("esp.enabled", "esp", true, enabled));
        s.add(Setting.toggle("esp.chest", "esp", true, esp));
        s.add(Setting.toggle("esp.mushroom", "esp", true, esp));
        s.add(Setting.number("esp.distance", "esp", 128, 8, 512, 1, esp));
        s.add(Setting.color("esp.color.chest", "esp", "#FFB347", esp));
        s.add(Setting.color("esp.color.mushroom", "esp", "#B875FF", esp));
        s.add(Setting.number("esp.opacity", "esp", 1, 0.1, 1, 0.05, esp));
        s.add(Setting.number("esp.width", "esp", 1, 1, 5, 1, esp));
        s.add(Setting.toggle("routes.enabled", "routes", true, enabled));
        s.add(Setting.toggle("routes.auto", "routes", true, routes));
        s.add(Setting.toggle("routes.auto.nether", "routes", true, auto));
        s.add(Setting.toggle("routes.auto.end", "routes", true, auto));
        s.add(Setting.toggle("routes.exploreDefault", "routes", true, routes));
        s.add(Setting.toggle("routes.manual", "routes", true, routes));
        String[] colors = {"#B36AE8", "#66D8E9", "#FFD052", "#62D1FF", "#FFB347", "#B875FF"};
        for (int i = 0; i < TYPES.size(); i++) {
            s.add(Setting.toggle("routes.select." + TYPES.get(i), "routes", true, manual));
            s.add(Setting.color("routes.color." + TYPES.get(i), "routes", colors[i], routes));
        }
        s.add(Setting.color("routes.color.explore", "routes", "#088DA5", routes));
        s.add(Setting.number("routes.opacity", "routes", 1, 0.1, 1, 0.05, routes));
        s.add(Setting.number("routes.width", "routes", 3, 1, 8, 0.5, routes));
        s.add(Setting.toggle("hints.enabled", "hints", true, routes));
        for (String id : List.of("coordinates", "distance", "controls", "exploreCount", "priority"))
            s.add(Setting.toggle("hints." + id, "hints", true, hints));
        s.add(Setting.choice("hints.messages", "hints", "chat", List.of("chat", "actionbar", "off"), enabled));
        return List.copyOf(s);
    }
    static void register() {
        List<Setting> schema = schema();
        store = new SettingsStore(CatbudCore.configPath("magic_tower"), schema, next -> {
            boolean wasEnabled = enabled();
            boolean explorationChanged = values.bool("routes.exploreDefault") != next.bool("routes.exploreDefault");
            values = next;
            if (wasEnabled != enabled()) CatbudAddonsClient.session().useAuto();
            TargetLines.onConfigurationChanged(explorationChanged);
        });
        values = store.current();
        var legacy = FabricLoader.getInstance().getConfigDir().resolve("catbud-addons.json");
        if (!store.exists() && Files.exists(legacy)) {
            try {
                var old = JsonParser.parseString(Files.readString(legacy)).getAsJsonObject();
                if (old.has("autoPortalRoutes") && old.get("autoPortalRoutes").isJsonPrimitive()
                    && old.getAsJsonPrimitive("autoPortalRoutes").isBoolean()) {
                    var draft = values.copy();
                    draft.set(schema.stream().filter(s -> s.id().equals("routes.auto")).findFirst().orElseThrow(),
                        new JsonPrimitive(old.get("autoPortalRoutes").getAsBoolean()));
                    store.apply(draft);
                }
            } catch (Exception e) { CatbudAddonsClient.LOGGER.warn("Cannot migrate legacy settings", e); }
        }
        String version = FabricLoader.getInstance().getModContainer("catbud_addons").orElseThrow().getMetadata().getVersion().getFriendlyString();
        CatbudCore.register(new SettingsAddon("magic_tower", Component.literal("Magic Tower"), version,
            List.of("general", "radar", "esp", "routes", "hints", "status"), schema, store, MagicTowerSettings::status,
            category -> category.equals("general") ? List.of(SettingsAddon.StatusRow.info(
                Component.translatable("key.catbud_addons.toggle_target_line"), CatbudAddonsClient::targetKeyName)) : List.of()));
    }
    private static Component tr(String key, Object... args) { return Component.translatable("status.magic_tower." + key, args); }
    private static boolean inWorld() { var mc = Minecraft.getInstance(); return enabled() && mc.level != null && mc.player != null; }
    private static boolean active() { return inWorld() && CatbudAddonsClient.session().isActive(); }
    private static Component reason() {
        return tr(!enabled() ? "disabled" : !inWorld() ? "need_world" : !active() ? "need_scan" : "immediate");
    }
    private static SettingsAddon.StatusRow action(String label, java.util.function.Supplier<Component> value,
                                                 Runnable run, java.util.function.BooleanSupplier available) {
        return new SettingsAddon.StatusRow(tr(label), value, run, available,
            () -> label.startsWith("floor.") ? reason() : tr(!enabled() ? "disabled" : !inWorld() ? "need_world" : "immediate"));
    }
    static List<SettingsAddon.StatusRow> status() {
        var session = CatbudAddonsClient.session();
        var targets = session.targets();
        var rows = new ArrayList<SettingsAddon.StatusRow>();
        rows.add(SettingsAddon.StatusRow.info(tr("mode"), () -> tr("mode." + session.modeKey())));
        rows.add(SettingsAddon.StatusRow.info(tr("phase"), () -> tr("phase." + session.phaseKey())));
        rows.add(SettingsAddon.StatusRow.info(tr("evacuation"), () -> tr("evacuation." + session.evacuationStatus().name().toLowerCase(Locale.ROOT))));
        for (String type : TYPES) rows.add(SettingsAddon.StatusRow.info(tr("count." + type),
            () -> Component.literal(Integer.toString(targets.targetCount(type)))));
        rows.add(SettingsAddon.StatusRow.info(tr("marked"), () -> Component.literal(Integer.toString(targets.lineTargets().size()))));
        rows.add(SettingsAddon.StatusRow.info(tr("routes"), () -> Component.literal(Integer.toString(TargetLines.routeCount()))));
        rows.add(action("force", () -> tr("force"), () -> { session.forceStart(Minecraft.getInstance()); TargetLines.tick(Minecraft.getInstance(), session); }, MagicTowerSettings::inWorld));
        rows.add(action("stop", () -> tr("stop"), () -> { session.forceStop(); TargetLines.tick(Minecraft.getInstance(), session); }, MagicTowerSettings::inWorld));
        rows.add(action("auto", () -> tr("auto"), () -> { session.useAuto(); TargetLines.tick(Minecraft.getInstance(), session); }, MagicTowerSettings::inWorld));
        rows.add(new SettingsAddon.StatusRow(tr("floor.explore"), () -> tr(TargetLines.manualExplorationEnabled() ? "on" : "off"),
            () -> TargetLines.setManualExploration(Minecraft.getInstance(), session, !TargetLines.manualExplorationEnabled()),
            () -> active() && routes(), MagicTowerSettings::reason, TargetLines::manualExplorationEnabled));
        for (var type : WorldTargets.PortalType.values()) rows.add(new SettingsAddon.StatusRow(
            tr("floor." + type.name().toLowerCase(Locale.ROOT)),
            () -> tr(!autoPortal(type) ? "permanent_off" : TargetLines.autoSuppressed(type) ? "paused" : "allowed"),
            () -> TargetLines.setAutoSuppressed(type, !TargetLines.autoSuppressed(type)),
            () -> active() && autoPortal(type),
            () -> !autoPortal(type) ? tr("permanent_off") : reason(), () -> autoPortal(type) && !TargetLines.autoSuppressed(type)));
        return rows;
    }
}
