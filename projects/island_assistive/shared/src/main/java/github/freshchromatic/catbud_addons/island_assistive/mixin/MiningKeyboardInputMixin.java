package github.freshchromatic.catbud_addons.island_assistive.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import github.freshchromatic.catbud_addons.island_assistive.MiningClientAccess;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class MiningKeyboardInputMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void islandAssistive$manualKey(long window, int action, KeyEvent key, CallbackInfo callback) {
        if (action != InputConstants.PRESS) {
            return;
        }
        var session = ((MiningClientAccess) Minecraft.getInstance()).islandAssistive$mining();
        if (session != null) {
            session.manualKey(key);
        }
    }
}
