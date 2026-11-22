package github.freshchromatic.catbud_addons.island_assistive.rhythm.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningAttackController;
import github.freshchromatic.catbud_addons.island_assistive.RhythmGameVerification;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MiningAttackController.class, remap = false)
abstract class RhythmAttackMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void rhythm$attack(BlockPos target, boolean cooldown, CallbackInfoReturnable<Boolean> callback) {
        RhythmGameVerification.attackAttempt(target);
    }
}
