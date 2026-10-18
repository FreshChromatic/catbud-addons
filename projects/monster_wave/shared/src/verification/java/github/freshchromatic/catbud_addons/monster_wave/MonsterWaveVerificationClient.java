package github.freshchromatic.catbud_addons.monster_wave;

import github.freshchromatic.catbud_addons.catbud_core.CatbudCore;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MonsterWaveVerificationClient implements ClientModInitializer {

    private boolean done;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (done || !client.isGameLoadFinished()) {
                return;
            }
            done = true;
            var results = new ArrayList<String>();
            try {
                verify(results);
                Files.write(client.gameDirectory.toPath().resolve("monster-wave-verification.txt"), results);
                client.stop();
            } catch (Throwable failure) {
                results.add("FAIL: " + failure);
                try {
                    Files.write(client.gameDirectory.toPath().resolve("monster-wave-verification.txt"), results);
                } catch (Exception writeFailure) {
                    failure.addSuppressed(writeFailure);
                }
                throw new IllegalStateException("Monster Wave verification failed", failure);
            }
        });
    }

    private static void verify(List<String> results) throws Exception {
        List<String> hooks = Arrays.stream(ClientPacketListener.class.getDeclaredMethods()).map(method -> method
            .getName()).toList();
        for (String hook : List.of("bossbar", "health", "teleport", "respawn", "title", "subtitle", "actionbar",
            "systemChat", "titleTiming", "titleClear")) {
            check(hooks.stream().anyMatch(name -> name.endsWith("monsterWave$" + hook)), "Mixin applied: " + hook,
                results);
        }
        List<String> cleanupHooks = Arrays.stream(ClientCommonPacketListenerImpl.class.getDeclaredMethods()).map(
            method -> method.getName()).toList();
        for (String hook : List.of("cleanupStarted", "cleanupFinished")) {
            check(cleanupHooks.stream().anyMatch(name -> name.endsWith("monsterWave$" + hook)), "Mixin applied: "
                + hook, results);
        }
        var addon = CatbudCore.addons().stream().filter(value -> value.id().equals("monster_wave")).findFirst()
            .orElseThrow();
        check(addon.store().current().bool("enabled"), "Registered settings default enabled", results);
        check(addon.status().get().stream().anyMatch(row -> row.section() != null),
            "Manual controls section registered", results);
        check(!Component.translatable("status.monster_wave.name").getString().equals("status.monster_wave.name"),
            "Language resources loaded", results);

        var session = MonsterWaveClient.session();
        var identity = new MonsterWaveSession.ConnectionIdentity("verification.example", new UUID(0, 1));
        session.connectionJoined(identity);
        Object lobby = new Object();
        Object arena = new Object();
        session.tick(new MonsterWaveSession.Observation(true, true, lobby, "minecraft:overworld", 0, 64, 0, 20));
        var bar = new LerpingBossEvent(UUID.randomUUID(), Component.translatable(MonsterWaveSignals.QUEUE, Component
            .translatable(MonsterWaveSignals.WAVE), "1"), 1, BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.PROGRESS, false, false, false);
        MonsterWaveClient.bossbar(ClientboundBossEventPacket.createAddPacket(bar));
        check(session.phase() == MonsterWaveSession.Phase.QUEUED, "Queue packet identified", results);
        bar.setName(Component.translatable(MonsterWaveSignals.READY));
        MonsterWaveClient.bossbar(ClientboundBossEventPacket.createUpdateNamePacket(bar));
        check(session.phase() == MonsterWaveSession.Phase.READY, "Ready packet identified", results);
        MonsterWaveClient.teleport("position_packet");
        session.tick(new MonsterWaveSession.Observation(true, true, arena, "minecraft:custom", 0, 64, 0, 4));
        check(session.active(), "Active at exactly four health points", results);
        bar.setName(Component.translatable(MonsterWaveSignals.START));
        MonsterWaveClient.bossbar(ClientboundBossEventPacket.createUpdateNamePacket(bar));
        bar.setName(Component.translatable(MonsterWaveSignals.EXIT));
        MonsterWaveClient.bossbar(ClientboundBossEventPacket.createUpdateNamePacket(bar));
        bar.setProgress(0.25F);
        MonsterWaveClient.bossbar(ClientboundBossEventPacket.createUpdateProgressPacket(bar));
        bar.setColor(BossEvent.BossBarColor.BLUE);
        MonsterWaveClient.bossbar(ClientboundBossEventPacket.createUpdateStylePacket(bar));
        bar.setDarkenScreen(true);
        MonsterWaveClient.bossbar(ClientboundBossEventPacket.createUpdatePropertiesPacket(bar));
        for (var channel : RunEvent.Channel.values()) {
            MonsterWaveClient.text(channel, Component.translatable(MonsterWaveSignals.EXIT),
                channel == RunEvent.Channel.ACTIONBAR);
        }
        MonsterWaveClient.titleTiming(10, 70, 20);
        MonsterWaveClient.titleClear(true);
        check(session.active(), "Ready exit retains protection", results);
        session.tick(new MonsterWaveSession.Observation(true, true, arena, "minecraft:custom", 0, 64, 0, 3.5F));
        check(session.lastSummary().reason() == RunEvent.EndReason.LOW_HEALTH, "Low health terminates with LOW_HEALTH",
            results);
        check(session.lastSink() == DisabledRunSink.INSTANCE && session.lastSink().location() == null,
            "Run recording disabled", results);
        var connection = new Connection(PacketFlow.CLIENTBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        check(connection.isConnected(), "Test transport connected", results);
        MonsterWavePlatform.disconnect(connection);
        check(!connection.isConnected(), "Disconnect adapter closes transport immediately", results);
        verifyCleanupDisplay(connection, results);
        channel.finishAndReleaseAll();
        verifyDelayedTransportClose(results);
        session.connectionReset();
        check(session.mode() == MonsterWaveSession.Mode.AUTO, "Reconnect restores automatic detection", results);
        check(session.awaitingResume(), "Disconnected active custom run awaits recovery", results);
        session.connectionJoined(identity);
        session.tick(new MonsterWaveSession.Observation(true, true, new Object(), "minecraft:custom", 0, 64, 0, 4));
        check(session.active(), "Rejoining custom restores protection without queue signals", results);
        session.tick(new MonsterWaveSession.Observation(true, true, arena, "minecraft:custom", 0, 64, 0, 3));
        check(session.lastSummary().reason() == RunEvent.EndReason.LOW_HEALTH,
            "Resumed protection reacts to low health", results);
        check(session.lastSink() == DisabledRunSink.INSTANCE, "Resumed run recording disabled", results);
        session.connectionJoined(identity);
        session.tick(new MonsterWaveSession.Observation(true, true, lobby, "minecraft:overworld", 0, 64, 0, 3));
        check(!session.active() && !session.awaitingResume(), "Rejoining outside custom clears recovery", results);
        results.add("PASS: all client checks");
    }

    private static void verifyDelayedTransportClose(List<String> results) throws Exception {
        var delayed = new DelayedClose();
        var connection = new Connection(PacketFlow.CLIENTBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(delayed, connection);
        var returned = new CountDownLatch(1);
        var blocked = new AtomicBoolean();
        Thread watchdog = new Thread(() -> {
            try {
                if (delayed.intercepted.await(5, TimeUnit.SECONDS) && !returned.await(2, TimeUnit.SECONDS)) {
                    blocked.set(true);
                    delayed.complete();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "monster-wave-close-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            try {
                MonsterWavePlatform.disconnect(connection);
            } finally {
                returned.countDown();
            }
            check(!blocked.get() && delayed.intercepted.getCount() == 0 && channel.isOpen(),
                "Disconnect request returns while transport close is pending", results);
            check(connection.getDisconnectionDetails() != null && MonsterWaveSignals.keys(connection
                .getDisconnectionDetails().reason()).contains("disconnect.monster_wave.low_health"),
                "Disconnect reason is published before close completion", results);
            check(!channel.config().isAutoRead(), "Inbound reads stop while close is pending", results);
            delayed.complete();
            check(!connection.isConnected(), "Delayed transport closes after release", results);
        } finally {
            delayed.complete();
            channel.finishAndReleaseAll();
            watchdog.join(1000);
        }
    }

    private static void verifyCleanupDisplay(Connection connection, List<String> results) throws Exception {
        MonsterWaveClient.disconnectCleanupStarted(connection);
        var screen = new net.minecraft.client.gui.screens.DisconnectedScreen(
            new net.minecraft.client.gui.screens.TitleScreen(), Component.literal("Monster Wave verification"),
            Component.literal("Disconnected; cleaning up world"));
        MonsterWaveClient.beforeWorldCleanup(screen);
        check(github.freshchromatic.catbud_addons.catbud_core.ClientPlatform.screen(Minecraft.getInstance()) == screen,
            "Disconnect screen presented before world cleanup", results);
        check(screen.children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance).map(
            net.minecraft.client.gui.components.Button.class::cast).noneMatch(button -> button.active),
            "Disconnect navigation disabled until cleanup completes", results);
        var graph = new net.minecraft.client.renderer.SectionOcclusionGraph();
        var field = graph.getClass().getDeclaredField("fullUpdateTask");
        field.setAccessible(true);
        var task = new java.util.concurrent.CompletableFuture<Void>();
        field.set(graph, task);
        java.util.concurrent.CompletableFuture.delayedExecutor(150, TimeUnit.MILLISECONDS).execute(() -> task.complete(
            null));
        graph.waitAndReset(null);
        check(task.isDone(), "World visibility cleanup completes while rendering disconnected UI", results);
        MonsterWaveClient.disconnectCleanupFinished(connection);
        check(screen.children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance).map(
            net.minecraft.client.gui.components.Button.class::cast).anyMatch(button -> button.active),
            "Disconnect navigation restored after cleanup", results);
    }

    private static final class DelayedClose extends io.netty.channel.ChannelOutboundHandlerAdapter {

        private record Pending(io.netty.channel.ChannelHandlerContext context,
            io.netty.channel.ChannelPromise promise) {}

        private final CountDownLatch           intercepted = new CountDownLatch(1);
        private final AtomicReference<Pending> pending     = new AtomicReference<>();

        @Override
        public void close(io.netty.channel.ChannelHandlerContext context, io.netty.channel.ChannelPromise promise) {
            pending.set(new Pending(context, promise));
            intercepted.countDown();
        }

        private void complete() {
            var close = pending.getAndSet(null);
            if (close != null) {
                close.context().close(close.promise());
            }
        }
    }

    private static void check(boolean condition, String description, List<String> results) {
        if (!condition) {
            throw new AssertionError(description);
        }
        results.add("PASS: " + description);
    }
}
