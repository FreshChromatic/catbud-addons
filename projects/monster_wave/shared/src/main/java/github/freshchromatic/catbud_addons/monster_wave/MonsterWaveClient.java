package github.freshchromatic.catbud_addons.monster_wave;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;

import java.util.UUID;

public final class MonsterWaveClient implements ClientModInitializer {

    private static MonsterWaveSession session;
    private static String             version;
    private static String             minecraftVersion;

    static MonsterWaveSession session() {
        return session;
    }

    @Override
    public void onInitializeClient() {
        var loader = FabricLoader.getInstance();
        version = loader.getModContainer("catbud_monster_wave").orElseThrow().getMetadata().getVersion()
            .getFriendlyString();
        minecraftVersion = loader.getModContainer("minecraft").orElseThrow().getMetadata().getVersion()
            .getFriendlyString();
        session = new MonsterWaveSession(RunClock.SYSTEM, mode -> new MonsterWaveRun(UUID.randomUUID(), RunClock.SYSTEM,
            DisabledRunSink.INSTANCE, version, minecraftVersion, mode.name()), () -> MonsterWavePlatform.disconnect(
                session.lastSummary().reason()));
        MonsterWaveSettings.register(version);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof DisconnectedScreen) {
                MonsterWavePlatform.disconnectScreenInitialized(screen);
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> session.connectionJoined(
            MonsterWavePlatform.connectionIdentity()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> session.connectionReset());
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, parameters, receivedAt) -> text(
            RunEvent.Channel.PLAYER_CHAT, message, false));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> session.clientStopped());
    }

    public static void tick() {
        if (session != null) {
            session.tick(MonsterWavePlatform.observation());
        }
    }

    public static void disconnectCleanupStarted(Connection connection) {
        MonsterWavePlatform.disconnectCleanupStarted(connection);
    }

    public static void disconnectCleanupFinished(Connection connection) {
        MonsterWavePlatform.disconnectCleanupFinished(connection);
    }

    public static void beforeWorldCleanup(Screen screen) {
        MonsterWavePlatform.beforeWorldCleanup(screen);
    }

    public static Object awaitWorldCleanup(java.util.concurrent.Future<?> task, boolean unloading)
        throws InterruptedException, java.util.concurrent.ExecutionException {
        return MonsterWavePlatform.awaitWorldCleanup(task, unloading);
    }

    public static void teleport(String source) {
        if (session != null) {
            session.teleport(source);
        }
    }

    public static void text(RunEvent.Channel channel, Component text, boolean overlay) {
        if (session != null && session.phase() != MonsterWaveSession.Phase.IDLE) {
            session.event("text", new RunEvent.Text(channel, MonsterWavePlatform.text(text), overlay));
        }
    }

    public static void titleTiming(int fadeIn, int stay, int fadeOut) {
        if (session != null) {
            session.event("title_timing", new RunEvent.TitleTiming(fadeIn, stay, fadeOut));
        }
    }

    public static void titleClear(boolean reset) {
        if (session != null) {
            session.event("title_clear", new RunEvent.TitleClear(reset));
        }
    }

    public static void bossbar(ClientboundBossEventPacket packet) {
        if (session == null) {
            return;
        }
        var bars = session.bossbars();
        packet.dispatch(new ClientboundBossEventPacket.Handler() {

            @Override
            public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay, boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
                bars.add(id, new RunEvent.BossValue(MonsterWavePlatform.text(name), progress, color.name(), overlay
                    .name(), darkenScreen, playMusic, createWorldFog), MonsterWaveSignals.signal(name));
            }

            @Override
            public void remove(UUID id) {
                bars.remove(id);
            }

            @Override
            public void updateProgress(UUID id, float progress) {
                bars.update(id, "progress", v -> new RunEvent.BossValue(v.name(), progress, v.color(), v.overlay(), v
                    .darkenScreen(), v.playMusic(), v.createWorldFog()));
            }

            @Override
            public void updateName(UUID id, Component name) {
                bars.name(id, MonsterWavePlatform.text(name), MonsterWaveSignals.signal(name));
            }

            @Override
            public void updateStyle(UUID id, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
                bars.update(id, "style", v -> new RunEvent.BossValue(v.name(), v.progress(), color.name(), overlay
                    .name(), v.darkenScreen(), v.playMusic(), v.createWorldFog()));
            }

            @Override
            public void updateProperties(UUID id, boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
                bars.update(id, "properties", v -> new RunEvent.BossValue(v.name(), v.progress(), v.color(), v
                    .overlay(), darkenScreen, playMusic, createWorldFog));
            }
        });
    }
}
