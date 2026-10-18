package github.freshchromatic.catbud_addons.monster_wave;

import java.time.Instant;
import java.util.UUID;

final class MonsterWaveRun {

    private final UUID     id;
    private final RunClock clock;
    private final RunSink  sink;
    private final long     triggeredNanos;
    private final Instant  triggeredAt;

    private long           sequence;
    private Long           activeNanos;
    private Instant        readyAt;
    private Instant        activeAt;
    private Float          minimumHealth;
    private RunEvent.Ended summary;

    MonsterWaveRun(UUID id, RunClock clock, RunSink sink, String version, String minecraft, String mode) {
        this.id = id;
        this.clock = clock;
        this.sink = sink;
        this.triggeredNanos = clock.nanoTime();
        this.triggeredAt = clock.instant();
        event("run_started", new RunEvent.Started(version, minecraft, mode));
    }

    void ready() {
        if (readyAt == null) {
            readyAt = clock.instant();
        }
    }

    void activate() {
        if (activeNanos == null) {
            activeNanos = clock.nanoTime();
            activeAt = clock.instant();
        }
    }

    void health(float value) {
        if (Float.isFinite(value) && (minimumHealth == null || value < minimumHealth)) {
            minimumHealth = value;
        }
    }

    long elapsed() {
        return elapsed(triggeredNanos);
    }

    Long activeElapsed() {
        return activeNanos == null ? null : elapsed(activeNanos);
    }

    private long elapsed(long start) {
        return Math.max(0, (clock.nanoTime() - start) / 1_000_000);
    }

    void event(String type, RunEvent.Payload payload) {
        if (summary == null) {
            sink.accept(envelope(type, payload));
        }
    }

    RunEvent.Ended end(RunEvent.EndReason reason) {
        if (summary == null) {
            summary = new RunEvent.Ended(reason, triggeredAt, readyAt, activeAt, clock.instant(), elapsed(),
                activeElapsed(), minimumHealth, activeAt != null);
            sink.finish(envelope("run_ended", summary));
        }
        return summary;
    }

    private RunEvent envelope(String type, RunEvent.Payload payload) {
        return new RunEvent(1, id, ++sequence, clock.instant(), elapsed(), activeElapsed(), type, payload);
    }

    RunSink sink() {
        return sink;
    }

    UUID id() {
        return id;
    }
}
