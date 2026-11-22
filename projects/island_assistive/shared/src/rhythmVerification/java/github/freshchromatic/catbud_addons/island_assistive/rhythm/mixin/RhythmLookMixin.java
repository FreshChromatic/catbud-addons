package github.freshchromatic.catbud_addons.island_assistive.rhythm.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningLookMotion;
import github.freshchromatic.catbud_addons.island_assistive.RhythmGameVerification;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MiningLookMotion.class, remap = false)
abstract class RhythmLookMixin {

    @Inject(method = "advance", at = @At("HEAD"), cancellable = true)
    private void rhythm$advance(float yaw, float pitch, float targetYaw, float targetPitch, double speed, double seconds, CallbackInfoReturnable<MiningLookMotion.Rotation> callback) {
        var rotation = RhythmGameVerification.rotation(yaw, pitch, seconds);
        if (rotation != null) {
            callback.setReturnValue(rotation);
        }
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void rhythm$reset(CallbackInfo callback) {
        RhythmGameVerification.resetMotion();
    }
}
