package github.freshchromatic.catbud_addons.island_assistive.study.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningStudyClient;
import github.freshchromatic.catbud_addons.island_assistive.StudyInput;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class StudyMouseMixin {

    @Inject(method = "onButton", at = @At("HEAD"))
    private void button(long window, MouseButtonInfo button, int action, CallbackInfo callback) {
        MiningStudyClient.nativeInput(new StudyInput.Button(button, action));
    }

    @Inject(method = "onMove", at = @At("HEAD"))
    private void move(long window, double x, double y, double dx, double dy, CallbackInfo callback) {
        MiningStudyClient.nativeInput(new StudyInput.Move(x, y, dx, dy));
    }

    @Inject(method = "onScroll", at = @At("HEAD"))
    private void scroll(long window, double x, double y, CallbackInfo callback) {
        MiningStudyClient.nativeInput(new StudyInput.Scroll(x, y));
    }
}
