package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class VariedAimMotionTest {

    private record Scene(String id, AimTrajectory.Vector eye, AimTrajectory.Rotation start,
        AimTrajectory.Vector reference, List<AimTargetTransfer.Box> boxes, double reach) {

        AimTrajectory.Context context(AimTrajectory.Rotation rotation) {
            return new AimTrajectory.Context("production-test", "side_thin", eye, rotation, reference);
        }
    }

    private final MiningMotionLibrary library = MiningMotionLibrary.instance();

    @Test
    void packagedModelContainsTheVerifiedSourceAndTargetPairs() {
        assertEquals(149, library.profiles().size());
        assertEquals(149, library.targets().size());
        assertEquals(133, library.profiles().stream().filter(profile -> profile.phase().equals("mining_active"))
            .count());
        for (var profile : library.profiles()) {
            assertNotNull(library.targets().get(profile.id()), profile.id());
        }
    }

    @Test
    void allDevelopmentScenesReachTheirActualShapeWithBoundedMotion() throws Exception {
        for (var scene : scenes()) {
            var motion = motion(scene, 20261105);
            boolean stable = false;
            int hitFrames = 0;
            for (int frame = 0; frame < 600; frame++) {
                var rotation = motion.advance(scene.context(motion.tracker().rotation()), scene.boxes(), scene.reach(),
                    1, 1.0 / 120);
                hitFrames = motion.canAttack() ? hitFrames + 1 : 0;
                stable |= hitFrames >= 12;
                assertTrue(rotation.pitch() >= -90 && rotation.pitch() <= 90);
                if (motion.canAttack()) {
                    assertTrue(VariedAimMotion.intersects(scene.eye(), rotation, scene.boxes(), scene.reach()));
                }
            }
            assertTrue(stable, scene.id());
            assertTrue(motion.tracker().peakSpeed() <= 180.000001);
            assertTrue(motion.tracker().peakAcceleration() <= 1440.000001);
        }
    }

    @Test
    void learnedGoalsRespectTheStandingPlatformAndExcludeBlockedChoices() {
        var eye = new AimTrajectory.Vector(-.31, 3.62000000476837, .5);
        var start = new AimTrajectory.Rotation(-95.01712, 69.31942);
        var reference = new AimTrajectory.Vector(.5, .5, .96875);
        var boxes = List.of(new AimTargetTransfer.Box(new AimTrajectory.Vector(0, 0, .9375), new AimTrajectory.Vector(1,
            1, 1)));
        var platform = List.of(new AimTargetTransfer.Box(new AimTrajectory.Vector(-3, 1, -3), new AimTrajectory.Vector(
            0, 2, 3)));
        Predicate<AimTrajectory.Vector> visible = point -> {
            var offset = point.subtract(eye);
            return !VariedAimMotion.intersects(eye, AimTrajectory.Rotation.from(offset, start.yaw()), platform, offset
                .length());
        };
        var context = new AimTrajectory.Context("mining", "side_thin", eye, start, reference);
        var pool = new AimSourceSampler(0, library.profiles(), library.targets()).compatible(context, boxes, 4.5);
        assertTrue(pool.stream().anyMatch(option -> !visible.test(option.goal())));
        assertTrue(visible.test(reference));
        for (long seed = 0; seed < 32; seed++) {
            var motion = new VariedAimMotion(start, seed, library.profiles(), library.targets(), visible);
            for (int frame = 0; frame < 360; frame++) {
                motion.advance(new AimTrajectory.Context("mining", "side_thin", eye, motion.tracker().rotation(),
                    reference), boxes, 4.5, 1, 1.0 / 120);
                assertTrue(visible.test(motion.lastDecision().goal()));
            }
            assertTrue(motion.canAttack(), "Seed " + seed);
            var previousGoal = motion.lastDecision().goal();
            var blocked = new VariedAimMotion(start, seed, library.profiles(), library.targets(), point -> visible.test(
                point) && point.subtract(previousGoal).length() > .000001);
            blocked.advance(context, boxes, 4.5, 1, 1.0 / 120);
            assertTrue(blocked.lastDecision().goal().subtract(previousGoal).length() > .000001);
        }
        var fallback = new VariedAimMotion(start, 0, library.profiles(), library.targets(), point -> point.subtract(
            reference).length() < .000001);
        fallback.advance(context, boxes, 4.5, 1, 0);
        assertEquals(reference, fallback.lastDecision().goal());
        assertEquals("NO_COMPATIBLE_SOURCE", fallback.lastDecision().fallback());
    }

    @Test
    void seedReplaysExactOutputsAndAirRetainsTheTracker() throws Exception {
        var scene = scenes()[0];
        var first = motion(scene, Long.MIN_VALUE);
        var repeated = motion(scene, Long.MIN_VALUE);
        for (int frame = 0; frame < 48; frame++) {
            assertEquals(first.advance(scene.context(first.tracker().rotation()), scene.boxes(), scene.reach(), 1, 1.0
                / 120), repeated.advance(scene.context(repeated.tracker().rotation()), scene.boxes(), scene.reach(), 1,
                    1.0 / 120));
        }
        var tracker = first.tracker();
        var velocity = tracker.velocity();
        first.advance(new AimTrajectory.Context("production-test", "air", scene.eye(), tracker.rotation(), null), List
            .of(), scene.reach(), 2, 1.0 / 120);
        assertSame(tracker, first.tracker());
        assertFalse(first.canAttack());
        assertTrue(Math.hypot(tracker.velocity().yaw() - velocity.yaw(), tracker.velocity().pitch() - velocity
            .pitch()) <= 1440.0 / 120 + .000001);
        assertEquals(VariedAimMotion.State.WAIT_AIR, first.state());
    }

    private VariedAimMotion motion(Scene scene, long seed) {
        return new VariedAimMotion(scene.start(), seed, library.profiles(), library.targets());
    }

    private Scene[] scenes() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/mining-motion-cases.json"),
            StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, Scene[].class);
        }
    }
}
