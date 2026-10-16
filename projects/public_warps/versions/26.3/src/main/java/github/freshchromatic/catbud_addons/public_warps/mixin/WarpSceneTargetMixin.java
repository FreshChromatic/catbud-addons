package github.freshchromatic.catbud_addons.public_warps.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PictureInPictureRenderer.class)
public interface WarpSceneTargetMixin {

    @Accessor("textureView")
    GpuTextureView catbud$colorTarget();

    @Accessor("depthTextureView")
    GpuTextureView catbud$depthTarget();
}
