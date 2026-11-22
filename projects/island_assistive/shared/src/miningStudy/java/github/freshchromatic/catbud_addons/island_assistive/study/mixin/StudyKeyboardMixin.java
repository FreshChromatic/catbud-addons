package github.freshchromatic.catbud_addons.island_assistive.study.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningStudyClient;
import github.freshchromatic.catbud_addons.island_assistive.StudyInput;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class StudyKeyboardMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void input(long window, int action, KeyEvent key, CallbackInfo callback) {
        MiningStudyClient.nativeInput(new StudyInput.Key(action, key));
    }
}
