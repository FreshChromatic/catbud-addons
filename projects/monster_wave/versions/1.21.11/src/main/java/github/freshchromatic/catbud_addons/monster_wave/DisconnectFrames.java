package github.freshchromatic.catbud_addons.monster_wave;

import github.freshchromatic.catbud_addons.monster_wave.mixin.MinecraftFrameAccessor;

import net.minecraft.client.Minecraft;

final class DisconnectFrames {

    private DisconnectFrames() {
    }

    static void refresh(Minecraft client) {
        var frames = (MinecraftFrameAccessor) client;
        frames.monsterWave$renderFrame(false);
    }
}
