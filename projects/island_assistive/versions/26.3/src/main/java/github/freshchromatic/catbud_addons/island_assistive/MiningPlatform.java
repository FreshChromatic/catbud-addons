package github.freshchromatic.catbud_addons.island_assistive;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

public final class MiningPlatform {

    private MiningPlatform() {
    }

    public static void swing(Minecraft client) {
        client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND,
            net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
    }

    public static void click(KeyMapping mapping) {
        KeyMapping.click(KeyMappingHelper.getBoundKeyOf(mapping));
    }

    public static boolean physical(KeyMapping mapping) {
        var key = KeyMappingHelper.getBoundKeyOf(mapping);
        if (key.getValue() < 0) {
            return false;
        }
        if (key.getType() == InputConstants.Type.MOUSE) {
            var mouse = Minecraft.getInstance().mouseHandler;
            return switch (key.getValue()) {
                case 0 -> mouse.isLeftPressed();
                case 1 -> mouse.isRightPressed();
                case 2 -> mouse.isMiddlePressed();
                default -> false;
            };
        }
        return InputConstants.isKeyDown(key.getValue());
    }
}
