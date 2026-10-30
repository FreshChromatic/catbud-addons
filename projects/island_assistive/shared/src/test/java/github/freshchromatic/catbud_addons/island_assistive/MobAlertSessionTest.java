package github.freshchromatic.catbud_addons.island_assistive;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class MobAlertSessionTest {

    private final RecordingOutput output  = new RecordingOutput();
    private final RecordingSound  sound   = new RecordingSound();
    private long                  now;
    private final MobAlertSession session = new MobAlertSession(() -> now, output, sound);

    @Test
    void disabledByDefaultDoesNotObserveOrPlay() {
        session.worldChanged("minecraft:overworld");
        session.observe(List.of(mob(1, 12)));
        now = 10_000;
        session.tick();
        assertFalse(session.enabled());
        assertTrue(session.pending().isEmpty());
        assertEquals(0, sound.plays);
        assertTrue(output.initial.isEmpty());
    }

    @Test
    void initialTargetsAreReportedTogetherWithoutSound() {
        enable();
        session.observe(List.of(mob(1, 12)));
        now = 1_999;
        session.observe(List.of(mob(2, 256)));
        now = 2_000;
        session.observe(List.of(mob(1, 12), mob(2, 256)));
        assertFalse(session.buildingBaseline());
        assertEquals(2, output.initial.getFirst().size());
        assertTrue(session.pending().isEmpty());
        assertEquals(0, sound.plays);
    }

    @Test
    void firstDiscoveryPlaysImmediatelyAndRepeatsAtFiveSeconds() {
        ready();
        session.observe(List.of(mob(1, 20)));
        assertEquals(1, sound.plays);
        now = 6_999;
        session.tick();
        assertEquals(1, sound.plays);
        now = 7_000;
        session.tick();
        assertEquals(2, sound.plays);
        now = 90_000;
        session.tick();
        session.tick();
        assertEquals(3, sound.plays);
        assertEquals(1, output.alerts.size());
    }

    @Test
    void laterTargetsShareTheOriginalSchedule() {
        ready();
        session.observe(List.of(mob(1, 20)));
        now = 5_000;
        session.observe(List.of(mob(2, 30)));
        assertEquals(1, sound.plays);
        now = 7_000;
        session.tick();
        assertEquals(2, sound.plays);
        assertEquals(2, session.pending().size());
    }

    @Test
    void disappearanceAndDeathDoNotStopPendingAlerts() {
        ready();
        session.observe(List.of(mob(1, 20)));
        session.observe(List.of());
        now = 7_000;
        session.tick();
        assertEquals(2, sound.plays);
        assertEquals(1, session.pending().size());
    }

    @Test
    void leavingReenteringAndReloadingSameUuidDoNotAlertAgain() {
        ready();
        session.observe(List.of(mob(1, 20)));
        session.acknowledge(session.token(), 1, false);
        session.observe(List.of(mob(1, 300)));
        session.observe(List.of());
        session.observe(List.of(mob(1, 20)));
        assertEquals(1, sound.plays);
        assertEquals(1, output.alerts.size());
        assertTrue(session.pending().isEmpty());
    }

    @Test
    void targetLoadedOutsideRadiusCanAlertWhenItEnters() {
        ready();
        session.observe(List.of(mob(1, 257)));
        assertTrue(session.pending().isEmpty());
        session.observe(List.of(mob(1, 256)));
        assertEquals(1, session.pending().size());
    }

    @Test
    void acknowledgingOneTargetKeepsOthersAndLastAcknowledgementStopsSound() {
        ready();
        session.observe(List.of(mob(1, 20), mob(2, 20)));
        int stops = sound.stops;
        assertTrue(session.acknowledge(session.token(), 1, false));
        assertEquals(stops, sound.stops);
        assertEquals(1, session.pending().size());
        assertTrue(session.acknowledge(session.token(), 2, false));
        assertEquals(stops + 1, sound.stops);
        now = 7_000;
        session.tick();
        assertEquals(1, sound.plays);
    }

    @Test
    void oldBatchDoesNotAcknowledgeLaterDiscoveries() {
        ready();
        session.observe(List.of(mob(1, 20), mob(2, 20)));
        session.observe(List.of(mob(3, 20)));
        assertTrue(session.acknowledge(session.token(), 2, true));
        assertEquals(List.of(3L), session.pending().stream().map(MobAlertSession.Alert::id).toList());
    }

    @Test
    void batchStillWorksAfterItsOwnTargetWasIndividuallyAcknowledged() {
        ready();
        session.observe(List.of(mob(1, 20), mob(2, 20)));
        session.acknowledge(session.token(), 2, false);
        session.observe(List.of(mob(3, 20)));
        session.acknowledge(session.token(), 2, true);
        assertEquals(List.of(3L), session.pending().stream().map(MobAlertSession.Alert::id).toList());
    }

    @Test
    void oldSessionAndUnknownAlertCannotAcknowledgeCurrentAlerts() {
        ready();
        UUID old = session.token();
        session.reset();
        session.worldChanged("minecraft:overworld");
        now += 2_000;
        session.observe(List.of(mob(1, 20)));
        assertFalse(session.acknowledge(old, 1, true));
        assertFalse(session.acknowledge(session.token(), 100, true));
        assertFalse(session.acknowledge(session.token(), 0, false));
        assertEquals(1, session.pending().size());
        assertTrue(session.acknowledge(session.token(), 1, false));
        assertFalse(session.acknowledge(session.token(), 1, false));
    }

    @Test
    void disablingClearsRuntimeStateAndStopsSound() {
        ready();
        session.observe(List.of(mob(1, 20)));
        session.configure(false, 256, true, MobWatchlistStore.DEFAULT_RULES);
        assertTrue(session.pending().isEmpty());
        now = 10_000;
        session.tick();
        assertEquals(1, sound.plays);
        assertFalse(sound.playing);
        session.configure(true, 256, true, MobWatchlistStore.DEFAULT_RULES);
        session.worldChanged("minecraft:overworld");
        session.observe(List.of(mob(1, 20)));
        assertTrue(session.buildingBaseline());
        assertTrue(session.pending().isEmpty());
    }

    @Test
    void dimensionChangesKeepPendingAlertsAndDoNotForgetAcknowledgedMobs() {
        ready();
        session.observe(List.of(mob(1, 20), mob(2, 20)));
        session.acknowledge(session.token(), 1, false);
        session.worldChanged("minecraft:the_nether");
        now = 3_000;
        session.observe(List.of(mob(3, 20)));
        now = 7_000;
        session.tick();
        assertEquals(2, sound.plays);
        session.worldChanged("minecraft:overworld");
        now += 2_000;
        session.observe(List.of(mob(1, 20), mob(2, 20)));
        assertEquals(List.of(2L), session.pending().stream().map(MobAlertSession.Alert::id).toList());
    }

    @Test
    void changedRadiusBuildsNewBaselineWithoutDroppingPending() {
        ready();
        session.observe(List.of(mob(1, 20)));
        session.configure(true, 32, true, MobWatchlistStore.DEFAULT_RULES);
        session.observe(List.of(mob(2, 20)));
        assertEquals(1, session.pending().size());
        assertTrue(session.buildingBaseline());
        now += 2_000;
        session.observe(List.of(mob(2, 20)));
        assertEquals(1, output.initial.size());
    }

    @Test
    void changedWatchlistBuildsBaselineAndAppliesNewRulesWithoutDroppingPending() {
        ready();
        session.observe(List.of(mob(1, 20)));
        var rule = new MobWatchRule("minecraft:zombie_villager", MobWatchRule.NameMatch.EXACT, "Rare");
        session.configure(true, 256, true, List.of(rule));
        var baseline = new MobSnapshot(new UUID(0, 2), "minecraft:zombie_villager", "Rare", "Rare", 0, 64, 0, 20);
        session.observe(List.of(baseline));
        assertTrue(session.buildingBaseline());
        assertEquals(1, session.pending().size());
        now += 2_000;
        session.observe(List.of(baseline, mob(3, 20)));
        assertEquals(1, session.pending().size());
        var discovered = new MobSnapshot(new UUID(0, 4), "minecraft:zombie_villager", "Rare", "Rare", 0, 64, 0, 20);
        session.observe(List.of(discovered));
        assertEquals(2, session.pending().size());
        assertEquals(List.of(baseline), output.initial.getFirst());
    }

    @Test
    void disabledInitialNoticeStillDeduplicatesBaselineTargets() {
        session.configure(true, 256, false, MobWatchlistStore.DEFAULT_RULES);
        session.worldChanged("minecraft:overworld");
        session.observe(List.of(mob(1, 20)));
        now = 2_000;
        session.observe(List.of(mob(1, 20)));
        assertTrue(output.initial.isEmpty());
        assertEquals(0, sound.plays);
    }

    @Test
    void lateCustomNameCanTriggerAndMultipleMatchingRulesProduceOneAlert() {
        var rule = new MobWatchRule("minecraft:zombie_villager", MobWatchRule.NameMatch.EXACT, "Rare Villager");
        session.configure(true, 256, true, List.of(rule, rule));
        session.worldChanged("minecraft:overworld");
        now = 2_000;
        session.observe(List.of(mob(1, 20)));
        var named = new MobSnapshot(new UUID(0, 1), "minecraft:zombie_villager", "§6Rare   Villager", "Rare Villager",
            0, 64, 0, 20);
        session.observe(List.of(named));
        assertEquals(1, session.pending().size());
        assertEquals(1, output.alerts.size());
        assertEquals(1, sound.plays);
    }

    private void enable() {
        session.configure(true, 256, true, MobWatchlistStore.DEFAULT_RULES);
        session.worldChanged("minecraft:overworld");
    }

    private void ready() {
        enable();
        now = 2_000;
        session.observe(List.of());
    }

    static MobSnapshot mob(int id, double distance) {
        return new MobSnapshot(new UUID(0, id), "minecraft:zombie_villager", "", "Zombie Villager", distance, 64, 0,
            distance);
    }

    private static final class RecordingOutput implements MobAlertOutput {

        final List<List<MobSnapshot>>     initial = new ArrayList<>();
        final List<MobAlertSession.Alert> alerts  = new ArrayList<>();

        @Override
        public void initial(List<MobSnapshot> mobs) {
            initial.add(mobs);
        }

        @Override
        public void discovered(UUID session, MobAlertSession.Alert alert) {
            alerts.add(alert);
        }
    }

    private static final class RecordingSound implements AlertSound {

        int     plays;
        int     stops;
        boolean playing;

        @Override
        public void play() {
            plays++;
            playing = true;
        }

        @Override
        public void stop() {
            stops++;
            playing = false;
        }
    }
}
