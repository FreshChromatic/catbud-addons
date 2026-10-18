package github.freshchromatic.catbud_addons.monster_wave.mixin;

import github.freshchromatic.catbud_addons.monster_wave.MonsterWaveClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftDisconnectMixin {

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void monsterWave$showDisconnectBeforeCleanup(Screen screen, boolean keepPacks, boolean stopSound, CallbackInfo ci) {
        MonsterWaveClient.beforeWorldCleanup(screen);
    }
}
