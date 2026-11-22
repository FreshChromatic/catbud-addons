package github.freshchromatic.catbud_addons.island_assistive.neural.mixin;

import github.freshchromatic.catbud_addons.island_assistive.NeuralMiningClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class NeuralCorrectionMixin {

    @Inject(method = "runTick", at = @At("HEAD"))
    private void correctionFrame(boolean renderLevel, CallbackInfo callback) {
        NeuralMiningClient.captureCorrection();
    }
}
