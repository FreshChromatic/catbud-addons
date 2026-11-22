package github.freshchromatic.catbud_addons.island_assistive.neural.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningLookMotion;
import github.freshchromatic.catbud_addons.island_assistive.MiningPlayerControl;
import github.freshchromatic.catbud_addons.island_assistive.NeuralMiningClient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MiningPlayerControl.class, remap = false)
abstract class NeuralControlMixin {

    @Inject(method = "begin", at = @At("HEAD"))
    private void begin(BlockPos target, CallbackInfo callback) {
        NeuralMiningClient.begin(target);
    }

    @Inject(method = "look", at = @At("HEAD"))
    private void look(Vec3 point, double speed, CallbackInfo callback) {
        NeuralMiningClient.look(speed);
    }

    @Inject(method = "clearAim", at = @At("HEAD"))
    private void clear(CallbackInfo callback) {
        NeuralMiningClient.clearAim();
    }

    @Inject(method = "release", at = @At("HEAD"))
    private void release(CallbackInfo callback) {
        NeuralMiningClient.resetMotion();
    }

    @Inject(method = "nextRotation", at = @At("HEAD"), cancellable = true)
    private void rotation(long now, CallbackInfoReturnable<MiningLookMotion.Rotation> callback) {
        if (NeuralMiningClient.controlsAim()) {
            callback.setReturnValue(NeuralMiningClient.frame(now));
        }
    }
}
