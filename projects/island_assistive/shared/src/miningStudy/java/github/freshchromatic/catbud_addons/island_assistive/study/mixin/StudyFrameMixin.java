package github.freshchromatic.catbud_addons.island_assistive.study.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningStudyClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class StudyFrameMixin {

    @Inject(method = "runTick", at = @At("HEAD"))
    private void study$frame(boolean renderLevel, CallbackInfo callback) {
        MiningStudyClient.frame();
    }
}
