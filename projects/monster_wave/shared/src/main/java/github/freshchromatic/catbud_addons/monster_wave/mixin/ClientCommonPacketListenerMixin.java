package github.freshchromatic.catbud_addons.monster_wave.mixin;

import github.freshchromatic.catbud_addons.monster_wave.MonsterWaveClient;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerMixin {

    @Shadow
    @Final protected Connection connection;

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void monsterWave$cleanupStarted(DisconnectionDetails details, CallbackInfo ci) {
        MonsterWaveClient.disconnectCleanupStarted(connection);
    }

    @Inject(method = "onDisconnect", at = @At("TAIL"))
    private void monsterWave$cleanupFinished(DisconnectionDetails details, CallbackInfo ci) {
        MonsterWaveClient.disconnectCleanupFinished(connection);
    }
}
