package github.freshchromatic.catbud_addons.island_assistive.study.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningLookMotion;
import github.freshchromatic.catbud_addons.island_assistive.MiningStudyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MiningLookMotion.class, remap = false)
abstract class StudyLookMixin {

    @Inject(method = "advance", at = @At("HEAD"), cancellable = true)
    private void study$advance(float yaw, float pitch, float targetYaw, float targetPitch, double speed, double seconds, CallbackInfoReturnable<MiningLookMotion.Rotation> callback) {
        var rotation = MiningStudyClient.rotation(yaw, pitch, speed, seconds);
        if (rotation != null) {
            callback.setReturnValue(rotation);
        }
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void study$reset(CallbackInfo callback) {
        MiningStudyClient.clearAim();
    }
}
