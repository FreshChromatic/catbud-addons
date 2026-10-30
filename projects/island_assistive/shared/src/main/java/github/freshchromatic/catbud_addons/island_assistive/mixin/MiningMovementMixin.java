package github.freshchromatic.catbud_addons.island_assistive.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningClientAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class MiningMovementMixin {

    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;tick()V", shift = At.Shift.AFTER))
    private void islandAssistive$bodyMovement(CallbackInfo ci) {
        var client = Minecraft.getInstance();
        if ((Object) this != client.player) {
            return;
        }
        var session = ((MiningClientAccess) client).islandAssistive$mining();
        if (session != null) {
            session.applyMovementInput();
        }
    }
}
