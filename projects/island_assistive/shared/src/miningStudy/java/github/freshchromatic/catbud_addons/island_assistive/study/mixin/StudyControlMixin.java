package github.freshchromatic.catbud_addons.island_assistive.study.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningPlayerControl;
import github.freshchromatic.catbud_addons.island_assistive.MiningStudyClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MiningPlayerControl.class, remap = false)
abstract class StudyControlMixin {

    @Shadow
    @Final private Minecraft         client;
    @Shadow private float            expectedYaw;
    @Shadow private float            expectedPitch;
    @Shadow private boolean          looking;

    @Inject(method = "begin", at = @At("HEAD"))
    private void begin(CallbackInfo callback) {
        MiningStudyClient.beginMotion();
    }

    @Inject(method = "release", at = @At("HEAD"))
    private void release(CallbackInfo callback) {
        MiningStudyClient.releaseMotion();
    }

    @Inject(method = "frame", at = @At("HEAD"), cancellable = true)
    private void frame(long now, CallbackInfo callback) {
        var rotation = MiningStudyClient.reactiveFrame(now);
        if (rotation == null) {
            return;
        }
        expectedYaw = rotation.yaw();
        expectedPitch = rotation.pitch();
        client.player.setYRot(expectedYaw);
        client.player.setXRot(expectedPitch);
        client.player.yRotO = expectedYaw;
        client.player.xRotO = expectedPitch;
        looking = true;
        callback.cancel();
    }
}
