package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

public record MiningInterruptionPolicy(boolean inventory, boolean screens, boolean usingItem, boolean unfocused) {

    public static final MiningInterruptionPolicy DEFAULT = new MiningInterruptionPolicy(true, false, false, false);

    public String suspension(Minecraft client) {
        var screen = ClientPlatform.screen(client);
        if (screen instanceof AbstractContainerScreen<?>) {
            if (inventory) {
                return "suspended_inventory";
            }
        } else if (screens && screen != null) {
            return "suspended_screen";
        }
        if (usingItem && client.player.isUsingItem()) {
            return "suspended_item";
        }
        if (unfocused && (!client.isWindowActive() || screen == null && !client.mouseHandler.isMouseGrabbed())) {
            return "suspended_focus";
        }
        return null;
    }
}
