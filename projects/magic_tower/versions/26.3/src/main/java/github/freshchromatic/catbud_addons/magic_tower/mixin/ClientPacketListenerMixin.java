package github.freshchromatic.catbud_addons.magic_tower.mixin;

import github.freshchromatic.catbud_addons.magic_tower.CatbudAddonsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void catbud$onServerBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (CatbudAddonsClient.session().isActive() && Minecraft.getInstance().level != null) {
            CatbudAddonsClient.session().targets().onBlockChanged(Minecraft.getInstance().level, packet.getPos());
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void catbud$onServerSectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (CatbudAddonsClient.session().isActive() && Minecraft.getInstance().level != null) {
            packet.runUpdates((pos, state) ->
                CatbudAddonsClient.session().targets().onBlockChanged(Minecraft.getInstance().level, pos));
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void catbud$onServerChunkUpdate(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null)
            CatbudAddonsClient.onWorldChunkChanged(Minecraft.getInstance().level,
                packet.x(), packet.z());
    }

    @Inject(method = "handleBossUpdate", at = @At("TAIL"))
    private void catbud$onBossbarUpdate(ClientboundBossEventPacket packet, CallbackInfo ci) {
        packet.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void add(java.util.UUID id, net.minecraft.network.chat.Component name, float progress,
                            net.minecraft.world.BossEvent.BossBarColor color,
                            net.minecraft.world.BossEvent.BossBarOverlay overlay,
                            boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
                CatbudAddonsClient.session().onBossbarName(id, name);
            }

            @Override
            public void updateName(java.util.UUID id, net.minecraft.network.chat.Component name) {
                CatbudAddonsClient.session().onBossbarName(id, name);
            }
        });
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void catbud$onTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        CatbudAddonsClient.session().onTeleportPacket();
    }

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void catbud$onRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        CatbudAddonsClient.session().onTeleportPacket();
    }

    @Inject(method = "setTitleText", at = @At("TAIL"))
    private void catbud$onTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        CatbudAddonsClient.session().onMessage(packet.text(), "Title");
    }

    @Inject(method = "setSubtitleText", at = @At("TAIL"))
    private void catbud$onSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
        CatbudAddonsClient.session().onMessage(packet.text(), "Subtitle");
    }

    @Inject(method = "setActionBarText", at = @At("TAIL"))
    private void catbud$onActionbar(ClientboundSetActionBarTextPacket packet, CallbackInfo ci) {
        CatbudAddonsClient.session().onMessage(packet.text(), "Actionbar");
    }

    @Inject(method = "handleSystemChat", at = @At("TAIL"))
    private void catbud$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        CatbudAddonsClient.session().onMessage(packet.content(), "Chat");
    }
}

