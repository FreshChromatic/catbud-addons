package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.AimTargetTransfer.Box;
import github.freshchromatic.catbud_addons.island_assistive.AimTargetTransfer.Preference;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Context;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Profile;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Rotation;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Vector;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class VariedAimMotion {

    enum State {
        IDLE, WAIT_AIR, UNREACHABLE, HOLD, TRACKING
    }

    record Decision(long revision, AimSourceSampler.Choice choice, Vector goal, String fallback, String curveStrategy,
        String recoveryError, double planningMs) {}

    private record Gesture(Vector origin, Vector goal, double radius, AimCurve curve) {

        Rotation direction(double elapsed, Vector eye, double previousYaw, boolean acquired) {
            if (acquired || curve == null) {
                return Rotation.from(goal.subtract(eye), previousYaw);
            }
            var direction = curve.guide().direction(elapsed, previousYaw);
            var point = origin.add(direction.vector().scale(radius));
            return Rotation.from(point.subtract(eye), previousYaw);
        }
    }

    private final AimSourceSampler  sampler;
    private final Predicate<Vector> visible;

    private AimMotionTracker tracker;
    private Rotation         previousTarget;
    private Long             revision;
    private Gesture          gesture;
    private double           elapsed;
    private boolean          acquired;
    private boolean          canAttack;
    private State            state = State.IDLE;
    private Decision         lastDecision;
    private long             plans;

    VariedAimMotion(Rotation start, long seed, List<Profile> profiles, Map<String, Preference> preferences) {
        this(start, seed, profiles, preferences, point -> true);
    }

    VariedAimMotion(Rotation start, long seed, List<Profile> profiles, Map<String, Preference> preferences, Predicate<Vector> visible) {
        sampler = new AimSourceSampler(seed, profiles, preferences);
        this.visible = visible;
        restart(start);
    }

    void restart(Rotation start) {
        tracker = new AimMotionTracker(start);
        previousTarget = start;
        revision = null;
        gesture = null;
        elapsed = 0;
        acquired = false;
        canAttack = false;
        state = State.IDLE;
        lastDecision = null;
    }

    AimMotionTracker tracker() {
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
        if (this.revision == null || this.revision != revision || boxes.isEmpty() || gesture != null && !visible.test(
            gesture.goal())) {
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
        } else if (visibleHit(context, boxes, reach)) {
            state = State.HOLD;
        } else {
            plan(context, boxes, reach, revision);
        }
        if (seconds == 0) {
            canAttack = active() && visibleHit(context, boxes, reach);
            return tracker.rotation();
        }
        int steps = Math.max(1, (int) Math.ceil(seconds * 240));
        double interval = seconds / steps;
        for (int index = 0; index < steps; index++) {
            Rotation target;
            if (state == State.TRACKING) {
                acquired = acquired || visibleHit(context, boxes, reach);
                elapsed += interval;
                target = gesture.direction(elapsed, context.eye(), previousTarget.yaw(), acquired);
                tracker.advance(target, previousTarget, interval);
            } else {
                target = tracker.rotation();
                tracker.advance(target, target, interval);
            }
            previousTarget = target;
        }
        canAttack = active() && visibleHit(context, boxes, reach);
        return tracker.rotation();
    }

    private boolean active() {
        return state == State.TRACKING || state == State.HOLD;
    }

    private boolean visibleHit(Context context, List<Box> boxes, double reach) {
        return intersects(context.eye(), tracker.rotation(), boxes, reach) && visible.test(context.eye().add(tracker
            .rotation().vector().scale(reach)));
    }

    private void plan(Context context, List<Box> boxes, double reach, long revision) {
        long started = System.nanoTime();
        var current = new Context(context.session(), context.family(), context.eye(), tracker.rotation(), context
            .reference());
        var choice = sampler.choose(sampler.compatible(current, boxes, reach).stream().filter(option -> visible.test(
            option.goal())).toList());
        var option = choice.option();
        var goal = option == null ? current.reference() : option.goal();
        var curve = option == null ? null
            : AimCurve.create(new Context(current.session(), current.family(), current.eye(), current.start(), goal),
                option.profile());
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
