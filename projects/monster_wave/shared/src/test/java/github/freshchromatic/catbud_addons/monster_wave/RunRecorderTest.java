package github.freshchromatic.catbud_addons.monster_wave;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RunRecorderTest {

    @TempDir Path directory;

    MonsterWaveRun run(RunSink sink, MonsterWaveSessionTest.Clock clock) {
        return new MonsterWaveRun(UUID.randomUUID(), clock, sink, "1.0.3", "test", "AUTO");
    }

    List<JsonObject> records() throws Exception {
        var records = new ArrayList<JsonObject>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.sorted(Comparator.comparing(p -> p.toString().replace(".jsonl", "-part-00000")))
                .toList()) {
                for (String line : Files.readAllLines(path)) {
                    records.add(JsonParser.parseString(line).getAsJsonObject());
                }
            }
        }
        return records;
    }

    @Test
    void recordsJsonLinesWithUtcTimingAndFinalSummaryAcrossSegments() throws Exception {
        var recorder = new RunRecorder(directory.resolve("run.jsonl"), 8192, 1000);
        var clock = new MonsterWaveSessionTest.Clock();
        var run = run(recorder, clock);
        clock.advance(1000);
        run.ready();
        run.activate();
        run.health(4);
        run.event("text", new RunEvent.Text(RunEvent.Channel.TITLE, MonsterWaveSessionTest.value("怪物浪潮").name(),
            false));
        clock.advance(2000);
        run.end(RunEvent.EndReason.LEFT_TARGET_WORLD);
        assertTrue(recorder.await(5000));
        assertNull(recorder.error());
        var events = records();
        assertEquals(3, events.size());
        assertEquals("run_started", events.getFirst().get("type").getAsString());
        assertEquals("2026-10-18T00:00:00Z", events.getFirst().get("receivedAt").getAsString());
        assertEquals("怪物浪潮", events.get(1).getAsJsonObject("data").getAsJsonObject("value").get("text").getAsString());
        var end = events.getLast();
        assertEquals("run_ended", end.get("type").getAsString());
        assertTrue(end.get("complete").getAsBoolean());
        assertEquals(3000, end.getAsJsonObject("data").get("totalDurationMs").getAsLong());
        assertEquals(2000, end.getAsJsonObject("data").get("activeDurationMs").getAsLong());
        assertTrue(events.stream().allMatch(e -> e.get("schemaVersion").getAsInt() == 1));
        try (var paths = Files.list(directory)) {
            assertTrue(paths.count() > 1);
        }
        var history = RunHistory.latest(directory);
        assertTrue(history.complete());
        assertEquals(3000, history.totalDurationMs());
        assertEquals(2000, history.activeDurationMs());
    }

    @Test
    void queueOverflowKeepsTerminalSummaryAndReportsEveryDroppedEvent() throws Exception {
        var recorder = new RunRecorder(directory.resolve("run.jsonl"), 1, 16 * 1024 * 1024);
        var clock = new MonsterWaveSessionTest.Clock();
        var run = run(recorder, clock);
        for (int i = 0; i < 30_000; i++) {
            run.event("text", new RunEvent.Text(RunEvent.Channel.ACTIONBAR, MonsterWaveSessionTest.value("update-" + i)
                .name(), true));
        }
        run.end(RunEvent.EndReason.LOW_HEALTH);
        assertTrue(recorder.await(10_000));
        assertNull(recorder.error());
        assertTrue(recorder.droppedEvents() > 0);
        var events = records();
        var end = events.getLast();
        assertEquals("run_ended", end.get("type").getAsString());
        assertFalse(end.get("complete").getAsBoolean());
        assertEquals("LOW_HEALTH", end.getAsJsonObject("data").get("reason").getAsString());
        long gaps = events.stream().filter(e -> e.get("type").getAsString().equals("recording_gap")).mapToLong(e -> e
            .get("droppedEvents").getAsLong()).sum();
        assertEquals(recorder.droppedEvents(), gaps);
        assertEquals(recorder.droppedEvents(), end.get("droppedEvents").getAsLong());
        assertFalse(RunHistory.latest(directory).complete());
    }

    @Test
    void unavailableDiskDoesNotStopHealthProtection() throws Exception {
        Path parentFile = directory.resolve("not-a-directory");
        Files.writeString(parentFile, "occupied");
        var recorder = new RunRecorder(parentFile.resolve("run.jsonl"), 1, 1000);
        assertTrue(recorder.await(5000));
        assertNotNull(recorder.error());
        var clock = new MonsterWaveSessionTest.Clock();
        int[] disconnects = { 0 };
        var session = new MonsterWaveSession(clock, mode -> run(recorder, clock), () -> disconnects[0]++);
        session.forceStart(new MonsterWaveSession.Observation(true, true, new Object(), "minecraft:custom", 0, 0, 0,
            3));
        assertEquals(1, disconnects[0]);
        assertEquals(RunEvent.EndReason.LOW_HEALTH, session.lastSummary().reason());
    }

    @Test
    void incompleteAndCorruptedFilesDoNotInventCompletionOrEndTime() throws Exception {
        Files.writeString(directory.resolve("run.jsonl"), "{\"type\":\"run_started\"}\n");
        var unfinished = RunHistory.latest(directory);
        assertFalse(unfinished.complete());
        assertNull(unfinished.reason());
        assertNull(unfinished.totalDurationMs());
        Files.writeString(directory.resolve("run.jsonl"), "{\"type\":\"run_ended\"");
        var corrupted = RunHistory.latest(directory);
        assertFalse(corrupted.complete());
        assertNotNull(corrupted.error());
    }

    @Test
    void noHistoryIsDifferentFromIncompleteHistory() {
        assertNull(RunHistory.latest(directory));
        assertNull(RunHistory.latest(directory.resolve("missing")));
    }

    @Test
    void terminalIsIdempotentAndDoesNotAcceptLaterMessages() throws Exception {
        var recorder = new RunRecorder(directory.resolve("run.jsonl"), 8192, 1000);
        var run = run(recorder, new MonsterWaveSessionTest.Clock());
        var first = run.end(RunEvent.EndReason.LOW_HEALTH);
        assertSame(first, run.end(RunEvent.EndReason.DISCONNECTED));
        run.event("ignored", new RunEvent.Health(20));
        assertTrue(recorder.await(5000));
        assertEquals(2, records().size());
    }
}
