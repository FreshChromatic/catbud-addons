package github.freshchromatic.catbud_addons.monster_wave;

import com.mojang.serialization.JsonOps;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

final class MonsterWavePlatform {

    private static final LowHealthDisconnectTransport DISCONNECT = new LowHealthDisconnectTransport();

    private MonsterWavePlatform() {
    }

    static MonsterWaveSession.Observation observation() {
        var mc = Minecraft.getInstance();
        var listener = mc.getConnection();
        boolean connected = listener != null && listener.getConnection().isConnected();
        boolean multiplayer = connected && !mc.hasSingleplayerServer() && !listener.getConnection()
            .isMemoryConnection();
        if (mc.level == null || mc.player == null) {
            return new MonsterWaveSession.Observation(connected, multiplayer, null, "", 0, 0, 0, Float.NaN);
        }
        var pos = mc.player.position();
        return new MonsterWaveSession.Observation(connected, multiplayer, mc.level, ClientPlatform.dimension(mc)
            .toString(), pos.x, pos.y, pos.z, mc.player.getHealth());
    }

    static void disconnect() {
        disconnect(RunEvent.EndReason.LOW_HEALTH);
    }

    static void disconnect(RunEvent.EndReason reason) {
        var listener = Minecraft.getInstance().getConnection();
        if (listener != null) {
            DISCONNECT.disconnect(listener.getConnection(), Component.translatable(
                reason == RunEvent.EndReason.BURST_RISK ? "disconnect.monster_wave.burst_risk"
                    : "disconnect.monster_wave.low_health"));
        }
    }

    static void disconnect(Connection connection) {
        DISCONNECT.disconnect(connection, Component.translatable("disconnect.monster_wave.low_health"));
    }

    static void disconnectCleanupStarted(Connection connection) {
        DISCONNECT.cleanupStarted(connection);
    }

    static void disconnectScreenInitialized(Screen screen) {
        DISCONNECT.screenInitialized(screen);
    }

    static void disconnectCleanupFinished(Connection connection) {
        DISCONNECT.cleanupFinished(connection);
    }

    static void beforeWorldCleanup(Screen screen) {
        DISCONNECT.beforeWorldCleanup(screen);
    }

    static Object awaitWorldCleanup(java.util.concurrent.Future<?> task, boolean unloading) throws InterruptedException,
        java.util.concurrent.ExecutionException {
        return DISCONNECT.awaitWorldCleanup(task, unloading);
    }

    static MonsterWaveSession.ConnectionIdentity connectionIdentity() {
        var client = Minecraft.getInstance();
        var listener = client.getConnection();
        if (listener == null || client.hasSingleplayerServer() || listener.getConnection().isMemoryConnection()) {
            return null;
        }
        var server = client.getCurrentServer();
        String address = server == null ? listener.getConnection().getRemoteAddress().toString() : server.ip;
        return new MonsterWaveSession.ConnectionIdentity(address.strip().toLowerCase(java.util.Locale.ROOT), client
            .getUser().getProfileId());
    }

    static RunEvent.TextValue text(Component text) {
        String encoded = null;
        String error = null;
        try {
            var listener = Minecraft.getInstance().getConnection();
            var ops = listener == null ? JsonOps.INSTANCE
                : listener.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            encoded = ComponentSerialization.CODEC.encodeStart(ops, text).getOrThrow().toString();
        } catch (RuntimeException failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        return new RunEvent.TextValue(text.getString(), encoded, MonsterWaveSignals.keys(text), error);
    }
}
