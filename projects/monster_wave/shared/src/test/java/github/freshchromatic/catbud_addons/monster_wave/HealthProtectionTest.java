package github.freshchromatic.catbud_addons.monster_wave;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HealthProtectionTest {

    @Test
    void successiveLossesTriggerBeforeFourPoints() {
        var clock = new MonsterWaveSessionTest.Clock();
        var protection = new LowHealthDisconnect(clock);
        assertFalse(protection.check(true, true, 20));
        clock.advance(200);
        assertFalse(protection.check(true, true, 14));
        clock.advance(200);
        assertTrue(protection.check(true, true, 8));
        assertEquals(LowHealthDisconnect.Trigger.BURST_RISK, protection.assessment().trigger());
        assertEquals(6, protection.assessment().largestDrop());
        assertEquals(12, protection.assessment().recentDrop());
        assertFalse(protection.check(true, true, 8));
    }

    @Test
    void healingDoesNotEraseLossesAndBothWindowsExpire() {
        var clock = new MonsterWaveSessionTest.Clock();
        var protection = new LowHealthDisconnect(clock);
        protection.check(true, true, 20);
        clock.advance(100);
        assertFalse(protection.check(true, true, 18));
        assertFalse(protection.check(true, true, 20));
        clock.advance(100);
        assertFalse(protection.check(true, true, 18));
        assertEquals(4, protection.assessment().predictedLoss());
        clock.advance(1000);
        assertFalse(protection.check(true, true, 18));
        assertEquals(0, protection.assessment().recentDrop());
        assertEquals(2, protection.assessment().predictedLoss());
        clock.advance(5000);
        assertFalse(protection.check(true, true, 18));
        assertEquals(0, protection.assessment().predictedLoss());
    }

    @Test
    void baselineThresholdResetAndDisabledContextsRemainSafe() {
        var clock = new MonsterWaveSessionTest.Clock();
        var protection = new LowHealthDisconnect(clock);
        assertFalse(protection.check(true, true, 4));
        assertTrue(protection.check(true, true, 3.5F));
        assertEquals(LowHealthDisconnect.Trigger.LOW_HEALTH, protection.assessment().trigger());
        protection.reset();
        assertFalse(protection.check(true, true, 8));
        assertFalse(protection.check(false, true, 1));
        assertFalse(protection.check(true, false, 1));
        assertFalse(protection.check(true, true, Float.NaN));
        assertEquals(0, protection.assessment().predictedLoss());
    }

    @Test
    void cleanupWaitRefreshesUntilWorkerCompletesWithoutDiscardingItsResult() throws Exception {
        var task = new CompletableFuture<String>();
        var frames = new AtomicInteger();
        String result = CleanupWait.await(task, () -> {
            frames.incrementAndGet();
            task.complete("finished");
        });
        assertEquals("finished", result);
        assertEquals(1, frames.get());
    }
}
