package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.AimTargetTransfer.Box;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Rotation;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Vector;
import java.util.Comparator;
import java.util.List;

final class NeuralObservation {

    record AngularVelocity(double yaw, double pitch) {}

    record State(Vector eye, Rotation rotation, List<Box> boxes, double reach, AngularVelocity previousVelocity,
        Vector eyeVelocity, double shapeAge, List<Box> occluders) {

        State {
            boxes = List.copyOf(boxes);
            occluders = occluders == null ? null : List.copyOf(occluders);
        }
    }

    private static final int MAX_BOXES = 6;

    private static final Comparator<Box> BOX_ORDER = Comparator.comparingDouble((Box box) -> box.minimum().x())
        .thenComparingDouble(box -> box.minimum().y()).thenComparingDouble(box -> box.minimum().z())
        .thenComparingDouble(box -> box.maximum().x()).thenComparingDouble(box -> box.maximum().y())
        .thenComparingDouble(box -> box.maximum().z());

    static float[] encode(State state, int version) {
        if (version != 1 && version != 2 || state.boxes().size() > MAX_BOXES) {
            throw new IllegalArgumentException("Unsupported neural observation version or target shape");
        }
        var values = new float[version == 1 ? 77 : 119];
        values[0] = (float) (state.rotation().pitch() / 90);
        values[1] = (float) (state.previousVelocity().yaw() / 180);
        values[2] = (float) (state.previousVelocity().pitch() / 180);
        values[3] = (float) (state.eyeVelocity().x() / 5);
        values[4] = (float) (state.eyeVelocity().y() / 5);
        values[5] = (float) (state.eyeVelocity().z() / 5);
        values[6] = (float) (Math.min(state.shapeAge(), 2) / 2);
        values[7] = state.boxes().isEmpty() ? 1 : 0;
        values[8] = hit(state.eye(), state.rotation(), state.boxes(), state.reach()) ? 1 : 0;
        values[9] = (float) (state.reach() / 5);
        if (version == 2) {
            values[8] = -1;
            if (state.occluders() != null) {
                values[8] = visible(state.eye(), state.rotation(), state.boxes(), state.occluders(), state.reach()) ? 1
                    : 0;
                values[10] = 1;
            }
        }
        int offset = 11;
        for (var box : state.boxes().stream().sorted(BOX_ORDER).toList()) {
            var center = box.minimum().add(box.maximum()).scale(.5);
            var extent = box.maximum().subtract(box.minimum());
            var goal = Rotation.from(center.subtract(state.eye()), state.rotation().yaw());
            double minimumYaw = Double.POSITIVE_INFINITY;
            double maximumYaw = Double.NEGATIVE_INFINITY;
            double minimumPitch = Double.POSITIVE_INFINITY;
            double maximumPitch = Double.NEGATIVE_INFINITY;
            for (double x : new double[] { box.minimum().x(), box.maximum().x() }) {
                for (double y : new double[] { box.minimum().y(), box.maximum().y() }) {
                    for (double z : new double[] { box.minimum().z(), box.maximum().z() }) {
                        var direction = Rotation.from(new Vector(x, y, z).subtract(state.eye()), state.rotation()
                            .yaw());
                        double yaw = AimTrajectory.wrap(direction.yaw() - state.rotation().yaw());
                        minimumYaw = Math.min(minimumYaw, yaw);
                        maximumYaw = Math.max(maximumYaw, yaw);
                        minimumPitch = Math.min(minimumPitch, direction.pitch() - state.rotation().pitch());
                        maximumPitch = Math.max(maximumPitch, direction.pitch() - state.rotation().pitch());
                    }
                }
            }
            values[offset++] = (float) (AimTrajectory.wrap(goal.yaw() - state.rotation().yaw()) / 90);
            values[offset++] = (float) ((goal.pitch() - state.rotation().pitch()) / 90);
            values[offset++] = (float) (minimumYaw / 90);
            values[offset++] = (float) (maximumYaw / 90);
            values[offset++] = (float) (minimumPitch / 90);
            values[offset++] = (float) (maximumPitch / 90);
            values[offset++] = (float) (center.subtract(state.eye()).length() / state.reach());
            values[offset++] = (float) extent.x();
            values[offset++] = (float) extent.y();
            values[offset++] = (float) extent.z();
            values[offset++] = 1;
            if (version != 2) {
                continue;
            }
            var points = List.of(center, center.add(new Vector(-extent.x() * .48, 0, 0)), center.add(new Vector(extent
                .x() * .48, 0, 0)), center.add(new Vector(0, -extent.y() * .48, 0)), center.add(new Vector(0, extent.y()
                    * .48, 0)), center.add(new Vector(0, 0, -extent.z() * .48)), center.add(new Vector(0, 0, extent.z()
                        * .48)));
            for (var point : points) {
                if (state.occluders() == null) {
                    values[offset++] = -1;
                } else {
                    values[offset++] = pointVisible(state.eye(), point, box, state.occluders(), state.reach()) ? 1 : 0;
                }
            }
        }
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite neural observation");
            }
        }
        return values;
    }

    private static boolean pointVisible(Vector eye, Vector point, Box target, List<Box> occluders, double reach) {
        var direction = point.subtract(eye).unit();
        double distance = entry(eye, direction, target, reach);
        return Double.isFinite(distance) && occluders.stream().noneMatch(box -> entry(eye, direction, box,
            reach) < distance - 1e-8);
    }

    private static boolean visible(Vector eye, Rotation rotation, List<Box> boxes, List<Box> occluders, double reach) {
        var direction = rotation.vector();
        double distance = boxes.stream().mapToDouble(box -> entry(eye, direction, box, reach)).min().orElse(
            Double.POSITIVE_INFINITY);
        return Double.isFinite(distance) && occluders.stream().noneMatch(box -> entry(eye, direction, box,
            reach) < distance - 1e-8);
    }

    private static boolean hit(Vector eye, Rotation rotation, List<Box> boxes, double reach) {
        return boxes.stream().anyMatch(box -> Double.isFinite(entry(eye, rotation.vector(), box, reach)));
    }

    private static double entry(Vector eye, Vector direction, Box box, double reach) {
        double low = 0;
        double high = reach;
        double[] origins = { eye.x(), eye.y(), eye.z() };
        double[] deltas = { direction.x(), direction.y(), direction.z() };
        double[] minimum = { box.minimum().x(), box.minimum().y(), box.minimum().z() };
        double[] maximum = { box.maximum().x(), box.maximum().y(), box.maximum().z() };
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(deltas[axis]) < 1e-12) {
                if (origins[axis] < minimum[axis] || origins[axis] > maximum[axis]) {
                    return Double.POSITIVE_INFINITY;
                }
                continue;
            }
            double first = (minimum[axis] - origins[axis]) / deltas[axis];
            double second = (maximum[axis] - origins[axis]) / deltas[axis];
            low = Math.max(low, Math.min(first, second));
            high = Math.min(high, Math.max(first, second));
            if (high < low) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return low;
    }
}
