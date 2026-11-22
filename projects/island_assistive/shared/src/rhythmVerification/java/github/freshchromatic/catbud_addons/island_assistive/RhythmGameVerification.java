package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class RhythmGameVerification implements ClientModInitializer {

    private enum Stage {
        START, FIXTURE_READY, CLIP_PREPARE, CLIP, CLIP_FINISH, BENCH_PREPARE, BENCH, REGEN_PREPARE, REGEN,
        FALLBACK_PREPARE, FALLBACK, STOP_PREPARE, STOP, MANUAL_PREPARE, MANUAL, SHUTDOWN, DONE
    }

    private record Trial(String scene, String method, String outcome, int ticks, double firstHitMs, double peakSpeed,
        double peakAcceleration, int attackRayChecks, int attackRayMismatches, List<String> sources,
        double planningTotalMs, double planningMaximumMs, int plans, RhythmMotion.Context unsupportedContext,
        List<Fallback> fallbacks) {}

    private record Fallback(String reason, RhythmMotion.Context context) {}

    private record ClipResult(String id, String prefix, int frames, String screenshotDirectory) {}

    private record ClipPose(String clip, int frame, double x, double y, double z, double yaw, double pitch) {}

    private record Result(String source, String status, String error, double parityError, int paritySamples,
        List<ClipResult> clips, List<Trial> trials, List<String> controls) {}

    private static final Gson POSE_JSON = new Gson();

    private static RhythmGameVerification active;

    private final List<Trial>      trials    = new ArrayList<>();
    private final List<ClipResult> clips     = new ArrayList<>();
    private final List<String>     controls  = new ArrayList<>();
    private final Set<String>      sources   = new HashSet<>();
    private final List<Fallback>   fallbacks = new ArrayList<>();
    private final AtomicInteger    pending   = new AtomicInteger();
    private final String           runId     = "rhythm-" + System.currentTimeMillis();

    private RhythmGameData          data;
    private Path                    output;
    private BlockMiningSession      session;
    private BlockPos                target;
    private RhythmGameData.Scene    scene;
    private StudyMotion             controller;
    private Vec3                    lookPoint;
    private BlockState              lookState;
    private CompletableFuture<Void> task;
    private Stage                   stage             = Stage.START;
    private int                     ticks;
    private int                     clipIndex;
    private int                     clipFrame;
    private int                     preparedClipFrame = -1;
    private int                     sceneIndex;
    private int                     method;
    private long                    trialStarted;
    private long                    started;
    private double                  firstHit          = -1;
    private double                  peakSpeed;
    private double                  peakAcceleration;
    private double                  planningTotalMs;
    private double                  planningMaximumMs;
    private int                     plans;
    private int                     attackRayChecks;
    private int                     attackRayMismatches;
    private boolean                 unsupported;
    private String                  unsupportedReason;
    private RhythmMotion.Context    unsupportedContext;
    private String                  error;
    private double                  parityError;
    private int                     paritySamples;

    private volatile boolean regenerating;
    private volatile int     breaks;
    private int              airTicks;

    @Override
    public void onInitializeClient() {
        active = this;
        try {
            data = RhythmGameData.load(Path.of(System.getProperty("catbud.rhythm.input")));
            output = Path.of(System.getProperty("catbud.rhythm.output"));
            Files.createDirectories(output);
            verifyMath();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot initialize controlled rhythm verification", failure);
        }
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!regenerating || target == null) {
                return;
            }
            var level = server.overworld();
            if (level.getBlockState(target).isAir()) {
                if (airTicks++ == 0) {
                    breaks++;
                }
                if (airTicks >= 4) {
                    level.setBlock(target, sequence(breaks), 2);
                    airTicks = 0;
                }
            } else {
                airTicks = 0;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (Throwable failure) {
                error = failure.toString();
                failure.printStackTrace();
                shutdown(client);
            }
        });
    }

    private void verifyMath() {
        for (var comparison : data.parity()) {
            var test = data.scene(comparison.caseId());
            var context = new RhythmMotion.Context(test.session(), "side_thin", test.eye(), test.start(), test
                .reference());
            var profile = RhythmMotion.select(context, data.profiles());
            require(profile != null, "Parity source missing");
            var motion = data.formatVersion() == 1 ? new StudyMotion.Recorded(new RhythmMotion.Controller(context,
                profile, comparison.method().equals("full_rhythm_bounded")))
                : StudyMotion.create(context, profile, data.preference(profile.id()), test.boxes(), test.reach(),
                    comparison.method());
            require(motion != null, "Parity learned target missing");
            for (int index = 1; index < comparison.expected().size(); index++) {
                var actual = motion.advance(1.0 / 120);
                var expected = comparison.expected().get(index);
                parityError = Math.max(parityError, Math.max(Math.abs(actual.yaw() - expected.yaw()), Math.abs(actual
                    .pitch() - expected.pitch())));
                paritySamples++;
            }
        }
        require(parityError < .0001, "Java rhythm controller differs from Python: " + parityError);
    }

    private void tick(Minecraft client) throws Exception {
        if (stage == Stage.DONE || !client.isGameLoadFinished()) {
            return;
        }
        if (stage == Stage.SHUTDOWN) {
            if (client.level == null && ++ticks > 30 && pending.get() == 0) {
                write();
                stage = Stage.DONE;
                client.stop();
            }
            return;
        }
        if (started != 0) {
            require((System.nanoTime() - started) / 1e9 < 1800, "Controlled game test timed out");
        }
        logicalFocus(client);
        client.options.pauseOnLostFocus = false;
        client.options.renderDistance().set(4);
        client.options.framerateLimit().set(60);
        client.options.fov().set(92);
        client.options.bobView().set(false);
        client.options.smoothCamera = false;
        client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        if (!client.gui.hud.isHidden()) {
            client.gui.hud.toggle();
        }
        if (VerificationPlatform.loading(client) || !VerificationPlatform.prepareWorld(client) || task != null && !task
            .isDone()) {
            return;
        }
        if (task != null) {
            task.join();
            task = null;
        }
        if (started == 0) {
            started = System.nanoTime();
        }
        ticks++;
        switch (stage) {
            case START -> {
                session = FabricLoader.getInstance().getEntrypoints("client", ClientModInitializer.class).stream()
                    .filter(IslandAssistiveClient.class::isInstance).map(IslandAssistiveClient.class::cast).findFirst()
                    .orElseThrow().mining();
                target = new BlockPos(-3576, 65, -3576);
                task = client.getSingleplayerServer().submit(() -> {
                    var server = client.getSingleplayerServer();
                    var level = server.overworld();
                    server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
                    server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false,
                        server);
                    server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false,
                        server);
                    server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
                    level.getWeatherData().setRaining(false);
                    level.getWeatherData().setThundering(false);
                    level.getChunkAt(target);
                    for (var position : BlockPos.betweenClosed(target.offset(-3, -1, -3), target.offset(3, 5, 3))) {
                        level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
                    }
                    for (int x = -3; x <= -1; x++) {
                        for (int z = -3; z <= 3; z++) {
                            level.setBlock(target.offset(x, 1, z), Blocks.STONE.defaultBlockState(), 2);
                        }
                    }
                });
                next(Stage.FIXTURE_READY);
            }
            case FIXTURE_READY -> {
                if (data.formatVersion() >= 2) {
                    method = 0;
                    sceneIndex = 0;
                    prepareBench(client);
                } else {
                    prepareClip(client);
                }
            }
            case CLIP_PREPARE -> {
                if (ticks >= 30 && client.level.getBlockState(target).is(Blocks.SCULK_VEIN)) {
                    clipFrame = 0;
                    preparedClipFrame = -1;
                    next(Stage.CLIP);
                }
            }
            case CLIP -> {
            }
            case CLIP_FINISH -> {
                if (pending.get() != 0) {
                    return;
                }
                var clip = data.clips().get(clipIndex);
                String prefix = runId + "-" + clip.id();
                for (int frame = 0; frame < clip.frames().size(); frame++) {
                    require(Files.isRegularFile(client.gameDirectory.toPath().resolve("screenshots").resolve(prefix
                        + String.format("-%04d.png", frame))), "Missing captured frame");
                }
                clips.add(new ClipResult(clip.id(), prefix, clip.frames().size(), client.gameDirectory.toPath().resolve(
                    "screenshots").toAbsolutePath().toString()));
                writeClips();
                if (++clipIndex < data.clips().size()) {
                    prepareClip(client);
                } else if (data.formatVersion() >= 2) {
                    shutdown(client);
                } else {
                    method = 0;
                    sceneIndex = 0;
                    prepareBench(client);
                }
            }
            case BENCH_PREPARE -> {
                if (ticks >= 35) {
                    begin(client, Stage.BENCH);
                }
            }
            case BENCH -> {
                observeHit(client);
                boolean removed = client.getSingleplayerServer().submit(() -> client.getSingleplayerServer().overworld()
                    .getBlockState(target).isAir()).join();
                if (removed || unsupported || !session.running() || ticks >= 200) {
                    trials.add(trial(benchOutcome(removed)));
                    session.stop();
                    if (++method == data.controlMethods().size()) {
                        method = 0;
                        sceneIndex++;
                    }
                    if (sceneIndex < data.scenes().size()) {
                        prepareBench(client);
                    } else {
                        method = 0;
                        prepareRegeneration(client);
                    }
                }
            }
            case REGEN_PREPARE -> {
                if (ticks >= 35) {
                    breaks = 0;
                    airTicks = 0;
                    regenerating = true;
                    begin(client, Stage.REGEN);
                }
            }
            case REGEN -> {
                if (breaks >= 10 || ticks >= 500 || unsupported || !session.running()) {
                    regenerating = false;
                    trials.add(trial("REGEN_BREAKS=" + breaks + ";" + (unsupported ? unsupportedReason
                        : session.status().getString())));
                    session.stop();
                    if (++method < data.controlMethods().size()) {
                        prepareRegeneration(client);
                    } else if (data.geometricFallback()) {
                        method = 1;
                        prepareFallback(client);
                    } else {
                        method = 0;
                        prepareStop(client);
                    }
                }
            }
            case FALLBACK_PREPARE -> {
                if (ticks >= 35) {
                    begin(client, Stage.FALLBACK);
                    require(!bodyHit(client), "Fallback fixture already aimed at target");
                }
            }
            case FALLBACK -> {
                observeHit(client);
                boolean removed = client.getSingleplayerServer().submit(() -> client.getSingleplayerServer().overworld()
                    .getBlockState(target).isAir()).join();
                if (removed || unsupported || !session.running() || ticks >= 200) {
                    trials.add(trial(removed ? "FALLBACK_SERVER_CONFIRMED_BREAK" : benchOutcome(false)));
                    session.stop();
                    if (++method < data.controlMethods().size()) {
                        prepareFallback(client);
                    } else {
                        method = 0;
                        prepareStop(client);
                    }
                }
            }
            case STOP_PREPARE -> {
                if (ticks >= 35) {
                    aimAt(client, target);
                    begin(client, Stage.STOP);
                }
            }
            case STOP -> {
                if (ticks >= 12) {
                    require(session.running() && client.options.keyAttack.isDown(), "Stop test never held attack");
                    session.stop();
                    require(!client.options.keyAttack.isDown() && !client.options.keyShift.isDown() && !client.gameMode
                        .isDestroying(), "Stop leaves held input");
                    controls.add("PASS stop clears attack/movement/destroying: " + methodName());
                    preparePose(client, data.scenes().getFirst(), vein(data.scenes().getFirst().face()), false);
                    next(Stage.MANUAL_PREPARE);
                }
            }
            case MANUAL_PREPARE -> {
                if (ticks >= 35) {
                    begin(client, Stage.MANUAL);
                }
            }
            case MANUAL -> {
                if (ticks == 3) {
                    require(method == 0 || controller != null, "Manual test never started candidate motion");
                    client.player.setYRot(client.player.getYRot() + 5);
                }
                if (ticks >= 7) {
                    require(!session.running() && !client.options.keyAttack.isDown(), "Manual turn did not take over");
                    controls.add("PASS synthetic manual view change takes over: " + methodName());
                    session.stop();
                    if (++method < data.controlMethods().size()) {
                        prepareStop(client);
                    } else if (data.formatVersion() >= 2) {
                        write();
                        if (data.benchOnly()) {
                            shutdown(client);
                        } else {
                            prepareClip(client);
                        }
                    } else {
                        shutdown(client);
                    }
                }
            }
            default -> throw new IllegalStateException("Unexpected verification stage " + stage);
        }
    }

    private void prepareClip(Minecraft client) {
        var clip = data.clips().get(clipIndex);
        preparePose(client, data.scene(clip.caseId()), vein(data.scene(clip.caseId()).face()), true);
        next(Stage.CLIP_PREPARE);
    }

    private void prepareBench(Minecraft client) {
        var test = data.scenes().get(sceneIndex);
        preparePose(client, test, vein(test.face()), false);
        next(Stage.BENCH_PREPARE);
    }

    private void prepareFallback(Minecraft client) {
        var test = data.scenes().getFirst();
        var fallback = new RhythmGameData.Scene(test.id(), test.query(), test.session(), "down", test.eye(),
            new RhythmMotion.Rotation(test.start().yaw(), 0), test.reference(), test.targetX(), test.targetY(), test
                .targetZ(), List.of(), test.reach());
        preparePose(client, fallback, vein("down"), false);
        next(Stage.FALLBACK_PREPARE);
    }

    private void prepareRegeneration(Minecraft client) {
        preparePose(client, data.scenes().getFirst(), vein(data.scenes().getFirst().face()), false);
        next(Stage.REGEN_PREPARE);
    }

    private void prepareStop(Minecraft client) {
        preparePose(client, data.scenes().getFirst(), Blocks.OBSIDIAN.defaultBlockState(), false);
        next(Stage.STOP_PREPARE);
    }

    private void preparePose(Minecraft client, RhythmGameData.Scene test, BlockState state, boolean visual) {
        if (session != null) {
            session.stop();
        }
        scene = test;
        firstHit = -1;
        peakSpeed = 0;
        peakAcceleration = 0;
        planningTotalMs = 0;
        planningMaximumMs = 0;
        plans = 0;
        attackRayChecks = 0;
        attackRayMismatches = 0;
        sources.clear();
        fallbacks.clear();
        unsupported = false;
        unsupportedReason = null;
        unsupportedContext = null;
        controller = null;
        client.setScreenAndShow(null);
        client.player.setNoGravity(visual);
        client.player.setDeltaMovement(Vec3.ZERO);
        task = client.getSingleplayerServer().submit(() -> {
            var player = client.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
            player.level().setBlock(target, state, 2);
            player.setGameMode(GameType.SURVIVAL);
            player.setNoGravity(visual);
            player.setDeltaMovement(Vec3.ZERO);
            player.teleportTo(test.eye().x(), test.eye().y() - player.getEyeHeight(), test.eye().z());
            player.setYRot((float) test.start().yaw());
            player.setXRot((float) test.start().pitch());
            player.getInventory().setItem(0, new ItemStack(state.is(Blocks.OBSIDIAN) ? Items.DIAMOND_PICKAXE
                : Items.DIAMOND_HOE));
        });
    }

    private void begin(Minecraft client, Stage next) {
        require(Math.abs(client.player.getY() + client.player.getEyeHeight() - scene.eye().y()) < .05,
            "Fixture does not support source stance");
        setAngles(client, scene.start());
        client.player.getInventory().setSelectedSlot(0);
        session.select();
        session.select(new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false));
        session.configure(false, .5, 180, true);
        session.start();
        require(session.running(), "Session refused fixture: " + session.status().getString());
        if (next == Stage.STOP) {
            aimAt(client, target);
        }
        trialStarted = System.nanoTime();
        next(next);
    }

    private void observeHit(Minecraft client) {
        if (firstHit < 0 && bodyHit(client)) {
            firstHit = (System.nanoTime() - trialStarted) / 1e6;
        }
    }

    private Trial trial(String outcome) {
        return new Trial(scene.id(), methodName(), outcome, ticks, firstHit, peakSpeed, peakAcceleration,
            attackRayChecks, attackRayMismatches, List.copyOf(sources), planningTotalMs, planningMaximumMs, plans,
            unsupportedContext, List.copyOf(fallbacks));
    }

    private String benchOutcome(boolean removed) {
        if (removed) {
            return "SERVER_CONFIRMED_BREAK";
        }
        if (unsupported) {
            return unsupportedReason;
        }
        return session.running() ? "TIMEOUT" : session.status().getString();
    }

    private String methodName() {
        return data.controlMethods().get(method);
    }

    private void next(Stage next) {
        stage = next;
        ticks = 0;
    }

    private void shutdown(Minecraft client) {
        regenerating = false;
        if (session != null) {
            session.stop();
        }
        try {
            write();
        } catch (Exception failure) {
            failure.printStackTrace();
        }
        next(Stage.SHUTDOWN);
        client.disconnect(new TitleScreen(), false, false);
    }

    private void writeClips() throws Exception {
        Files.writeString(output.resolve("clips.json"), new GsonBuilder().setPrettyPrinting().create().toJson(clips));
    }

    private void write() throws Exception {
        String status;
        if (error != null) {
            status = "FAIL";
        } else if (stage == Stage.SHUTDOWN || stage == Stage.DONE) {
            status = "EXPERIMENT_COMPLETE";
        } else {
            status = "IN_PROGRESS";
        }
        var result = new Result("verification", status, error, parityError, paritySamples, List.copyOf(clips), List
            .copyOf(trials), List.copyOf(controls));
        Files.writeString(output.resolve("game-verification.json"), new GsonBuilder().setPrettyPrinting().create()
            .toJson(result));
    }

    public static void beforeFrame() {
        if (active == null || active.stage != Stage.CLIP) {
            return;
        }
        var client = Minecraft.getInstance();
        double x = active.scene.eye().x();
        double y = active.scene.eye().y() - client.player.getEyeHeight();
        double z = active.scene.eye().z();
        client.player.setNoGravity(true);
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.setPos(x, y, z);
        client.player.xo = x;
        client.player.yo = y;
        client.player.zo = z;
        client.player.xOld = x;
        client.player.yOld = y;
        client.player.zOld = z;
        setAngles(client, active.data.clips().get(active.clipIndex).frames().get(active.clipFrame));
        active.preparedClipFrame = active.clipFrame;
    }

    public static void captureFrame() {
        if (active == null || active.stage != Stage.CLIP || active.preparedClipFrame != active.clipFrame) {
            return;
        }
        var owner = active;
        var client = Minecraft.getInstance();
        var clip = owner.data.clips().get(owner.clipIndex);
        var expected = clip.frames().get(owner.clipFrame);
        var camera = client.gameRenderer.mainCamera();
        var position = camera.position();
        double eyeError = position.distanceTo(new Vec3(owner.scene.eye().x(), owner.scene.eye().y(), owner.scene.eye()
            .z()));
        double yawError = Math.abs(RhythmMotion.wrap(camera.yRot() - expected.yaw()));
        double pitchError = Math.abs(camera.xRot() - expected.pitch());
        if (eyeError >= .001 || yawError >= .001 || pitchError >= .001) {
            owner.error = "Uncontrolled capture pose: eye=" + eyeError + " yaw=" + yawError + " pitch=" + pitchError;
            owner.shutdown(client);
            return;
        }
        try {
            var pose = new ClipPose(clip.id(), owner.clipFrame, position.x, position.y, position.z, camera.yRot(),
                camera.xRot());
            Files.writeString(owner.output.resolve("clip-poses.jsonl"), POSE_JSON.toJson(pose) + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        String name = owner.runId + "-" + clip.id() + String.format("-%04d.png", owner.clipFrame);
        owner.pending.incrementAndGet();
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1,
            component -> owner.pending.decrementAndGet());
        if (++owner.clipFrame == clip.frames().size()) {
            owner.next(Stage.CLIP_FINISH);
        }
    }

    public static void lookTarget(Vec3 point) {
        if (active != null) {
            var client = Minecraft.getInstance();
            var state = client.level.getBlockState(active.target);
            if (state != active.lookState) {
                active.controller = null;
            }
            active.lookPoint = point;
            active.lookState = state;
        }
    }

    public static void resetMotion() {
        if (active != null) {
            active.controller = null;
            active.lookPoint = null;
            active.lookState = null;
        }
    }

    public static MiningLookMotion.Rotation rotation(float yaw, float pitch, double seconds) {
        if (active == null || active.method == 0 || active.session == null || !active.session.running()) {
            return null;
        }
        var owner = active;
        var client = Minecraft.getInstance();
        var state = client.level.getBlockState(owner.target);
        if (state.isAir()) {
            owner.controller = null;
            return new MiningLookMotion.Rotation(yaw, pitch);
        }
        if (state != owner.lookState) {
            return new MiningLookMotion.Rotation(yaw, pitch);
        }
        if (owner.controller == null) {
            if (bodyHit(client)) {
                return new MiningLookMotion.Rotation(yaw, pitch);
            }
            if (owner.lookPoint == null) {
                return new MiningLookMotion.Rotation(yaw, pitch);
            }
            var eye = client.player.getEyePosition();
            var shape = client.level.getBlockState(owner.target).getShape(client.level, owner.target).toAabbs();
            String family = family(shape);
            var context = new RhythmMotion.Context(owner.scene.session(), family, new RhythmMotion.Vector(eye.x, eye.y,
                eye.z), new RhythmMotion.Rotation(yaw, pitch), new RhythmMotion.Vector(owner.lookPoint.x,
                    owner.lookPoint.y, owner.lookPoint.z));
            var profile = RhythmMotion.select(context, owner.data.profiles());
            long planningStarted = System.nanoTime();
            if (profile == null) {
                owner.controller = owner.unavailableMotion(context, "NO_PROFILE");
            } else {
                owner.sources.add(profile.id());
                if (owner.data.formatVersion() == 1) {
                    owner.controller = new StudyMotion.Recorded(new RhythmMotion.Controller(context, profile, owner
                        .methodName().equals("full_rhythm_bounded")));
                } else {
                    var boxes = shape.stream().map(box -> new LearnedAimTarget.Box(new RhythmMotion.Vector(box.minX
                        + owner.target.getX(), box.minY + owner.target.getY(), box.minZ + owner.target.getZ()),
                        new RhythmMotion.Vector(box.maxX + owner.target.getX(), box.maxY + owner.target.getY(), box.maxZ
                            + owner.target.getZ()))).toList();
                    owner.controller = StudyMotion.create(context, profile, owner.data.preference(profile.id()), boxes,
                        client.player.blockInteractionRange(), owner.methodName());
                    if (owner.controller == null) {
                        owner.controller = owner.unavailableMotion(context, "NO_TRANSFERABLE_TARGET");
                    }
                }
            }
            double planningMs = (System.nanoTime() - planningStarted) / 1e6;
            owner.planningTotalMs += planningMs;
            owner.planningMaximumMs = Math.max(owner.planningMaximumMs, planningMs);
            owner.plans++;
            if (owner.controller == null) {
                return new MiningLookMotion.Rotation(yaw, pitch);
            }
        }
        var result = owner.controller.advance(seconds);
        owner.peakSpeed = Math.max(owner.peakSpeed, owner.controller.peakSpeed());
        owner.peakAcceleration = Math.max(owner.peakAcceleration, owner.controller.peakAcceleration());
        return new MiningLookMotion.Rotation((float) result.yaw(), (float) result.pitch());
    }

    private StudyMotion unavailableMotion(RhythmMotion.Context context, String reason) {
        if (data.geometricFallback()) {
            fallbacks.add(new Fallback(reason, context));
            return GeometricStudyMotion.create(context);
        }
        unsupported = true;
        unsupportedReason = reason;
        unsupportedContext = context;
        return null;
    }

    private static void setAngles(Minecraft client, RhythmMotion.Rotation rotation) {
        client.player.setYRot((float) rotation.yaw());
        client.player.setXRot((float) rotation.pitch());
        client.player.yRotO = (float) rotation.yaw();
        client.player.xRotO = (float) rotation.pitch();
    }

    public static void attackAttempt(BlockPos position) {
        var owner = active;
        if (owner == null || owner.session == null || !owner.session.running() || !owner.target.equals(position)) {
            return;
        }
        var client = Minecraft.getInstance();
        owner.attackRayChecks++;
        if (bodyHit(client)) {
            owner.observeHit(client);
        } else {
            owner.attackRayMismatches++;
        }
    }

    private static String family(List<net.minecraft.world.phys.AABB> shape) {
        if (shape.isEmpty()) {
            return "air";
        }
        if (shape.size() > 1) {
            return "multi";
        }
        var box = shape.getFirst();
        if (Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) >= .5) {
            return "solid";
        }
        return box.getYsize() < .1 ? "horizontal_thin" : "side_thin";
    }

    private static boolean bodyHit(Minecraft client) {
        var hit = client.player.pick(client.player.blockInteractionRange(), 1, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && block.getBlockPos()
            .equals(active.target);
    }

    private static BlockState vein(String face) {
        return Blocks.SCULK_VEIN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.valueOf(face
            .toUpperCase(java.util.Locale.ROOT))), true);
    }

    private static BlockState sequence(int index) {
        return switch (index % 10) {
            case 0 -> Blocks.STONE.defaultBlockState();
            case 1 -> Blocks.OAK_LOG.defaultBlockState();
            case 2 -> Blocks.DIRT.defaultBlockState();
            case 3 -> vein("down");
            case 4 -> vein("up");
            case 5 -> vein("north");
            case 6 -> vein("south");
            case 7 -> vein("east");
            case 8 -> vein("west");
            default -> vein("up").setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true).setValue(
                MultifaceBlock.getFaceProperty(Direction.EAST), true);
        };
    }

    private static void aimAt(Minecraft client, BlockPos target) {
        var point = new MiningAimPlanner().find(client, target, client.player.getEyePosition(), null);
        require(point != null, "No visible test target");
        var delta = point.subtract(client.player.getEyePosition());
        setAngles(client, new RhythmMotion.Rotation(Math.toDegrees(Math.atan2(-delta.x, delta.z)), -Math.toDegrees(Math
            .atan2(delta.y, Math.hypot(delta.x, delta.z)))));
    }

    private static void logicalFocus(Minecraft client) throws ReflectiveOperationException {
        setFlag(client.getWindow(), "focused");
        setFlag(client.mouseHandler, "mouseGrabbed");
    }

    private static void setFlag(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setBoolean(target, true);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
