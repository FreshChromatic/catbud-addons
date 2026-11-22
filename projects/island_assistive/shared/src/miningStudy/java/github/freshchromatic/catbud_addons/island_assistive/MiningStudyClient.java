package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.mining_study.StudyPacket.Mode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import github.freshchromatic.catbud_addons.island_assistive.study.mixin.StudySessionAccess;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import com.mojang.brigadier.arguments.IntegerArgumentType;

public final class MiningStudyClient implements ClientModInitializer {

    private static MiningStudyClient active;

    private BlockMiningSession mining;
    private StudyMotionDriver  driver;
    private StudyTelemetry     telemetry;

    @Override
    public void onInitializeClient() {
        active = this;
        var client = Minecraft.getInstance();
        telemetry = new StudyTelemetry(Path.of(System.getProperty("catbud.study.output", client.gameDirectory.toPath()
            .resolve("mining-study").toString())));
        try {
            driver = new StudyMotionDriver(this::mining, telemetry);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        var experiment = new StudyExperiment(this::mining, driver, telemetry);
        PayloadTypeRegistry.clientboundPlay().register(StudyPayload.TYPE, StudyPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StudyPayload.TYPE, StudyPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(StudyPayload.TYPE, (payload, context) -> context.client().execute(
            () -> experiment.receive(payload.packet())));
        ClientTickEvents.END_CLIENT_TICK.register(experiment::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
            experiment.stop("disconnect");
            driver.mode(Mode.BASELINE);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> experiment.stop("client_stop"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(literal(
            "miningmotion").then(literal("on").executes(command -> experiment.switchMode(Mode.VARIED))).then(literal(
                "timed").executes(command -> experiment.switchMode(Mode.TIMED))).then(literal("off").executes(
                    command -> experiment.switchMode(Mode.BASELINE))).then(literal("stop").executes(command -> {
                        experiment.stop("command_stop");
                        return 1;
                    })).then(literal("probe").executes(command -> {
                        experiment.begin(Mode.VARIED, 600, false);
                        return 1;
                    })).then(literal("benchmark").executes(command -> {
                        experiment.begin(Mode.BASELINE, 12000, true);
                        return 1;
                    }).then(argument("ticks", IntegerArgumentType.integer(200, 18000)).executes(command -> {
                        experiment.begin(Mode.BASELINE, IntegerArgumentType.getInteger(command, "ticks"), true);
                        return 1;
                    }))).then(literal("status").executes(command -> {
                        StudyExperiment.message("轉向模式：" + driver.mode().name());
                        return 1;
                    }))));
    }

    private BlockMiningSession mining() {
        if (mining == null) {
            mining = FabricLoader.getInstance().getEntrypoints("client", ClientModInitializer.class).stream().filter(
                IslandAssistiveClient.class::isInstance).map(IslandAssistiveClient.class::cast).findFirst()
                .orElseThrow().mining();
        }
        return mining;
    }

    public static MiningLookMotion.Rotation rotation(float yaw, float pitch, double speed, double seconds) {
        return active == null ? null : active.driver.advance(yaw, pitch, speed, seconds);
    }

    public static void look(Vec3 point, double speed) {
        if (active != null) {
            active.driver.look(point, speed);
        }
    }

    public static void clearAim() {
        if (active != null) {
            active.driver.clearAim();
        }
    }

    public static void beginMotion() {
        if (active != null) {
            active.driver.begin();
        }
    }

    public static void releaseMotion() {
        if (active != null) {
            active.driver.reset();
        }
    }

    public static MiningLookMotion.Rotation reactiveFrame(long now) {
        return active == null ? null : active.driver.reactiveFrame(now);
    }

    public static void frame() {
        if (active != null && active.telemetry.open()) {
            active.telemetry.frame(Minecraft.getInstance(), active.mining());
        }
    }

    public static void nativeInput(StudyInput input) {
        if (active == null) {
            return;
        }
        var client = Minecraft.getInstance();
        if (active.telemetry.open()) {
            active.telemetry.nativeInput(input, client, active.mining());
        }
        if (active.driver.enabled() && active.mining().running() && ClientPlatform.screen(client) == null && client
            .isWindowActive() && client.mouseHandler.isMouseGrabbed() && client.getCameraEntity() == client.player
            && input.takesControl(client.options)) {
            ((StudySessionAccess) (Object) active.mining()).study$pause("manual");
        }
    }
}
