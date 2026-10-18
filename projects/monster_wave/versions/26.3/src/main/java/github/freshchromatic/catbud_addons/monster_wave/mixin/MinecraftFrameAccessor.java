package github.freshchromatic.catbud_addons.monster_wave.mixin;

import com.mojang.blaze3d.platform.SDLEventHandler;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftFrameAccessor {

    @Invoker("renderFrame")
    void monsterWave$renderFrame(boolean advanceGameTime);

    @Accessor("sdlEventHandler")
    SDLEventHandler monsterWave$getEventHandler();

}
