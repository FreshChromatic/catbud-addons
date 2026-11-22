package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.LearnedAimTarget.Box;
import github.freshchromatic.catbud_addons.island_assistive.LearnedAimTarget.Preference;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Context;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Profile;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Rotation;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Vector;
import github.freshchromatic.catbud_addons.island_assistive.study.mixin.StudySessionAccess;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class ReactiveStudyDriver {

    private record Plan(long seed, long revision, String family, String source, int poolSize, long drawIndex,
        double uniform, Double probability, Vector goal, String fallback, String curveStrategy, String recoveryError,
        double planningMs, Context context) {}

    private record StateSample(String state, long revision, long seed, boolean canAttack,
        AdaptiveAimTracker.Rates velocity) {}

    private final Supplier<BlockMiningSession> session;
    private final List<Profile>                profiles;
    private final Map<String, Preference>      preferences;
    private final StudyTelemetry               telemetry;

    private Long                    seedOverride;
    private long                    activeSeed;
    private long                    lastFrame;
    private long                    revision;
    private long                    loggedPlans;
    private double                  speed = 180;
    private Vec3                    reference;
    private BlockState              block;
    private ReactiveAimMotion       motion;
    private ReactiveAimMotion.State state;

    ReactiveStudyDriver(Supplier<BlockMiningSession> session, List<Profile> profiles, Map<String, Preference> preferences, StudyTelemetry telemetry) {
        this.session = session;
        this.profiles = profiles;
        this.preferences = preferences;
        this.telemetry = telemetry;
    }

    void seed(long seed) {
        seedOverride = seed;
    }

    void clearSeed() {
        seedOverride = null;
    }

    void begin() {
        reset();
        activeSeed = seedOverride == null ? UUID.randomUUID().getMostSignificantBits() : seedOverride;
        telemetry.event("reactive_seed", activeSeed);
    }

    void look(Vec3 reference, double speed) {
        this.reference = reference;
        this.speed = speed;
    }

    void clearAim() {
        reference = null;
    }

    void reset() {
        motion = null;
        block = null;
        reference = null;
        state = null;
        lastFrame = revision = loggedPlans = 0;
        speed = 180;
    }

    MiningLookMotion.Rotation frame(long now) {
        if (speed != 180) {
            motion = null;
            lastFrame = 0;
            return null;
        }
        var client = Minecraft.getInstance();
        var target = ((StudySessionAccess) (Object) session.get()).study$target();
        var currentBlock = client.level.getBlockState(target);
        if (currentBlock != block) {
            block = currentBlock;
            revision++;
        }
        var shape = block.getShape(client.level, target).toAabbs();
        var boxes = shape.stream().map(box -> new Box(new Vector(box.minX + target.getX(), box.minY + target.getY(),
            box.minZ + target.getZ()), new Vector(box.maxX + target.getX(), box.maxY + target.getY(), box.maxZ + target
                .getZ()))).toList();
        String family = "air";
        if (shape.size() > 1) {
            family = "multi";
        } else if (!shape.isEmpty()) {
            var box = shape.getFirst();
            if (Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) >= .5) {
                family = "solid";
            } else {
                family = box.getYsize() < .1 ? "horizontal_thin" : "side_thin";
            }
        }
        if (motion == null) {
            motion = new ReactiveAimMotion(new Rotation(client.player.getYRot(), client.player.getXRot()), activeSeed,
                profiles, preferences);
            loggedPlans = 0;
        }
        var eye = client.player.getEyePosition();
        var origin = new Vector(eye.x, eye.y, eye.z);
        var aimReference = reference == null ? null : new Vector(reference.x, reference.y, reference.z);
        if (aimReference != null && !ReactiveAimMotion.intersects(origin, Rotation.from(aimReference.subtract(origin),
            motion.tracker().rotation().yaw()), boxes, client.player.blockInteractionRange())) {
            aimReference = null;
        }
        var context = new Context(telemetry.identity(), family, origin, motion.tracker().rotation(), aimReference);
        double seconds = lastFrame == 0 ? 0 : Math.clamp((now - lastFrame) / 1e9, 0, .1);
        lastFrame = now;
        var rotation = motion.advance(context, boxes, client.player.blockInteractionRange(), revision, seconds);
        if (motion.plans() != loggedPlans) {
            var decision = motion.lastDecision();
            var choice = decision.choice();
            telemetry.event("reactive_plan", new Plan(activeSeed, revision, family, choice.option() == null ? null
                : choice.option().profile().id(), choice.poolSize(), choice.index(), choice.uniform(), choice
                    .probability(), decision.goal(), decision.fallback(), decision.curveStrategy(), decision
                        .recoveryError(), decision.planningMs(), context));
            loggedPlans = motion.plans();
        }
        if (state != motion.state()) {
            state = motion.state();
            telemetry.event("reactive_state", new StateSample(state.name(), revision, activeSeed, motion.canAttack(),
                motion.tracker().velocity()));
        }
        telemetry.motionLimits(motion.tracker().peakSpeed(), motion.tracker().peakAcceleration());
        return new MiningLookMotion.Rotation((float) rotation.yaw(), (float) rotation.pitch());
    }
}
