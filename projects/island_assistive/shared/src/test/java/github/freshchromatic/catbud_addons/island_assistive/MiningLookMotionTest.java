package github.freshchromatic.catbud_addons.island_assistive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MiningLookMotionTest {

    @Test
    void yawCrossesBoundaryByTheShortestRoute() {
        assertTrue(new MiningLookMotion().advance(179, 0, -179, 0, 180, 0.05).yaw() > 179);
        assertTrue(new MiningLookMotion().advance(-179, 0, 179, 0, 180, 0.05).yaw() < -179);
    }

    @Test
    void turningIsBoundedAndConvergesWithoutOvershoot() {
        var motion = new MiningLookMotion();
        float first = motion.advance(0, 0, 90, 0, 180, 0.05).yaw();
        assertTrue(first > 0 && first < 9);
        motion.reset();
        float angle = 0;
        for (int i = 0; i < 100; i++) {
            float next = motion.advance(angle, 0, 35, 0, 180, 0.05).yaw();
            assertTrue(next >= angle && next <= 35);
            angle = next;
        }
        assertEquals(35, angle);
    }

    @Test
    void highFrameRatesUseSmallStepsWithoutChangingTurnSpeed() {
        float slow = 0;
        float fast = 0;
        var slowMotion = new MiningLookMotion();
        var fastMotion = new MiningLookMotion();
        for (int frame = 0; frame < 20; frame++) {
            slow = slowMotion.advance(slow, 0, 90, 0, 180, 1.0 / 20).yaw();
        }
        for (int frame = 0; frame < 120; frame++) {
            float next = fastMotion.advance(fast, 0, 90, 0, 180, 1.0 / 120).yaw();
            assertTrue(next - fast <= 1.5001f);
            fast = next;
        }
        assertEquals(slow, fast, 0.5);
        assertEquals(10, fastMotion.advance(10, 0, 90, 0, 180, 0).yaw());
    }
}
