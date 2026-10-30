package github.freshchromatic.catbud_addons.island_assistive.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningClientAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MiningMouseInputMixin {

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
