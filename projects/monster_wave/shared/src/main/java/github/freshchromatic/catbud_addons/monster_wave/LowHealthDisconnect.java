package github.freshchromatic.catbud_addons.monster_wave;

import java.util.ArrayDeque;

final class LowHealthDisconnect {

    static final float THRESHOLD = 4.0F;
    static final float RESERVE   = 2.0F;

    enum Trigger {
        NONE, LOW_HEALTH, BURST_RISK
    }

    record Assessment(float health, float largestDrop, float recentDrop, float predictedLoss, boolean healthChanged,
        Trigger trigger) {}

    private record Loss(long nanos, float amount) {}

    private final RunClock         clock;
    private final ArrayDeque<Loss> losses = new ArrayDeque<>();

    private boolean    triggered;
    private float      previousHealth = Float.NaN;
    private Assessment assessment     = new Assessment(Float.NaN, 0, 0, 0, false, Trigger.NONE);

    LowHealthDisconnect(RunClock clock) {
        this.clock = clock;
    }

    boolean check(boolean active, boolean multiplayer, float health) {
        if (triggered || !active || !multiplayer || !Float.isFinite(health)) {
            return false;
        }
        long now = clock.nanoTime();
        boolean changed = !Float.isFinite(previousHealth) || health != previousHealth;
        if (Float.isFinite(previousHealth) && health < previousHealth) {
            losses.addLast(new Loss(now, previousHealth - health));
        }
        previousHealth = health;
        while (!losses.isEmpty() && now - losses.getFirst().nanos() >= 5_000_000_000L) {
            losses.removeFirst();
        }
        float largest = 0;
        float recent = 0;
        for (var loss : losses) {
            largest = Math.max(largest, loss.amount());
            if (now - loss.nanos() < 1_000_000_000L) {
                recent += loss.amount();
            }
        }
        float predicted = Math.max(largest, recent);
        Trigger trigger = Trigger.NONE;
        if (health < THRESHOLD) {
            trigger = Trigger.LOW_HEALTH;
        } else if (predicted > 0 && health - predicted <= RESERVE) {
            trigger = Trigger.BURST_RISK;
        }
        assessment = new Assessment(health, largest, recent, predicted, changed, trigger);
        triggered = trigger != Trigger.NONE;
        return triggered;
    }

    Assessment assessment() {
        return assessment;
    }

    void reset() {
        triggered = false;
        previousHealth = Float.NaN;
        losses.clear();
        assessment = new Assessment(Float.NaN, 0, 0, 0, false, Trigger.NONE);
    }
}
