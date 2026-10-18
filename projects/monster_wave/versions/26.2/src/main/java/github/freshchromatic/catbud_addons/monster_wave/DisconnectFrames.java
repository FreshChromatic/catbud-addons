package github.freshchromatic.catbud_addons.monster_wave;

import github.freshchromatic.catbud_addons.monster_wave.mixin.MinecraftFrameAccessor;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;

final class DisconnectFrames {

    private DisconnectFrames() {
    }

    static void refresh(Minecraft client) {
        var frames = (MinecraftFrameAccessor) client;
        RenderSystem.pollEvents();
        frames.monsterWave$renderFrame(false);
    }
}
