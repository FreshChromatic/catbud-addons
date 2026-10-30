package github.freshchromatic.catbud_addons.island_assistive.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningClientAccess;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "fi.dy.masa.tweakeroo.util.CameraUtils", remap = false)
public abstract class TweakerooCameraMixin {

    @Inject(method = "shouldPreventPlayerInputs", at = @At("HEAD"), cancellable = true, remap = false)
    private static void islandAssistive$allowBodyMining(CallbackInfoReturnable<Boolean> ci) {
        var client = Minecraft.getInstance();
        if (client != null && ((MiningClientAccess) client).islandAssistive$bodyInput()) {
            ci.setReturnValue(false);
        }
    }
}
