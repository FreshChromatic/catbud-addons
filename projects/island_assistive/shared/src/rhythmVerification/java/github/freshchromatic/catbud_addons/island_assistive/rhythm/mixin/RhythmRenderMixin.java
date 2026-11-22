package github.freshchromatic.catbud_addons.island_assistive.rhythm.mixin;

import github.freshchromatic.catbud_addons.island_assistive.RhythmGameVerification;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class RhythmRenderMixin {

    @Inject(method = "update", at = @At("HEAD"))
    private void rhythm$prepare(CallbackInfo callback) {
        RhythmGameVerification.beforeFrame();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rhythm$capture(CallbackInfo callback) {
        RhythmGameVerification.captureFrame();
    }
}
