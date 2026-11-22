package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import github.freshchromatic.catbud_addons.island_assistive.AimTargetTransfer.Box;
import github.freshchromatic.catbud_addons.island_assistive.NeuralObservation.AngularVelocity;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

final class NeuralCorrectionRecorder {

    record Parent(String file, String sha256) {}

    private record Target(int x, int y, int z) {}

    private record Header(int schema, String source, String purpose, Parent parent, Target target, int durationSeconds,
        String dimension) {}

    private record Frame(long sequence, long time, boolean eligible, boolean targetHit,
        NeuralObservation.State observation, Box region) {}

    private record End(String reason, long frames) {}

    private final Gson        gson  = new Gson();
    private final NeuralScene scene = new NeuralScene();

    private BufferedWriter writer;
    private BlockPos       target;
    private ClientLevel    world;
    private long           sequence;
    private long           started;
    private long           readyAt;
    private long           duration;

    boolean running() {
        return writer != null;
    }

    void arm(Path directory, BlockPos target, Parent parent, NeuralScene.Continuity continuity, int seconds)
        throws IOException {
        finish("replaced");
        Files.createDirectories(directory);
        writer = Files.newBufferedWriter(directory.resolve(UUID.randomUUID() + ".jsonl"));
        this.target = target.immutable();
        sequence = started = readyAt = 0;
        duration = seconds * 1_000_000_000L;
        scene.resume(continuity);
        world = Minecraft.getInstance().level;
        String source = Boolean.getBoolean("catbud.neural.verifyCorrection") ? "verification" : "human_correction";
        append(new Header(1, source, "manual angular recovery after a neural policy deviation", parent, new Target(
            target.getX(), target.getY(), target.getZ()), seconds, world.dimension().identifier().toString()));
        message("Correction armed. After chat closes, aim naturally at the target; automatic mining is stopped.");
    }

    void frame(Minecraft client, long now, boolean automated) {
        if (!running()) {
            return;
        }
        if (automated) {
            finish("automated_control");
            message("Correction rejected: automatic mining was enabled.");
            return;
        }
        if (client.player == null || client.level == null) {
            finish("no_world");
            return;
        }
        if (client.level != world) {
            finish("world_changed");
            return;
        }
        boolean eligible = client.isWindowActive() && client.mouseHandler.isMouseGrabbed() && ClientPlatform.screen(
            client) == null && !client.isPaused() && client.getCameraEntity() == client.player && client.player
                .isAlive() && client.level.hasChunkAt(target);
        if (started == 0) {
            if (!eligible) {
                readyAt = 0;
                return;
            }
            if (readyAt == 0) {
                readyAt = now + 500_000_000;
                return;
            }
            if (now < readyAt) {
                return;
            }
            started = now;
            ClientPlatform.actionbar(client, Component.literal("Correction recording: move the mouse naturally"));
        }
        if (now - started >= duration) {
            finish("duration_complete");
            message("Correction saved. Automatic mining remains stopped.");
            return;
        }
        var observation = scene.observe(client, target, new AngularVelocity(0, 0), now);
        var eye = client.player.getEyePosition();
        var hit = client.level.clip(new ClipContext(eye, eye.add(client.player.getViewVector(1).scale(client.player
            .blockInteractionRange())), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        append(new Frame(++sequence, now, eligible, hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(
            target), observation, scene.region()));
    }

    void finish(String reason) {
        if (writer == null) {
            return;
        }
        try {
            append(new End(reason, sequence));
            writer.close();
            writer = null;
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot close correction recording", error);
        }
    }

    private void append(Object value) {
        try {
            writer.write(gson.toJson(value));
            writer.newLine();
            if (sequence % 100 == 0) {
                writer.flush();
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot write correction recording", error);
        }
    }

    private void message(String message) {
        var client = Minecraft.getInstance();
        if (client.player != null) {
            ClientPlatform.chat(client, Component.literal(message));
        }
    }
}
