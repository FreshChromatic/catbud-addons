package github.freshchromatic.catbud_addons.island_assistive.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningClientAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MiningMouseInputMixin {

    @Inject(method = "onButton", at = @At("HEAD"))
    private void islandAssistive$manualButton(long window, MouseButtonInfo button, int action, CallbackInfo callback) {
        if (action != InputConstants.PRESS || button.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        var session = ((MiningClientAccess) Minecraft.getInstance()).islandAssistive$mining();
        if (session != null) {
            session.takeManualControl();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"))
    private void islandAssistive$manualScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (horizontal == 0 && vertical == 0) {
            return;
        }
        var session = ((MiningClientAccess) Minecraft.getInstance()).islandAssistive$mining();
        if (session != null) {
            session.manualScroll();
        }
    }
}
