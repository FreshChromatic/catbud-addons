package github.freshchromatic.catbud_addons.magic_tower;

import github.freshchromatic.catbud_addons.catbud_core.*;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.OptionalInt;

record FloorHudElement(TowerHudState state) implements HudElement {

    public String id() {
        return "catbud_addons:tower_floor";
    }

    public String addonId() {
        return "magic_tower";
    }

    public Component name() {
        return Component.translatable("hud.magic_tower.floor");
    }

    public boolean visible() {
        return state.visible();
    }

    public HudElementConfig defaults() {
        return TowerHudDefaults.config(id());
    }

    public HudContent content(boolean preview) {
        OptionalInt floor = preview ? OptionalInt.of(12) : state.session().floor();
        return new HudContent(null, List.of(floor.isPresent() ? Component.translatable("hud.magic_tower.floor_number",
            floor.getAsInt()) : Component.translatable("hud.magic_tower.floor_unknown")));
    }
}
