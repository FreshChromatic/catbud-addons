package github.freshchromatic.catbud_addons.monster_wave.mixin;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftFrameAccessor {

    @Invoker("renderFrame")
    void monsterWave$renderFrame(boolean advanceGameTime);

}
