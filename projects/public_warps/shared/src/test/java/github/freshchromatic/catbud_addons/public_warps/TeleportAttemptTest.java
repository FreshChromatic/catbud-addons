package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TeleportAttemptTest {

    @Test
    void cancelledAndSingleSignalNeverCount() {
        var t = new TeleportAttempt("Cat", 1000);
        t.launch(true, 1100);
        t.positionCorrection(1200);
        assertFalse(t.confirmArrival(2000, true));
        t.launch(false, 2100);
        t.positionCorrection(2200);
        t.cancel();
        assertFalse(t.confirmArrival(3000, true));
    }

    @Test
    void arrivalRequiresLoadedCorrectionAndCountsOnce() {
        var t = new TeleportAttempt("Cat", 1000);
        t.launch(false, 1100);
        t.launch(true, 1200);
        assertFalse(t.confirmArrival(2000, true));
        t.positionCorrection(2100);
        assertFalse(t.confirmArrival(2300, true));
        assertFalse(t.confirmArrival(2800, true));
        t.bossEnded(2800);
        assertFalse(t.confirmArrival(2800, false));
        assertTrue(t.confirmArrival(2800, true));
        assertFalse(t.confirmArrival(3000, true));
    }

    @Test
    void onlyExactTargetsAndBoundedWindow() {
        assertEquals("Neko_1212", TeleportAttempt.target("to by Neko_1212"));
        assertNull(TeleportAttempt.target("to by set"));
        assertNull(TeleportAttempt.target("to by Cat extra"));
        var t = new TeleportAttempt("Cat", 1000);
        t.launch(true, 32000);
        t.launch(false, 32000);
        assertFalse(t.qualified(32000));
    }

    @Test
    void rapidTargetReplacementRequiresFreshBossUpdateAndCountsOnlyNewTarget() {
        var old = new TeleportAttempt("maiyaWEE_77", 1000);
        old.launch(false, 1200);
        old.launch(true, 1200);
        var next = new TeleportAttempt("Yurui0905", 1800);
        assertTrue(next.retainLaunchFrom(old, 1800));
        old.cancel();
        assertFalse(next.qualified(1800));
        assertTrue(next.refreshRetainedLaunch(2000));
        assertFalse(next.refreshRetainedLaunch(2001));
        next.positionCorrection(7000);
        next.bossEnded(7050);
        assertTrue(next.confirmArrival(7600, true));
        assertFalse(next.confirmArrival(7700, true));
        assertFalse(old.confirmArrival(7600, true));
    }

    @Test
    void staleCancelledAndSingleChannelLaunchCannotBeReused() {
        var old = new TeleportAttempt("Cat", 1000);
        old.launch(true, 1100);
        assertFalse(new TeleportAttempt("Other", 1500).retainLaunchFrom(old, 1500));
        old.launch(false, 1200);
        assertFalse(new TeleportAttempt("Other", 7000).retainLaunchFrom(old, 7000));
        old.cancel();
        assertFalse(new TeleportAttempt("Other", 1500).retainLaunchFrom(old, 1500));
    }
}
