package github.freshchromatic.catbud_addons.public_warps.mixin;

import github.freshchromatic.catbud_addons.public_warps.*;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(GuiRenderer.class)
public abstract class WarpGuiRendererMixin {

    @Shadow
    @Final
    @Mutable private Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> pictureInPictureRenderers;

    @Inject(method = "render", at = @At("TAIL"))
    private void trimWarpScenes(CallbackInfo ci) {
        WarpSceneRenderer.trimScenes();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void registerWarpScene(CallbackInfo ci) {
        pictureInPictureRenderers = new java.util.HashMap<>(pictureInPictureRenderers);
        pictureInPictureRenderers.put(WarpSceneState.class, new WarpSceneRenderer());
    }
}
