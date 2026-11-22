package github.freshchromatic.catbud_addons.island_assistive;

import java.util.List;

final class AcquiredRhythmMotion implements StudyMotion {

    private final RhythmMotion.Context       context;
    private final List<LearnedAimTarget.Box> boxes;
    private final double                     reach;
    private final RhythmMotion.Controller    controller;

    private RhythmMotion.Rotation current;
    private boolean               acquired;

    private AcquiredRhythmMotion(RhythmMotion.Context context, RhythmMotion.Profile profile, List<LearnedAimTarget.Box> boxes, double reach, RetimedRhythmCurve curve) {
        this.context = context;
        this.boxes = List.copyOf(boxes);
        this.reach = reach;
        current = context.start();
        var finalDirection = RhythmMotion.Rotation.from(context.reference().subtract(context.eye()), current.yaw());
        controller = new RhythmMotion.Controller(context, profile, true, (seconds, previousYaw) -> acquired
            ? finalDirection
            : curve.direction(seconds, previousYaw));
    }

    static AcquiredRhythmMotion create(RhythmMotion.Context context, RhythmMotion.Profile profile, LearnedAimTarget.Preference preference, List<LearnedAimTarget.Box> boxes, double reach) {
        var goal = preference == null ? null : LearnedAimTarget.select(context, boxes, preference, reach);
        if (goal == null) {
            return null;
        }
        var targeted = new RhythmMotion.Context(context.session(), context.family(), context.eye(), context.start(),
            goal);
        var plan = new RhythmMotion.Controller(targeted, profile, true);
        return new AcquiredRhythmMotion(targeted, profile, boxes, reach, RetimedRhythmCurve.create(plan, profile));
    }

    @Override
    public RhythmMotion.Rotation advance(double seconds) {
        int steps = Math.max(1, (int) Math.ceil(seconds * 240));
        double interval = seconds / steps;
        if (interval <= 0) {
            return current;
        }
        for (int index = 0; index < steps; index++) {
            if (!acquired && intersectsCurrentView()) {
                acquired = true;
            }
            current = controller.advance(interval);
        }
        return current;
    }

    @Override
    public double peakSpeed() {
        return controller.peakSpeed();
    }

    @Override
    public double peakAcceleration() {
        return controller.peakAcceleration();
    }

    private boolean intersectsCurrentView() {
        var direction = current.vector();
        for (var box : boxes) {
            double low = 0;
            double high = reach;
            boolean intersects = true;
            for (int axis = 0; axis < 3; axis++) {
                double origin = component(context.eye(), axis);
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

    private static double component(RhythmMotion.Vector vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x();
            case 1 -> vector.y();
            default -> vector.z();
        };
    }
}
