package github.freshchromatic.catbud_addons.magic_tower;

import github.freshchromatic.catbud_addons.catbud_core.*;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

record EvacuationHudElement(TowerHudState state) implements HudElement {

    public String id() {
        return "catbud_addons:tower_evacuation";
    }

    public String addonId() {
        return "magic_tower";
    }

    public Component name() {
        return Component.translatable("hud.magic_tower.evacuation");
    }

    public boolean visible() {
        return state.visible();
    }

    public HudElementConfig defaults() {
        return TowerHudDefaults.config(id());
    }

    public HudContent content(boolean preview) {
        var status = preview ? TowerSession.EvacuationStatus.PRESENT : state.session().evacuationStatus();
        Identifier icon = switch (status) {
            case PRESENT -> Identifier.fromNamespaceAndPath("catbud_addons", "textures/gui/check.png");
            case ABSENT -> Identifier.fromNamespaceAndPath("catbud_addons", "textures/gui/close.png");
            case UNKNOWN -> null;
        };
        return new HudContent(icon, List.of(Component.translatable("hud.magic_tower.evacuation." + status.name()
            .toLowerCase(Locale.ROOT))));
    }
}
