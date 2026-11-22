package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.LearnedAimTarget.Box;
import github.freshchromatic.catbud_addons.island_assistive.LearnedAimTarget.Preference;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Context;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Profile;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Rotation;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Vector;
import java.util.List;
import java.util.Map;

final class ReactiveAimMotion {

    enum State {
        IDLE, WAIT_AIR, UNREACHABLE, HOLD, TRACKING
    }

    record Decision(long revision, MotionSourceSampler.Choice choice, Vector goal, String fallback,
        String curveStrategy, String recoveryError, double planningMs) {}

    private record Gesture(Vector origin, Vector goal, double radius, ReactiveAimCurve curve) {

        Rotation direction(double elapsed, Vector eye, double previousYaw, boolean acquired) {
            if (acquired || curve == null) {
                return Rotation.from(goal.subtract(eye), previousYaw);
            }
            var direction = curve.guide().direction(elapsed, previousYaw);
            var point = origin.add(direction.vector().scale(radius));
            return Rotation.from(point.subtract(eye), previousYaw);
        }
    }

    private final MotionSourceSampler sampler;

    private AdaptiveAimTracker tracker;
    private Rotation           previousTarget;
    private Long               revision;
    private Gesture            gesture;
    private double             elapsed;
    private boolean            acquired;
    private boolean            canAttack;
    private State              state = State.IDLE;
    private Decision           lastDecision;
    private long               plans;

    ReactiveAimMotion(Rotation start, long seed, List<Profile> profiles, Map<String, Preference> preferences) {
        sampler = new MotionSourceSampler(seed, profiles, preferences);
        restart(start);
    }

    void restart(Rotation start) {
        tracker = new AdaptiveAimTracker(start);
        previousTarget = start;
        revision = null;
        gesture = null;
        elapsed = 0;
        acquired = false;
        canAttack = false;
        state = State.IDLE;
        lastDecision = null;
    }

    AdaptiveAimTracker tracker() {
        return tracker;
    }

    State state() {
        return state;
    }

    boolean canAttack() {
        return canAttack;
    }

    Decision lastDecision() {
        return lastDecision;
    }

    long plans() {
        return plans;
    }

    Rotation advance(Context context, List<Box> boxes, double reach, long revision, double seconds) {
        if (!(seconds >= 0 && seconds <= .1)) {
            throw new IllegalArgumentException("Frame duration must be between zero and 100 ms");
        }
        if (this.revision == null || this.revision != revision || boxes.isEmpty()) {
            this.revision = revision;
            gesture = null;
            elapsed = 0;
            acquired = false;
        }
        if (boxes.isEmpty()) {
            state = State.WAIT_AIR;
        } else if (gesture != null) {
            state = gesture.goal().subtract(context.eye()).length() <= reach ? State.TRACKING : State.UNREACHABLE;
        } else if (context.reference() == null) {
            state = State.UNREACHABLE;
        } else if (intersects(context.eye(), tracker.rotation(), boxes, reach)) {
            state = State.HOLD;
        } else {
            plan(context, boxes, reach, revision);
        }
        if (seconds == 0) {
            canAttack = active() && intersects(context.eye(), tracker.rotation(), boxes, reach);
            return tracker.rotation();
        }
        int steps = Math.max(1, (int) Math.ceil(seconds * 240));
        double interval = seconds / steps;
        for (int index = 0; index < steps; index++) {
            Rotation target;
            if (state == State.TRACKING) {
                acquired = acquired || intersects(context.eye(), tracker.rotation(), boxes, reach);
                elapsed += interval;
                target = gesture.direction(elapsed, context.eye(), previousTarget.yaw(), acquired);
                tracker.advance(target, previousTarget, interval);
            } else {
                target = tracker.rotation();
                tracker.advance(target, target, interval);
            }
            previousTarget = target;
        }
        canAttack = active() && intersects(context.eye(), tracker.rotation(), boxes, reach);
        return tracker.rotation();
    }

    private boolean active() {
        return state == State.TRACKING || state == State.HOLD;
    }

    private void plan(Context context, List<Box> boxes, double reach, long revision) {
        long started = System.nanoTime();
        var current = new Context(context.session(), context.family(), context.eye(), tracker.rotation(), context
            .reference());
        var choice = sampler.choose(sampler.compatible(current, boxes, reach));
        var option = choice.option();
        var goal = option == null ? current.reference() : option.goal();
        var curve = option == null ? null
            : ReactiveAimCurve.create(new Context(current.session(), current.family(), current.eye(), current.start(),
                goal), option.profile());
        gesture = new Gesture(current.eye(), goal, goal.subtract(current.eye()).length(), curve);
        lastDecision = new Decision(revision, choice, goal, option == null ? "NO_COMPATIBLE_SOURCE" : null,
            curve == null ? null : curve.strategy(), curve == null ? null : curve.recoveryError(), (System.nanoTime()
                - started) / 1e6);
        plans++;
        state = State.TRACKING;
    }

    static boolean intersects(Vector eye, Rotation rotation, List<Box> boxes, double reach) {
        var direction = rotation.vector();
        for (var box : boxes) {
            double low = 0;
            double high = reach;
            boolean intersects = true;
            for (int axis = 0; axis < 3; axis++) {
                double origin = component(eye, axis);
                double delta = component(direction, axis);
                double minimum = component(box.minimum(), axis);
                double maximum = component(box.maximum(), axis);
                if (Math.abs(delta) < 1e-12) {
                    if (origin < minimum || origin > maximum) {
                        intersects = false;
                        break;
                    }
                    continue;
                }
                double a = (minimum - origin) / delta;
                double b = (maximum - origin) / delta;
                low = Math.max(low, Math.min(a, b));
                high = Math.min(high, Math.max(a, b));
                if (high < low) {
                    intersects = false;
                    break;
                }
            }
            if (intersects) {
                return true;
            }
        }
        return false;
    }

    private static double component(Vector vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x();
            case 1 -> vector.y();
            default -> vector.z();
        };
    }
}
