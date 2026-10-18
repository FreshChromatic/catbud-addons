package github.freshchromatic.catbud_addons.monster_wave;

import github.freshchromatic.catbud_addons.monster_wave.mixin.ConnectionAccessor;
import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;

final class LowHealthDisconnectTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger("Catbud Monster Wave");

    private Attempt pending;

    private static final class Attempt {

        private final Connection connection;
        private final UUID       id             = UUID.randomUUID();
        private final long       requestedNanos = System.nanoTime();

        private boolean cleanupStarted;
        private boolean previewShown;

        private final List<AbstractWidget> suspendedControls = new ArrayList<>();

        private Attempt(Connection connection) {
            this.connection = connection;
        }

        private void log(String stage) {
            LOGGER.info("monster_wave_disconnect attempt={} stage={} since_request_ms={}", id, stage, (System.nanoTime()
                - requestedNanos) / 1_000_000.0);
        }
    }

    void disconnect(Connection connection, Component reason) {
        var attempt = new Attempt(connection);
        pending = attempt;
        var access = (ConnectionAccessor) connection;
        var channel = access.monsterWave$getChannel();
        if (channel == null) {
            connection.disconnect(reason);
            attempt.log("close_deferred_until_connected");
            return;
        }
        // Vanilla waits for close completion before saving the reason; publish it first when closing asynchronously.
        access.monsterWave$setDisconnectionDetails(new DisconnectionDetails(reason));
        connection.setReadOnly();
        channel.close().addListener(result -> {
            attempt.log(result.isSuccess() ? "transport_closed" : "transport_close_failed");
            if (!result.isSuccess()) {
                LOGGER.warn("Monster Wave transport close failed", result.cause());
            }
        });
        attempt.log("close_requested");
    }

    void cleanupStarted(Connection connection) {
        if (pending != null && pending.connection == connection) {
            pending.cleanupStarted = true;
            pending.log("world_cleanup_started");
        }
    }

    void beforeWorldCleanup(Screen screen) {
        var attempt = pending;
        if (attempt != null && attempt.cleanupStarted && !attempt.previewShown
            && screen instanceof DisconnectedScreen) {
            attempt.previewShown = true;
            Minecraft.getInstance().setScreenAndShow(screen);
            attempt.log("disconnect_screen_first_frame_shown");
        }
    }

    Object awaitWorldCleanup(Future<?> task, boolean unloading) throws InterruptedException, ExecutionException {
        var client = Minecraft.getInstance();
        var attempt = pending;
        if (attempt == null || !attempt.cleanupStarted || !attempt.previewShown || !unloading || client.level != null
            || !client.isSameThread() || !(ClientPlatform.screen(client) instanceof DisconnectedScreen)) {
            return task.get();
        }
        attempt.log("visibility_cleanup_wait_started");
        int[] frames = { 0 };
        try {
            // Preserve the worker's lifetime while refreshing only the disconnected UI on the render thread.
            return CleanupWait.await(task, () -> {
                DisconnectFrames.refresh(client);
                frames[0]++;
            });
        } finally {
            attempt.log("visibility_cleanup_wait_finished");
            LOGGER.info("monster_wave_disconnect attempt={} stage=visibility_cleanup_frames refreshed_frames={}",
                attempt.id, frames[0]);
        }
    }

    void screenInitialized(Screen screen) {
        if (pending != null && pending.cleanupStarted) {
            for (var child : screen.children()) {
                if (child instanceof AbstractWidget widget && widget.active) {
                    pending.suspendedControls.add(widget);
                    widget.active = false;
                }
            }
            pending.log("disconnect_screen_initialized");
        }
    }

    void cleanupFinished(Connection connection) {
        if (pending != null && pending.connection == connection) {
            pending.log("world_cleanup_finished");
            for (var control : pending.suspendedControls) {
                control.active = true;
            }
            pending = null;
        }
    }
}
