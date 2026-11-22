package github.freshchromatic.catbud_addons.island_assistive.rhythm.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningPlayerControl;
import github.freshchromatic.catbud_addons.island_assistive.RhythmGameVerification;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MiningPlayerControl.class, remap = false)
abstract class RhythmTargetMixin {

    @Inject(method = "look", at = @At("HEAD"))
    private void rhythm$target(Vec3 point, double speed, CallbackInfo callback) {
        RhythmGameVerification.lookTarget(point);
    }
}
