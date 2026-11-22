package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import github.freshchromatic.catbud_addons.mining_study.StudyJournal;
import github.freshchromatic.catbud_addons.mining_study.StudyPacket;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import github.freshchromatic.catbud_addons.island_assistive.study.mixin.StudySessionAccess;

final class StudyTelemetry {

    private record Frame(long intervalNs, float yaw, float pitch, double x, double y, double z, String state,
        boolean focused, boolean screenOpen, boolean mouseGrabbed, int slot, boolean attack, boolean destroying,
        boolean targetHit) {}

    private record State(String state, String reason, boolean attack, boolean destroying) {}

    private record End(StudyPacket server, long frames, double peakSpeed, double peakAcceleration) {}

    private record NativeInput(StudyInput input, String state, boolean focused, boolean screenOpen,
        boolean mouseGrabbed) {}

    private final Path output;

    private StudyJournal journal;
    private UUID         run;
    private long         lastFrame;
    private long         frames;
    private double       peakSpeed;
    private double       peakAcceleration;
    private String       lastState;
    private boolean      sampling;

    StudyTelemetry(Path output) {
        this.output = output;
    }

    void open(StudyPacket packet, String modelFreeze, String runtimeBuild) {
        run = packet.run();
        frames = lastFrame = 0;
        peakSpeed = peakAcceleration = 0;
        lastState = "";
        sampling = false;
        try {
            journal = new StudyJournal(output.resolve(run.toString()).resolve("client.jsonl"));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        event("metadata", packet);
        event("model_freeze", modelFreeze);
        event("runtime_build", runtimeBuild);
    }

    String identity() {
        return run == null ? "manual-study" : run.toString();
    }

    boolean open() {
        return journal != null;
    }

    void begin(StudyPacket packet) {
        event("begin", packet);
        sampling = true;
    }

    void end(StudyPacket packet) {
        event("end", new End(packet, frames, peakSpeed, peakAcceleration));
        close();
    }

    void event(String type, Object data) {
        if (journal != null) {
            journal.event(type, data);
        }
    }

    void motionLimits(double speed, double acceleration) {
        peakSpeed = Math.max(peakSpeed, speed);
        peakAcceleration = Math.max(peakAcceleration, acceleration);
    }

    void nativeInput(StudyInput input, Minecraft client, BlockMiningSession mining) {
        event(input.eventType(), new NativeInput(input, mining.state().name(), client.isWindowActive(), ClientPlatform
            .screen(client) != null, client.mouseHandler.isMouseGrabbed()));
    }

    void tick(Minecraft client, BlockMiningSession mining) {
        if (journal == null) {
            return;
        }
        String state = mining.state().name();
        if (!state.equals(lastState)) {
            event("state", new State(state, mining.status().getString(), client.options.keyAttack.isDown(),
                client.gameMode != null && client.gameMode.isDestroying()));
            lastState = state;
        }
        journal.flush();
    }

    void frame(Minecraft client, BlockMiningSession mining) {
        if (!sampling || journal == null || client.player == null || client.gameMode == null) {
            return;
        }
        long now = System.nanoTime();
        long interval = lastFrame == 0 ? 0 : now - lastFrame;
        lastFrame = now;
        frames++;
        var target = ((StudySessionAccess) (Object) mining).study$target();
        var hit = client.player.pick(client.player.blockInteractionRange(), 1, false);
        boolean targetHit = hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && block
            .getBlockPos().equals(target);
        event("frame", new Frame(interval, client.player.getYRot(), client.player.getXRot(), client.player.getX(),
            client.player.getY(), client.player.getZ(), mining.state().name(), client.isWindowActive(), ClientPlatform
                .screen(client) != null, client.mouseHandler.isMouseGrabbed(), client.player.getInventory()
                    .getSelectedSlot(), client.options.keyAttack.isDown(), client.gameMode.isDestroying(), targetHit));
    }

    void close() {
        sampling = false;
        if (journal == null) {
            return;
        }
        try {
            journal.close();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        journal = null;
    }
}
