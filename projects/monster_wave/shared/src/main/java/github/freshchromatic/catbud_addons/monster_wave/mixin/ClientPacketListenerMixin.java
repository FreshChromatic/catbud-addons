package github.freshchromatic.catbud_addons.monster_wave.mixin;

import github.freshchromatic.catbud_addons.monster_wave.MonsterWaveClient;
import github.freshchromatic.catbud_addons.monster_wave.RunEvent;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleBossUpdate", at = @At("TAIL"))
    private void monsterWave$bossbar(ClientboundBossEventPacket packet, CallbackInfo ci) {
        MonsterWaveClient.bossbar(packet);
    }

    @Inject(method = "handleSetHealth", at = @At("TAIL"))
    private void monsterWave$health(ClientboundSetHealthPacket packet, CallbackInfo ci) {
        MonsterWaveClient.tick();
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void monsterWave$teleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        MonsterWaveClient.teleport("position_packet");
    }

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void monsterWave$respawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        MonsterWaveClient.teleport("respawn_packet");
    }

    @Inject(method = "setTitleText", at = @At("TAIL"))
    private void monsterWave$title(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        MonsterWaveClient.text(RunEvent.Channel.TITLE, packet.text(), false);
    }

    @Inject(method = "setSubtitleText", at = @At("TAIL"))
    private void monsterWave$subtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
        MonsterWaveClient.text(RunEvent.Channel.SUBTITLE, packet.text(), false);
    }

    @Inject(method = "setActionBarText", at = @At("TAIL"))
    private void monsterWave$actionbar(ClientboundSetActionBarTextPacket packet, CallbackInfo ci) {
        MonsterWaveClient.text(RunEvent.Channel.ACTIONBAR, packet.text(), true);
    }

    @Inject(method = "handleSystemChat", at = @At("TAIL"))
    private void monsterWave$systemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        MonsterWaveClient.text(RunEvent.Channel.SYSTEM_CHAT, packet.content(), packet.overlay());
    }

    @Inject(method = "setTitlesAnimation", at = @At("TAIL"))
    private void monsterWave$titleTiming(ClientboundSetTitlesAnimationPacket packet, CallbackInfo ci) {
        MonsterWaveClient.titleTiming(packet.getFadeIn(), packet.getStay(), packet.getFadeOut());
    }

    @Inject(method = "handleTitlesClear", at = @At("TAIL"))
    private void monsterWave$titleClear(ClientboundClearTitlesPacket packet, CallbackInfo ci) {
        MonsterWaveClient.titleClear(packet.shouldResetTimes());
    }
}
