package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import github.freshchromatic.catbud_addons.island_assistive.study.mixin.StudySessionAccess;
import github.freshchromatic.catbud_addons.mining_study.StudyPacket.Mode;
import java.util.stream.Collectors;

final class StudyMotionDriver {

    private record Model(List<RhythmMotion.Profile> profiles, List<LearnedAimTarget.Preference> targets, String freeze,
        String build) {}

    record Plan(String family, String source, String fallback, double planningMs, RhythmMotion.Context context) {}

    private final Supplier<BlockMiningSession> session;
    private final StudyTelemetry               telemetry;
    private final Model                        model;
    private final ReactiveStudyDriver          reactive;

    private Mode        mode = Mode.BASELINE;
    private Vec3        point;
    private BlockState  block;
    private StudyMotion motion;

    StudyMotionDriver(Supplier<BlockMiningSession> session, StudyTelemetry telemetry) throws IOException {
        this.session = session;
        this.telemetry = telemetry;
        try (var input = StudyMotionDriver.class.getResourceAsStream("/mining-study-model.json")) {
            if (input == null) {
                throw new IOException("Frozen study model missing");
            }
            model = new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), Model.class);
        }
        reactive = new ReactiveStudyDriver(session, model.profiles, model.targets.stream().collect(Collectors
            .toUnmodifiableMap(LearnedAimTarget.Preference::source, preference -> preference)), telemetry);
    }

    String freeze() {
        return model.freeze;
    }

    String build() {
        return model.build;
    }

    boolean enabled() {
        return mode != Mode.BASELINE;
    }

    Mode mode() {
        return mode;
    }

    void mode(Mode mode) {
        this.mode = mode;
        reactive.clearSeed();
        reset();
    }

    void seed(long seed) {
        reactive.seed(seed);
    }

    void begin() {
        if (mode == Mode.VARIED) {
            reactive.begin();
        }
    }

    MiningLookMotion.Rotation reactiveFrame(long now) {
        return mode == Mode.VARIED ? reactive.frame(now) : null;
    }

    void look(Vec3 point, double speed) {
        this.point = point;
        reactive.look(point, speed);
    }

    void reset() {
        clearAim();
        reactive.reset();
    }

    void clearAim() {
        motion = null;
        block = null;
        point = null;
        reactive.clearAim();
    }

    MiningLookMotion.Rotation advance(float yaw, float pitch, double speed, double seconds) {
        var client = Minecraft.getInstance();
        var mining = session.get();
        if (mode != Mode.TIMED || mining == null || !mining.running() || point == null || speed != 180) {
            return null;
        }
        var target = ((StudySessionAccess) (Object) mining).study$target();
        var currentBlock = client.level.getBlockState(target);
        if (currentBlock != block) {
            motion = null;
            block = currentBlock;
        }
        if (block.isAir()) {
            motion = null;
            return new MiningLookMotion.Rotation(yaw, pitch);
        }
        if (motion == null) {
            if (new MiningAimPlanner().crosshair(client, target) != null) {
                return new MiningLookMotion.Rotation(yaw, pitch);
            }
            long started = System.nanoTime();
            var eye = client.player.getEyePosition();
            var shape = block.getShape(client.level, target).toAabbs();
            String family;
            if (shape.size() > 1) {
                family = "multi";
            } else if (shape.isEmpty()) {
                return new MiningLookMotion.Rotation(yaw, pitch);
            } else {
                var box = shape.getFirst();
                if (Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) >= .5) {
                    family = "solid";
                } else {
                    family = box.getYsize() < .1 ? "horizontal_thin" : "side_thin";
                }
            }
            var context = new RhythmMotion.Context(telemetry.identity(), family, new RhythmMotion.Vector(eye.x, eye.y,
                eye.z), new RhythmMotion.Rotation(yaw, pitch), new RhythmMotion.Vector(point.x, point.y, point.z));
            var profile = RhythmMotion.select(context, model.profiles);
            String fallback = "";
            if (profile == null) {
                motion = GeometricStudyMotion.create(context);
                fallback = "NO_PROFILE";
            } else {
                var preference = model.targets.stream().filter(value -> value.source().equals(profile.id())).findFirst()
                    .orElse(null);
                var boxes = shape.stream().map(box -> new LearnedAimTarget.Box(new RhythmMotion.Vector(box.minX + target
                    .getX(), box.minY + target.getY(), box.minZ + target.getZ()), new RhythmMotion.Vector(box.maxX
                        + target.getX(), box.maxY + target.getY(), box.maxZ + target.getZ()))).toList();
                motion = StudyMotion.create(context, profile, preference, boxes, client.player.blockInteractionRange(),
                    "timed_feasible");
                if (motion == null) {
                    motion = GeometricStudyMotion.create(context);
                    fallback = "NO_TRANSFERABLE_TARGET";
                }
            }
            telemetry.event("plan", new Plan(family, profile == null ? null : profile.id(), fallback, (System.nanoTime()
                - started) / 1e6, context));
        }
        var rotation = motion.advance(seconds);
        telemetry.motionLimits(motion.peakSpeed(), motion.peakAcceleration());
        return new MiningLookMotion.Rotation((float) rotation.yaw(), (float) rotation.pitch());
    }
}
