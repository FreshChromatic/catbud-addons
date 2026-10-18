package github.freshchromatic.catbud_addons.catbud_core;

import net.minecraft.network.chat.Component;

record HudCopyElement(String id, HudElement source) implements HudElement {

    @Override
    public String addonId() {
        return source.addonId();
    }

    @Override
    public Component name() {
        return Component.translatable("hud.core.copy_name", source.name(), id.substring(Math.max(0, id.length() - 4)));
    }

    @Override
    public HudElementConfig defaults() {
        return source.defaults().withSource(source.id());
    }

    @Override
    public boolean visible() {
        return source.visible();
    }

    @Override
    public HudContent content(boolean preview) {
        return source.content(preview);
    }
}
