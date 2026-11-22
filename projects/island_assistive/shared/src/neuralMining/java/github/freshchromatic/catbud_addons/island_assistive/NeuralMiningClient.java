package github.freshchromatic.catbud_addons.island_assistive;

import ai.onnxruntime.OrtException;
import com.google.gson.Gson;
import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Rotation;
import github.freshchromatic.catbud_addons.island_assistive.NeuralObservation.AngularVelocity;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

public final class NeuralMiningClient implements ClientModInitializer {

    private record Trace(long time, long episode, NeuralObservation.State observation, NeuralAction.Step action) {}

    private static NeuralMiningClient instance;

    private final NeuralScene              scene      = new NeuralScene();
    private final Gson                     gson       = new Gson();
    private final NeuralCorrectionRecorder correction = new NeuralCorrectionRecorder();

    private Path                   latestTrace;
    private BlockPos               latestTarget;
    private ClientLevel            latestWorld;
    private NeuralScene.Continuity latestContinuity;

    private NeuralPolicy    policy;
    private BufferedWriter  journal;
    private BlockPos        target;
    private Random          random;
    private AngularVelocity velocity = new AngularVelocity(0, 0);
    private long            lastStep;
    private long            steps;
    private long            limited;
    private long            episode;
    private boolean         enabled;
    private boolean         movementAim;

    @Override
    public void onInitializeClient() {
        instance = this;
        NeuralMiningCommands.register(this);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> disable());
    }

    public static void begin(BlockPos target) {
        if (instance == null || !instance.enabled) {
            return;
        }
        instance.reset();
        instance.target = target.immutable();
        instance.latestTarget = target.immutable();
        instance.latestWorld = Minecraft.getInstance().level;
    }

    public static void look(double speed) {
        if (instance != null) {
            boolean movement = speed != 180;
            if (movement != instance.movementAim) {
                var target = instance.target;
                instance.reset();
                instance.target = target;
                instance.movementAim = movement;
            }
        }
    }

    public static void resetMotion() {
        if (instance != null) {
            instance.reset();
        }
    }

    public static void clearAim() {
        if (instance != null) {
            instance.movementAim = false;
        }
    }

    public static boolean controlsAim() {
        return instance != null && instance.enabled && !instance.movementAim && instance.target != null;
    }

    public static MiningLookMotion.Rotation frame(long now) {
        return instance.advance(now);
    }

    public static void captureCorrection() {
        if (instance == null || !instance.correction.running()) {
            return;
        }
        var client = Minecraft.getInstance();
        var session = ((MiningClientAccess) client).islandAssistive$mining();
        instance.correction.frame(client, System.nanoTime(), instance.enabled || session != null && session.running());
    }

    int enable(boolean sample) {
        if (FabricLoader.getInstance().isModLoaded("catbud_mining_capture")) {
            message("Neural mode requires an isolated client without the human capture mod.");
            return 0;
        }
        disable();
        try {
            var model = Path.of(requiredProperty("catbud.neural.model"));
            var metadata = Path.of(requiredProperty("catbud.neural.metadata"));
            var definition = gson.fromJson(Files.readString(metadata), ModelIdentity.class);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(
                model)));
            if (definition == null || !hash.equals(definition.onnx_sha256())) {
                throw new IllegalArgumentException("Neural model hash differs from metadata");
            }
            policy = new NeuralPolicy(model, metadata);
            var output = Path.of(requiredProperty("catbud.neural.output"));
            Files.createDirectories(output);
            latestTrace = output.resolve(UUID.randomUUID() + ".jsonl");
            journal = Files.newBufferedWriter(latestTrace);
            latestTarget = null;
            latestWorld = null;
            latestContinuity = null;
            long seed = UUID.randomUUID().getMostSignificantBits();
            random = sample ? new Random(seed) : null;
            append(Map.of("source", "neural", "model_sha256", hash, "sampling", sample, "seed", seed, "step_seconds",
                .02, "fallback", false, "status", "DEVELOPMENT_ONLY", "scope",
                "target aim; movement planner excluded"));
            enabled = true;
            steps = limited = 0;
            message("Neural research mode: " + (sample ? "sample" : "mean") + ". Start mining separately.");
            return 1;
        } catch (IOException | UncheckedIOException | OrtException | IllegalArgumentException
            | NoSuchAlgorithmException error) {
            LoggerFactory.getLogger(NeuralMiningClient.class).error("Cannot enable neural research mode", error);
            disable();
            message("Neural mode failed: " + error.getMessage());
            return 0;
        }
    }

    private record ModelIdentity(String onnx_sha256) {}

    int probe(BlockPos target) {
        var client = Minecraft.getInstance();
        if (!enabled || client.level == null || client.player == null) {
            message("Enable neural research mode in a world before starting a probe.");
            return 0;
        }
        var session = ((MiningClientAccess) client).islandAssistive$mining();
        session.configure(false, .5, 180, true);
        session.select();
        session.select(new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false));
        session.start();
        return session.running() ? 1 : 0;
    }

    int correct(int seconds) {
        var client = Minecraft.getInstance();
        if (steps == 0 || latestTrace == null || latestTarget == null || latestWorld != client.level
            || client.player == null) {
            message("Run a neural probe in this world before recording a correction.");
            return 0;
        }
        disable();
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(
                latestTrace)));
            correction.arm(latestTrace.getParent().resolve("corrections"), latestTarget,
                new NeuralCorrectionRecorder.Parent(latestTrace.getFileName().toString(), hash), latestContinuity,
                seconds);
            return 1;
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot arm correction recording", error);
        }
    }

    int verifyAutomaticControl() {
        if (!Boolean.getBoolean("catbud.neural.verifyCorrection") || !correction.running()) {
            return 0;
        }
        var client = Minecraft.getInstance();
        var session = ((MiningClientAccess) client).islandAssistive$mining();
        session.start();
        return session.running() ? 1 : 0;
    }

    private MiningLookMotion.Rotation advance(long now) {
        var client = Minecraft.getInstance();
        if (lastStep != 0 && now - lastStep < 20_000_000) {
            return new MiningLookMotion.Rotation(client.player.getYRot(), client.player.getXRot());
        }
        if (lastStep != 0 && now - lastStep > 100_000_000) {
            reset();
            message("Neural mining stopped: observation gap exceeded 100 ms.");
            ((MiningClientAccess) client).islandAssistive$mining().stop();
            return null;
        }
        try {
            var observation = scene.observe(client, target, velocity, now);
            latestContinuity = scene.continuity();
            var distribution = policy.infer(NeuralObservation.encode(observation, policy.definition()
                .observationVersion()));
            var action = NeuralAction.advance(observation.rotation(), velocity, NeuralAction.select(distribution,
                random));
            var rotation = new Rotation((float) action.rotation().yaw(), (float) action.rotation().pitch());
            velocity = new AngularVelocity((rotation.yaw() - observation.rotation().yaw()) / .02, (rotation.pitch()
                - observation.rotation().pitch()) / .02);
            append(new Trace(now, episode, observation, new NeuralAction.Step(rotation, action.requested(), velocity,
                action.limited(), action.pitchLimited())));
            steps++;
            limited += action.limited() || action.pitchLimited() ? 1 : 0;
            lastStep = lastStep == 0 ? now : lastStep + 20_000_000;
            return new MiningLookMotion.Rotation((float) rotation.yaw(), (float) rotation.pitch());
        } catch (OrtException | IllegalArgumentException | UncheckedIOException error) {
            LoggerFactory.getLogger(NeuralMiningClient.class).error("Neural inference stopped mining", error);
            disable();
            return null;
        }
    }

    private void reset() {
        episode++;
        target = null;
        movementAim = false;
        velocity = new AngularVelocity(0, 0);
        lastStep = 0;
        scene.reset();
        if (policy != null) {
            policy.reset();
        }
    }

    int disable() {
        enabled = false;
        correction.finish("stopped");
        var client = Minecraft.getInstance();
        var mining = ((MiningClientAccess) client).islandAssistive$mining();
        if (mining != null) {
            mining.stop();
        }
        reset();
        try {
            if (journal != null) {
                journal.close();
                journal = null;
            }
            if (policy != null) {
                policy.close();
                policy = null;
            }
        } catch (IOException | OrtException error) {
            throw new IllegalStateException("Cannot close neural research session", error);
        }
        return 1;
    }

    int status() {
        message("Neural research mode: " + enabled + ", steps=" + steps + ", limited=" + limited + ", correction="
            + correction.running());
        return 1;
    }

    private void append(Object value) {
        try {
            journal.write(gson.toJson(value));
            journal.newLine();
            if (steps % 50 == 0) {
                journal.flush();
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot write neural trace", error);
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    private void message(String message) {
        var client = Minecraft.getInstance();
        if (client.player != null) {
            ClientPlatform.chat(client, Component.literal(message));
        }
    }
}
