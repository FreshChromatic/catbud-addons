package github.freshchromatic.catbud_addons.public_warps.mixin;

import github.freshchromatic.catbud_addons.public_warps.PublicWarpsClient;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class WarpPacketMixin {

    @Inject(method = "sendCommand", at = @At("HEAD"))
    private void command(String command, CallbackInfo ci) {
        PublicWarpsClient.INSTANCE.command(command);
    }

    @Inject(method = "handleCommandSuggestions", at = @At("HEAD"), cancellable = true)
    private void suggestions(ClientboundCommandSuggestionsPacket p, CallbackInfo ci) {
        // HEAD runs before PacketUtils' thread guard. Reserve our IDs and process on the client
        // thread.
        if (p.id() >= 0x6C000000) {
            net.minecraft.client.Minecraft.getInstance().execute(() -> PublicWarpsClient.INSTANCE.suggestions(p));
            ci.cancel();
        }
    }

    @Inject(method = "handleBossUpdate", at = @At("TAIL"))
    private void boss(ClientboundBossEventPacket p, CallbackInfo ci) {
        p.dispatch(new ClientboundBossEventPacket.Handler() {

            @Override
            public void add(java.util.UUID id, Component name, float progress, net.minecraft.world.BossEvent.BossBarColor color, net.minecraft.world.BossEvent.BossBarOverlay overlay, boolean dark, boolean music, boolean fog) {
                PublicWarpsClient.INSTANCE.boss(id, name);
            }

            @Override
            public void updateName(java.util.UUID id, Component name) {
                PublicWarpsClient.INSTANCE.boss(id, name);
            }

            @Override
            public void updateProgress(java.util.UUID id, float progress) {
                PublicWarpsClient.INSTANCE.bossProgress(id, progress);
            }

            @Override
            public void remove(java.util.UUID id) {
                PublicWarpsClient.INSTANCE.bossRemoved(id);
            }
        });
    }

    @Inject(method = "setActionBarText", at = @At("TAIL"))
    private void action(ClientboundSetActionBarTextPacket p, CallbackInfo ci) {
        PublicWarpsClient.INSTANCE.message(p.text(), false, true);
    }

    @Inject(method = "handleSystemChat", at = @At("TAIL"))
    private void chat(ClientboundSystemChatPacket p, CallbackInfo ci) {
        PublicWarpsClient.INSTANCE.message(p.content(), false, p.overlay());
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void move(ClientboundPlayerPositionPacket p, CallbackInfo ci) {
        PublicWarpsClient.INSTANCE.correction();
    }
}
