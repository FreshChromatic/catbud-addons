package github.freshchromatic.catbud_addons.catbud_core;

import net.minecraft.network.chat.Component;

public interface HudElement {

    String id();

    String addonId();

    Component name();

    HudElementConfig defaults();

    boolean visible();

    HudContent content(boolean preview);
}
