package github.freshchromatic.catbud_addons.monster_wave;

import java.util.UUID;

import static github.freshchromatic.catbud_addons.monster_wave.RunEvent.EndReason;

final class MonsterWaveSession {

    enum Phase {
        IDLE, QUEUED, READY, ACTIVE
    }

    enum Mode {
        AUTO, FORCED, OFF
    }

    record Observation(boolean connected, boolean multiplayer, Object world, String dimension, double x, double y,
        double z, float health, int nearbyHostiles) {

        Observation(boolean connected, boolean multiplayer, Object world, String dimension, double x, double y, double z, float health) {
            this(connected, multiplayer, world, dimension, x, y, z, health, -1);
        }

        boolean available() {
            return connected && world != null;
        }
    }

    interface RunFactory {

        MonsterWaveRun create(Mode mode);
    }

    record ConnectionIdentity(String server, UUID player) {}

    private record ResumeCandidate(ConnectionIdentity connection, UUID previousRunId, long disconnectedNanos) {}

    private static final String TARGET_DIMENSION = "minecraft:custom";

    private final RunClock            clock;
    private final RunFactory          runs;
    private final Runnable            disconnect;
    private final LowHealthDisconnect protection;
    private final BossbarTracker      bossbars = new BossbarTracker(this::onBossbar);

    private boolean enabled = true;
    private boolean disconnectRequested;
    private Phase   phase   = Phase.IDLE;
    private Mode    mode    = Mode.AUTO;

    private UUID           bossId;
    private Long           vanishedNanos;
    private boolean        teleportSeen;
    private Observation    previous;
    private MonsterWaveRun run;
    private RunSink        lastSink;
    private RunEvent.Ended lastSummary;

    private ConnectionIdentity connectionIdentity;
    private ResumeCandidate    resumeCandidate;

    MonsterWaveSession(RunClock clock, RunFactory runs, Runnable disconnect) {
        this.clock = clock;
        this.runs = runs;
        this.disconnect = disconnect;
        this.protection = new LowHealthDisconnect(clock);
    }

    BossbarTracker bossbars() {
        return bossbars;
    }

    Phase phase() {
        return phase;
    }

    Mode mode() {
        return mode;
    }

    boolean active() {
        return enabled && !disconnectRequested && (mode == Mode.FORCED || mode == Mode.AUTO && phase == Phase.ACTIVE);
    }

    boolean enabled() {
        return enabled;
    }

    void enabled(boolean enabled) {
        if (this.enabled != enabled) {
            end(EndReason.DISABLED);
            this.enabled = enabled;
            mode = Mode.AUTO;
        }
    }

    void connectionReset() {
        rememberActiveRun();
        end(EndReason.DISCONNECTED);
        mode = Mode.AUTO;
        disconnectRequested = false;
        previous = null;
        bossbars.clear();
    }

    void connectionJoined(ConnectionIdentity identity) {
        connectionReset();
        connectionIdentity = identity;
        if (resumeCandidate != null && !resumeCandidate.connection().equals(identity)) {
            resumeCandidate = null;
        }
    }

    boolean awaitingResume() {
        return resumeCandidate != null;
    }

    private void rememberActiveRun() {
        if (run != null && active() && connectionIdentity != null && previous != null && previous.multiplayer()
            && TARGET_DIMENSION.equals(previous.dimension())) {
            resumeCandidate = new ResumeCandidate(connectionIdentity, run.id(), clock.nanoTime());
        }
    }

    private void resumeIfApplicable(Observation now) {
        if (resumeCandidate == null) {
            return;
        }
        var candidate = resumeCandidate;
        resumeCandidate = null;
        if (mode != Mode.AUTO || !now.multiplayer() || !TARGET_DIMENSION.equals(now.dimension()) || !candidate
            .connection().equals(connectionIdentity)) {
            return;
        }
        start();
        activate();
        event("run_resumed", new RunEvent.Resumed(candidate.previousRunId(), Math.max(0, (clock.nanoTime() - candidate
            .disconnectedNanos()) / 1_000_000)));
        event("world", new RunEvent.World(now.dimension(), true, false, false));
    }

    boolean forceStart(Observation now) {
        if (!enabled || disconnectRequested || !now.available() || !now.multiplayer()) {
            return false;
        }
        end(EndReason.MODE_CHANGED);
        mode = Mode.FORCED;
        start();
        activate();
        previous = now;
        event("world", new RunEvent.World(now.dimension(), false, false, false));
        checkHealth(now);
        return true;
    }

    void forceStop() {
        end(EndReason.MANUAL_STOP);
        mode = Mode.OFF;
    }

    void useAuto() {
        end(EndReason.MODE_CHANGED);
        mode = Mode.AUTO;
    }

    void clientStopped() {
        end(EndReason.CLIENT_STOPPED);
    }

    void teleport(String source) {
        if (enabled && mode == Mode.AUTO && (phase == Phase.QUEUED || phase == Phase.READY)) {
            teleportSeen = true;
        }
        event("teleport", new RunEvent.Teleport(source));
    }

    void event(String type, RunEvent.Payload payload) {
        if (run != null) {
            run.event(type, payload);
        }
    }

    private void onBossbar(BossbarTracker.Change change) {
        if (!enabled || disconnectRequested || mode != Mode.AUTO || resumeCandidate != null) {
            if (active()) {
                recordBossbar(change.operation(), change.bar());
            }
            return;
        }
        var bar = change.bar();
        boolean nameEvent = change.operation().equals("add") || change.operation().equals("name");
        boolean started = false;
        if (nameEvent && phase == Phase.IDLE && bar.signal() == BossbarTracker.Signal.QUEUE) {
            phase = Phase.QUEUED;
            bossId = bar.id();
            start();
            started = true;
        } else if (nameEvent && (phase == Phase.QUEUED || phase == Phase.READY && bar.id().equals(bossId)) && bar
            .signal() == BossbarTracker.Signal.READY) {
            ready(bar.id());
        }
        if (!started) {
            recordBossbar(change.operation(), bar);
        }
    }

    private void recordBossbar(String operation, BossbarTracker.Tracked bar) {
        event("bossbar", new RunEvent.Bossbar(operation, bar.id(), bar.value(), bar.signal().name()));
    }

    private void start() {
        protection.reset();
        run = runs.create(mode);
        lastSink = run.sink();
        state();
        for (var bar : bossbars.snapshot()) {
            recordBossbar("snapshot", bar);
        }
    }

    private void ready(UUID id) {
        bossId = id;
        vanishedNanos = null;
        phase = Phase.READY;
        if (run != null) {
            run.ready();
        }
        state();
    }

    private void activate() {
        phase = Phase.ACTIVE;
        vanishedNanos = null;
        teleportSeen = false;
        run.activate();
        state();
    }

    private void state() {
        event("state", new RunEvent.State(phase.name(), mode.name()));
    }

    void tick(Observation now) {
        if (!now.connected()) {
            connectionReset();
            return;
        }
        if (!enabled || disconnectRequested || !now.available()) {
            return;
        }
        resumeIfApplicable(now);
        boolean changedWorld = previous != null && previous.world() != now.world();
        boolean changedDimension = previous != null && !previous.dimension().equals(now.dimension());
        boolean positionJump = previous != null && distanceSquared(previous, now) > 64 * 64;
        boolean enteredCustom = changedDimension && TARGET_DIMENSION.equals(now.dimension());
        if (changedWorld || changedDimension || positionJump) {
            event("world", new RunEvent.World(now.dimension(), changedWorld, changedDimension, positionJump));
        }

        if (mode == Mode.AUTO) {
            if (phase == Phase.IDLE) {
                for (var bar : bossbars.snapshot()) {
                    if (bar.signal() == BossbarTracker.Signal.QUEUE) {
                        onBossbar(new BossbarTracker.Change("name", bar));
                        break;
                    }
                }
            }
            if (phase == Phase.QUEUED || phase == Phase.READY) {
                updateWaiting(now, enteredCustom || changedWorld || positionJump || teleportSeen);
            } else if (phase == Phase.ACTIVE && !TARGET_DIMENSION.equals(now.dimension())) {
                end(EndReason.LEFT_TARGET_WORLD);
            }
        }
        previous = now;
        checkHealth(now);
    }

    private void updateWaiting(Observation now, boolean teleportEvidence) {
        if (phase == Phase.QUEUED || !bossbars.contains(bossId)) {
            for (var bar : bossbars.snapshot()) {
                if (bar.signal() == BossbarTracker.Signal.READY) {
                    ready(bar.id());
                    break;
                }
            }
        }
        boolean tracked = bossbars.contains(bossId);
        if (tracked) {
            vanishedNanos = null;
        } else if (vanishedNanos == null) {
            vanishedNanos = clock.nanoTime();
        }
        boolean confirmed = TARGET_DIMENSION.equals(now.dimension()) && teleportEvidence;
        if (confirmed && (phase == Phase.READY || !tracked)) {
            activate();
        } else if (vanishedNanos != null && clock.nanoTime() - vanishedNanos >= (phase == Phase.READY ? 15_000_000_000L
            : 3_000_000_000L)) {
            end(EndReason.QUEUE_CANCELLED);
        }
    }

    private static double distanceSquared(Observation a, Observation b) {
        double x = a.x() - b.x();
        double y = a.y() - b.y();
        double z = a.z() - b.z();
        return x * x + y * y + z * z;
    }

    private void checkHealth(Observation now) {
        if (active() && now.multiplayer() && Float.isFinite(now.health())) {
            run.health(now.health());
            boolean shouldDisconnect = protection.check(true, true, now.health());
            var risk = protection.assessment();
            if (risk.healthChanged()) {
                event("health_observed", new RunEvent.HealthRisk(risk.health(), risk.largestDrop(), risk.recentDrop(),
                    risk.predictedLoss(), LowHealthDisconnect.RESERVE, risk.trigger().name(), now.nearbyHostiles(),
                    NearbyMobCounter.RADIUS));
            }
            if (shouldDisconnect) {
                rememberActiveRun();
                disconnectRequested = true;
                EndReason reason = risk.trigger() == LowHealthDisconnect.Trigger.BURST_RISK ? EndReason.BURST_RISK
                    : EndReason.LOW_HEALTH;
                event(reason == EndReason.BURST_RISK ? "burst_risk" : "low_health", new RunEvent.HealthRisk(risk
                    .health(), risk.largestDrop(), risk.recentDrop(), risk.predictedLoss(), LowHealthDisconnect.RESERVE,
                    risk.trigger().name(), now.nearbyHostiles(), NearbyMobCounter.RADIUS));
                end(reason);
                disconnect.run();
            }
        }
    }

    private void end(EndReason reason) {
        if (reason != EndReason.LOW_HEALTH && reason != EndReason.BURST_RISK && reason != EndReason.DISCONNECTED) {
            resumeCandidate = null;
        }
        if (run != null) {
            lastSummary = run.end(reason);
            run = null;
        }
        phase = Phase.IDLE;
        bossId = null;
        vanishedNanos = null;
        teleportSeen = false;
        protection.reset();
        if (mode == Mode.FORCED) {
            mode = Mode.AUTO;
        }
    }

    long elapsed() {
        return run == null ? 0 : run.elapsed();
    }

    LowHealthDisconnect.Assessment healthRisk() {
        return protection.assessment();
    }

    Long activeElapsed() {
        return run == null ? null : run.activeElapsed();
    }

    RunSink lastSink() {
        return lastSink;
    }

    RunEvent.Ended lastSummary() {
        return lastSummary;
    }
}
