package github.freshchromatic.catbud_addons.magic_tower;

import github.freshchromatic.catbud_addons.catbud_core.*;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

record CoinHudElement(TowerHudState state) implements HudElement {

    private static final Identifier ICON = Identifier.fromNamespaceAndPath("catbud_addons",
        "textures/gui/coin_of_tower.png");

    public String id() {
        return "catbud_addons:tower_coins";
    }

    public String addonId() {
        return "magic_tower";
    }

    public Component name() {
        return Component.translatable("hud.magic_tower.coins");
    }

    public boolean visible() {
        return state.visible();
    }

    public HudElementConfig defaults() {
        return TowerHudDefaults.config(id());
    }

    public HudContent content(boolean preview) {
        var coins = preview ? new TowerCoinInventory.Snapshot(137, List.of(215), false) : state.coins();
        var settings = MagicTowerSettings.values();
        List<Component> lines = new ArrayList<>();
        if (settings.bool("hud.coins.count")) {
            lines.add(Component.translatable("hud.magic_tower.coin_count", coins.count()));
        }
        if (settings.bool("hud.coins.capacity")) {
            if (coins.capacities().size() > 1) {
                lines.add(Component.translatable("hud.magic_tower.variants"));
                for (int i = 0; i < coins.capacities().size(); i++) {
                    lines.add(Component.translatable("hud.magic_tower.variant_capacity", i + 1, coins.capacities().get(
                        i)));
                }
            } else {
                lines.add(Component.translatable(coins.estimated() ? "hud.magic_tower.estimated_capacity"
                    : "hud.magic_tower.capacity", coins.capacities().getFirst()));
            }
        }
        if (settings.bool("hud.coins.singleLine") && !lines.isEmpty()) {
            var joined = Component.empty();
            for (Component line : lines) {
                if (!joined.getSiblings().isEmpty()) {
                    joined.append(" · ");
                }
                joined.append(line);
            }
            lines = List.of(joined);
        }
        return new HudContent(ICON, lines);
    }
}
