package github.freshchromatic.catbud_addons.catbud_core;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

public record HudContent(Identifier icon, List<Component> lines) {

    public HudContent {
        lines = List.copyOf(lines);
    }
}
