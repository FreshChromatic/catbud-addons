package github.freshchromatic.catbud_addons.monster_wave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static github.freshchromatic.catbud_addons.monster_wave.MonsterWaveSession.*;
import static github.freshchromatic.catbud_addons.monster_wave.BossbarTracker.Signal;
import static github.freshchromatic.catbud_addons.monster_wave.RunEvent.EndReason;

class MonsterWaveSessionTest {

    static final ConnectionIdentity SERVER = new ConnectionIdentity("catbud.example:25565", new UUID(0, 1));

    static final class Clock implements RunClock {

        long    nanos;
        Instant wall = Instant.parse("2026-10-18T00:00:00Z");

        public Instant instant() {
            return wall;
        }

        public long nanoTime() {
            return nanos;
        }

        void advance(long millis) {
            nanos += millis * 1_000_000;
            wall = wall.plusMillis(millis);
        }
    }

    static final class Sink implements RunSink {

        final List<RunEvent> events = new ArrayList<>();
        RunEvent             end;

        public void accept(RunEvent event) {
            events.add(event);
        }

        public void finish(RunEvent event) {
            assertNull(end);
            end = event;
            events.add(event);
        }

        public String location() {
            return "test";
        }

        public String error() {
            return null;
        }

        public long droppedEvents() {
            return 0;
        }
    }

    final Clock              clock   = new Clock();
    final List<Sink>         sinks   = new ArrayList<>();
    final Object             lobby   = new Object();
    final Object             arena   = new Object();
    final UUID               queue   = UUID.randomUUID();
    int                      disconnected;
    final MonsterWaveSession session = new MonsterWaveSession(clock, mode -> {
                                         var sink = new Sink();
                                         sinks.add(sink);
                                         return new MonsterWaveRun(UUID.randomUUID(), clock, sink, "test", "test", mode
                                             .name());
                                     }, () -> disconnected++);

    static RunEvent.BossValue value(String text) {
        return new RunEvent.BossValue(new RunEvent.TextValue(text, "{\"text\":\"" + text + "\"}", List.of(), null), 1,
            "RED", "PROGRESS", false, false, false);
    }

    Observation observation(Object world, String dimension, float health) {
        return new Observation(true, true, world, dimension, 0, 64, 0, health);
    }

    void queued() {
        session.connectionJoined(SERVER);
        session.tick(observation(lobby, "minecraft:overworld", 20));
        session.bossbars().add(queue, value("queue"), Signal.QUEUE);
        assertEquals(Phase.QUEUED, session.phase());
    }

    void active(float health) {
        queued();
        session.bossbars().name(queue, value("ready").name(), Signal.READY);
        session.teleport("position_packet");
        session.tick(observation(arena, "minecraft:custom", health));
    }

    @Test
    void normalRunHasIndependentQueueAndActiveDurations() {
        queued();
        clock.advance(500);
        session.bossbars().name(queue, value("ready").name(), Signal.READY);
        clock.advance(500);
        session.teleport("position_packet");
        session.tick(observation(arena, "minecraft:custom", 20));
        assertTrue(session.active());
        clock.advance(2500);
        session.tick(observation(lobby, "minecraft:overworld", 2));
        var summary = session.lastSummary();
        assertEquals(3500, summary.totalDurationMs());
        assertEquals(2500, summary.activeDurationMs());
        assertEquals(EndReason.LEFT_TARGET_WORLD, summary.reason());
        assertEquals(0, disconnected);
        assertEquals(20F, summary.minimumHealth());
    }

    @ParameterizedTest
    @ValueSource(floats = { 3.5F, 3F, 0F })
    void enteringWithLowHealthDisconnectsImmediatelyAndOnlyOnce(float health) {
        active(health);
        assertEquals(1, disconnected);
        assertEquals(EndReason.LOW_HEALTH, session.lastSummary().reason());
        session.tick(observation(arena, "minecraft:custom", health));
        session.bossbars().name(queue, value("queue again").name(), Signal.QUEUE);
        assertFalse(session.forceStart(observation(arena, "minecraft:custom", health)));
        session.connectionReset();
        assertEquals(1, disconnected);
        assertEquals(1, sinks.size());
        assertEquals(EndReason.LOW_HEALTH, session.lastSummary().reason());
    }

    @Test
    void exactThresholdIsAllowedUntilNextHealthUpdate() {
        active(4);
        assertEquals(0, disconnected);
        session.tick(observation(arena, "minecraft:custom", 3.5F));
        assertEquals(1, disconnected);
    }

    @Test
    void queueAndReadyDoNotProtectOrInferActivationFromReadyStart() {
        queued();
        session.tick(observation(lobby, "minecraft:overworld", 1));
        session.bossbars().name(queue, value("start").name(), Signal.READY_START);
        session.tick(observation(lobby, "minecraft:custom", 1));
        assertEquals(Phase.QUEUED, session.phase());
        session.bossbars().name(queue, value("ready").name(), Signal.READY);
        session.tick(observation(lobby, "minecraft:custom", 1));
        assertEquals(Phase.READY, session.phase());
        assertEquals(0, disconnected);
    }

    @Test
    void unrelatedWorldAndReadyBarCannotStartRun() {
        session.bossbars().add(UUID.randomUUID(), value("tower queue"), Signal.NONE);
        session.bossbars().add(UUID.randomUUID(), value("ready"), Signal.READY);
        session.teleport("position_packet");
        session.tick(observation(arena, "minecraft:custom", 1));
        assertEquals(Phase.IDLE, session.phase());
        assertTrue(sinks.isEmpty());
        assertEquals(0, disconnected);
    }

    @Test
    void missingReadyCanActivateAfterQueueDisappearsAndEarlierTeleport() {
        queued();
        session.teleport("respawn_packet");
        session.tick(observation(arena, "minecraft:custom", 20));
        assertEquals(Phase.QUEUED, session.phase());
        session.bossbars().remove(queue);
        session.tick(observation(arena, "minecraft:custom", 20));
        assertTrue(session.active());
    }

    @Test
    void readyBarReplacementAndTemporaryMissingLevelPreserveRun() {
        queued();
        UUID ready = UUID.randomUUID();
        session.bossbars().add(ready, value("ready"), Signal.READY);
        session.bossbars().remove(queue);
        UUID replacement = UUID.randomUUID();
        session.bossbars().remove(ready);
        session.bossbars().add(replacement, value("ready2"), Signal.READY);
        session.tick(new Observation(true, true, null, "", 0, 0, 0, Float.NaN));
        clock.advance(20_000);
        session.tick(observation(arena, "minecraft:custom", 20));
        assertTrue(session.active());
        assertEquals(1, sinks.size());
    }

    @Test
    void cancellationUsesQueueGraceWithoutInventingActiveTime() {
        queued();
        session.bossbars().remove(queue);
        session.tick(observation(lobby, "minecraft:overworld", 20));
        clock.advance(2999);
        session.tick(observation(lobby, "minecraft:overworld", 20));
        assertEquals(Phase.QUEUED, session.phase());
        clock.advance(1);
        session.tick(observation(lobby, "minecraft:overworld", 20));
        assertEquals(EndReason.QUEUE_CANCELLED, session.lastSummary().reason());
        assertNull(session.lastSummary().activeDurationMs());
        assertFalse(session.lastSummary().started());
    }

    @Test
    void readyGraceIsFifteenSeconds() {
        queued();
        session.bossbars().name(queue, value("ready").name(), Signal.READY);
        session.bossbars().remove(queue);
        session.tick(observation(lobby, "minecraft:overworld", 20));
        clock.advance(14999);
        session.tick(observation(lobby, "minecraft:overworld", 20));
        assertEquals(Phase.READY, session.phase());
        clock.advance(1);
        session.tick(observation(lobby, "minecraft:overworld", 20));
        assertEquals(EndReason.QUEUE_CANCELLED, session.lastSummary().reason());
    }

    @Test
    void readyExitAndSameDimensionTravelKeepProtecting() {
        active(4);
        session.bossbars().name(queue, value("exit").name(), Signal.READY_EXIT);
        session.bossbars().remove(queue);
        session.tick(observation(new Object(), "minecraft:custom", 4));
        assertTrue(session.active());
        clock.advance(24 * 60 * 60 * 1000L);
        session.tick(observation(arena, "minecraft:custom", 3));
        assertEquals(1, disconnected);
        assertEquals(EndReason.LOW_HEALTH, session.lastSummary().reason());
    }

    @Test
    void forcedModeSurvivesWorldChangeAndResetsOnReconnect() {
        assertTrue(session.forceStart(observation(lobby, "minecraft:overworld", 4)));
        session.tick(observation(arena, "minecraft:the_nether", 4));
        assertTrue(session.active());
        session.connectionReset();
        assertEquals(Mode.AUTO, session.mode());
        assertEquals(EndReason.DISCONNECTED, session.lastSummary().reason());
    }

    @Test
    void manualAndDisabledModesEndRecordings() {
        active(20);
        session.forceStop();
        assertEquals(EndReason.MANUAL_STOP, session.lastSummary().reason());
        session.tick(observation(arena, "minecraft:custom", 1));
        assertFalse(session.active());
        session.useAuto();
        session.forceStart(observation(arena, "minecraft:custom", 20));
        session.enabled(false);
        assertEquals(EndReason.DISABLED, session.lastSummary().reason());
        assertFalse(session.forceStart(observation(arena, "minecraft:custom", 1)));
        assertEquals(0, disconnected);
    }

    @Test
    void forcedStartAtLowHealthDisconnectsAndSingleplayerCannotForceStart() {
        assertFalse(session.forceStart(new Observation(true, false, lobby, "minecraft:overworld", 0, 0, 0, 1)));
        assertTrue(session.forceStart(observation(lobby, "minecraft:overworld", 1)));
        assertEquals(1, disconnected);
        assertEquals(EndReason.LOW_HEALTH, session.lastSummary().reason());
    }

    @Test
    void singleplayerAndMissingPlayerNeverDisconnect() {
        active(20);
        session.tick(new Observation(true, false, arena, "minecraft:custom", 0, 0, 0, 1));
        session.tick(new Observation(true, true, null, "", 0, 0, 0, Float.NaN));
        assertEquals(0, disconnected);
        assertTrue(session.active());
    }

    @Test
    void wallClockChangesDoNotChangeElapsedAndRepeatedTextIsPreserved() {
        active(20);
        clock.advance(1000);
        clock.wall = clock.wall.minusSeconds(3600);
        var text = new RunEvent.Text(RunEvent.Channel.ACTIONBAR, value("same").name(), true);
        session.event("text", text);
        clock.advance(1000);
        session.event("text", text);
        session.clientStopped();
        assertEquals(2000, session.lastSummary().activeDurationMs());
        var events = sinks.getFirst().events;
        assertEquals(2, events.stream().filter(e -> e.type().equals("text")).count());
        long previous = 0;
        for (var event : events) {
            assertTrue(event.sequence() > previous);
            previous = event.sequence();
        }
    }

    @Test
    void lowHealthReconnectInCustomRestoresProtectionAndLinksRecordings() {
        active(3);
        UUID previousId = sinks.getFirst().end.runId();
        assertTrue(session.awaitingResume());
        session.connectionReset();
        clock.advance(12_000);
        session.connectionJoined(SERVER);
        session.tick(observation(new Object(), "minecraft:custom", 4));
        assertTrue(session.active());
        assertFalse(session.awaitingResume());
        assertEquals(Mode.AUTO, session.mode());
        assertEquals(2, sinks.size());
        assertEquals(1, disconnected);
        var resumed = sinks.getLast().events.stream().filter(event -> event.type().equals("run_resumed")).findFirst()
            .orElseThrow();
        var data = (RunEvent.Resumed) resumed.data();
        assertEquals(previousId, data.previousRunId());
        assertEquals(12_000, data.disconnectedDurationMs());
        assertNotEquals(previousId, resumed.runId());
        session.tick(observation(arena, "minecraft:custom", 3.5F));
        assertEquals(2, disconnected);
        assertEquals(EndReason.LOW_HEALTH, session.lastSummary().reason());
    }

    @Test
    void serverKickAlsoRetainsResumeCandidateAcrossDisconnectedTicks() {
        active(20);
        session.connectionReset();
        assertTrue(session.awaitingResume());
        assertEquals(EndReason.DISCONNECTED, session.lastSummary().reason());
        for (int tick = 0; tick < 5; tick++) {
            session.tick(new Observation(false, false, null, "", 0, 0, 0, Float.NaN));
        }
        session.connectionJoined(SERVER);
        session.tick(new Observation(true, true, null, "", 0, 0, 0, Float.NaN));
        assertTrue(session.awaitingResume());
        assertFalse(session.active());
        session.tick(observation(new Object(), "minecraft:custom", 20));
        assertTrue(session.active());
        assertEquals(2, sinks.size());
    }

    @Test
    void reconnectOutsideCustomClearsResumeCandidate() {
        active(3);
        session.connectionJoined(SERVER);
        session.tick(observation(lobby, "minecraft:overworld", 1));
        assertFalse(session.active());
        assertFalse(session.awaitingResume());
        session.tick(observation(arena, "minecraft:custom", 1));
        assertFalse(session.active());
        assertEquals(1, disconnected);
        assertEquals(1, sinks.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void changingServerOrAccountPreventsResumption(boolean changeServer) {
        active(3);
        var other = changeServer ? new ConnectionIdentity("other.example", SERVER.player())
            : new ConnectionIdentity(SERVER.server(), new UUID(0, 2));
        session.connectionJoined(other);
        session.tick(observation(arena, "minecraft:custom", 1));
        assertFalse(session.active());
        assertFalse(session.awaitingResume());
        assertEquals(1, sinks.size());
        assertEquals(1, disconnected);
    }

    @Test
    void joiningCustomWithoutEarlierActiveRunDoesNotResume() {
        session.connectionJoined(SERVER);
        session.tick(observation(arena, "minecraft:custom", 1));
        assertFalse(session.active());
        assertTrue(sinks.isEmpty());
        assertEquals(0, disconnected);
    }

    @ParameterizedTest
    @ValueSource(strings = { "stop", "auto", "disable", "shutdown" })
    void explicitUserActionsClearPendingResumption(String action) {
        active(3);
        assertTrue(session.awaitingResume());
        switch (action) {
            case "stop" -> session.forceStop();
            case "auto" -> session.useAuto();
            case "disable" -> session.enabled(false);
            case "shutdown" -> session.clientStopped();
            default -> throw new AssertionError(action);
        }
        assertFalse(session.awaitingResume());
        session.connectionJoined(SERVER);
        session.tick(observation(arena, "minecraft:custom", 1));
        assertFalse(session.active());
        assertEquals(1, disconnected);
    }

    @Test
    void everyLowHealthRejoinCanDisconnectOnceWithoutNewQueueSignal() {
        active(3);
        for (int rejoin = 0; rejoin < 3; rejoin++) {
            session.connectionReset();
            session.connectionJoined(SERVER);
            session.tick(observation(new Object(), "minecraft:custom", 3));
            session.tick(observation(arena, "minecraft:custom", 3));
            assertEquals(rejoin + 2, disconnected);
        }
        assertEquals(4, sinks.size());
        assertTrue(session.awaitingResume());
    }

    @Test
    void queuedRunDoesNotBecomeResumeCandidate() {
        queued();
        session.connectionReset();
        session.connectionJoined(SERVER);
        session.tick(observation(arena, "minecraft:custom", 1));
        assertFalse(session.active());
        assertFalse(session.awaitingResume());
        assertEquals(0, disconnected);
    }

    @Test
    void completedRunDoesNotResumeOnLaterCustomJoin() {
        active(20);
        session.tick(observation(lobby, "minecraft:overworld", 20));
        session.connectionReset();
        session.connectionJoined(SERVER);
        session.tick(observation(arena, "minecraft:custom", 1));
        assertFalse(session.active());
        assertFalse(session.awaitingResume());
        assertEquals(0, disconnected);
    }

    @Test
    void forcedCustomRunResumesAsAutomaticAndSingleplayerClearsCandidate() {
        session.connectionJoined(SERVER);
        session.forceStart(observation(arena, "minecraft:custom", 20));
        session.connectionReset();
        session.connectionJoined(SERVER);
        session.tick(observation(arena, "minecraft:custom", 20));
        assertTrue(session.active());
        assertEquals(Mode.AUTO, session.mode());
        session.connectionReset();
        session.connectionJoined(null);
        session.tick(new Observation(true, false, arena, "minecraft:custom", 0, 0, 0, 1));
        assertFalse(session.active());
        assertFalse(session.awaitingResume());
        assertEquals(0, disconnected);
    }

    @Test
    void burstRiskRecordsAssessmentAndKeepsReconnectRecovery() {
        active(20);
        clock.advance(200);
        session.tick(observation(arena, "minecraft:custom", 14));
        assertTrue(session.active());
        clock.advance(200);
        session.tick(observation(arena, "minecraft:custom", 8));
        assertEquals(1, disconnected);
        assertEquals(EndReason.BURST_RISK, session.lastSummary().reason());
        assertTrue(session.awaitingResume());
        var event = sinks.getFirst().events.stream().filter(value -> value.type().equals("burst_risk")).findFirst()
            .orElseThrow();
        assertEquals(12, ((RunEvent.HealthRisk) event.data()).predictedLoss());
        session.connectionJoined(SERVER);
        session.tick(observation(new Object(), "minecraft:custom", 8));
        assertTrue(session.active());
        assertEquals(0, session.healthRisk().predictedLoss());
    }
}
