package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import github.freshchromatic.catbud_addons.mining_study.StudyPacket;
import github.freshchromatic.catbud_addons.mining_study.StudyPacket.Kind;
import github.freshchromatic.catbud_addons.mining_study.StudyPacket.Mode;
import java.util.UUID;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class StudyExperiment {

    private final Supplier<BlockMiningSession> mining;
    private final StudyMotionDriver            driver;
    private final StudyTelemetry               telemetry;

    private StudyPacket preparing;
    private StudyPacket request;
    private UUID        run;
    private Mode        mode = Mode.BASELINE;
    private int         readyTicks;
    private int         connectionTicks;
    private int         nextTicks;
    private int         pairedLimit;
    private boolean     connecting;
    private boolean     paired;
    private boolean     complete;

    StudyExperiment(Supplier<BlockMiningSession> mining, StudyMotionDriver driver, StudyTelemetry telemetry) {
        this.mining = mining;
        this.driver = driver;
        this.telemetry = telemetry;
    }

    int switchMode(Mode selected) {
        stop("mode_switch");
        driver.mode(selected);
        mode = selected;
        message("轉向模式：" + selected.name() + "；挖掘已停止，請自行啟動。");
        return 1;
    }

    void begin(Mode selected, int ticks, boolean paired) {
        stop("new_run");
        run = UUID.randomUUID();
        this.paired = paired;
        complete = false;
        pairedLimit = ticks;
        mode = selected;
        String configuredSeed = System.getProperty("catbud.study.seed");
        long seed = configuredSeed == null ? run.getMostSignificantBits() : Long.parseLong(configuredSeed);
        request = new StudyPacket(run, Kind.HELLO, selected, ticks, 0, seed, "verification");
        ClientPlayNetworking.send(new StudyPayload(request));
    }

    void receive(StudyPacket packet) {
        if (request == null) {
            return;
        }
        if (!packet.run().equals(run)) {
            return;
        }
        switch (packet.kind()) {
            case PREPARE -> {
                if (packet.mode() != request.mode()) {
                    throw new IllegalStateException("Unexpected study mode");
                }
                run = packet.run();
                preparing = packet;
                readyTicks = 0;
                driver.mode(packet.mode());
                driver.seed(packet.seed());
                telemetry.open(packet, driver.freeze(), driver.build());
            }
            case BEGIN -> {
                telemetry.begin(packet);
                var session = mining.get();
                session.configure(false, .5, 180, true);
                session.start();
                if (!session.running()) {
                    stop("start_refused:" + session.status().getString());
                }
            }
            case PROGRESS -> telemetry.event("server_progress", packet);
            case END -> {
                mining.get().stop();
                telemetry.tick(Minecraft.getInstance(), mining.get());
                telemetry.frame(Minecraft.getInstance(), mining.get());
                telemetry.end(packet);
                driver.mode(Mode.BASELINE);
                preparing = null;
                request = null;
                if (paired && mode == Mode.BASELINE && packet.detail().equals("duration_complete")) {
                    nextTicks = 60;
                } else {
                    complete = true;
                    message("研究試次結束：" + packet.detail());
                }
            }
            default -> throw new IllegalArgumentException("Unexpected server study message");
        }
    }

    void tick(Minecraft client) {
        String server = System.getProperty("catbud.study.server");
        if (server != null && !connecting && client.isGameLoadFinished() && ClientPlatform.screen(
            client) instanceof TitleScreen) {
            connecting = true;
            client.options.renderDistance().set(4);
            client.options.framerateLimit().set(60);
            client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            client.options.pauseOnLostFocus = false;
            ConnectScreen.startConnecting(ClientPlatform.screen(client), client, ServerAddress.parseString(server),
                new ServerData("Isolated mining study", server, ServerData.Type.OTHER), false, null);
        }
        if (client.player == null || client.level == null) {
            return;
        }
        if (server != null && request == null && nextTicks == 0 && !complete && ++connectionTicks == 60) {
            begin(Mode.BASELINE, Integer.getInteger("catbud.study.ticks", 12000), true);
        }
        if (nextTicks > 0 && --nextTicks == 0) {
            begin(Mode.VARIED, pairedLimit, true);
        }
        var session = mining.get();
        if (preparing != null && ++readyTicks == 40) {
            var target = new BlockPos(-3576, 65, -3576);
            session.select();
            session.select(new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false));
            ClientPlayNetworking.send(new StudyPayload(new StudyPacket(run, Kind.READY, mode, 0, 0, preparing.seed(),
                "ready")));
            preparing = null;
        }
        telemetry.tick(client, session);
    }

    void stop(String reason) {
        nextTicks = 0;
        paired = false;
        complete = true;
        mining.get().stop();
        if (telemetry.open()) {
            telemetry.event("client_stop", reason);
            telemetry.tick(Minecraft.getInstance(), mining.get());
            telemetry.frame(Minecraft.getInstance(), mining.get());
            if (ClientPlayNetworking.canSend(StudyPayload.TYPE) && run != null) {
                ClientPlayNetworking.send(new StudyPayload(new StudyPacket(run, Kind.END, mode, 0, 0, 0, reason)));
            }
            telemetry.close();
        }
        request = null;
        preparing = null;
        driver.reset();
    }

    static void message(String text) {
        var client = Minecraft.getInstance();
        if (client.player != null) {
            ClientPlatform.actionbar(client, Component.literal(text));
        }
    }
}
