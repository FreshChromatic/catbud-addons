package github.freshchromatic.catbud_addons.island_assistive;

import java.util.List;

import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Context;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Rotation;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Vector;

final class AimTargetTransfer {

    record Box(Vector minimum, Vector maximum) {}

    record Preference(String source, String kind, double u, double v, double w) {}

    private record Axis(int index, int sign) {}

    static Vector select(Context context, List<Box> boxes, Preference preference, double reach) {
        Vector selected = null;
        double bestAngle = Double.POSITIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (var box : boxes) {
            if (!kind(box).equals(preference.kind)) {
                continue;
            }
            var size = box.maximum.subtract(box.minimum);
            var axes = axes(box, context.eye());
            double[] values = { preference.u, preference.v, preference.w };
            double[] coordinates = new double[3];
            for (int index = 0; index < axes.length; index++) {
                var axis = axes[index];
                double fraction = axis.sign > 0 ? values[index] : 1 - values[index];
                coordinates[axis.index] = component(box.minimum, axis.index) + component(size, axis.index) * fraction;
            }
            var point = new Vector(coordinates[0], coordinates[1], coordinates[2]);
            var offset = point.subtract(context.eye());
            double distance = offset.length();
            if (distance > reach) {
                continue;
            }
            var direction = Rotation.from(offset, context.start().yaw()).vector();
            if (boxes.stream().noneMatch(candidate -> intersects(candidate, context.eye(), direction, reach))) {
                continue;
            }
            double angle = context.start().vector().angle(offset);
            if (angle < bestAngle || angle == bestAngle && distance < bestDistance) {
                selected = point;
                bestAngle = angle;
                bestDistance = distance;
            }
        }
        return selected;
    }

    private static String kind(Box box) {
        var size = box.maximum.subtract(box.minimum);
        if (Math.min(size.x(), Math.min(size.y(), size.z())) >= .5) {
            return "solid";
        }
        return size.y() < .1 ? "horizontal_thin" : "side_thin";
    }

    private static Axis[] axes(Box box, Vector eye) {
        var size = box.maximum.subtract(box.minimum);
        var offset = eye.subtract(box.minimum.add(box.maximum).scale(.5));
        int normal = 0;
        boolean solid = kind(box).equals("solid");
        for (int index = 1; index < 3; index++) {
            boolean preferred = solid ? Math.abs(component(offset, index)) / component(size, index) > Math.abs(
                component(offset, normal)) / component(size, normal) : component(size, index) < component(size, normal);
            if (preferred) {
                normal = index;
            }
        }
        int sign = component(offset, normal) >= 0 ? 1 : -1;
        return switch (normal) {
            case 0 -> new Axis[] { new Axis(2, -sign), new Axis(1, 1), new Axis(0, sign) };
            case 1 -> new Axis[] { new Axis(0, 1), new Axis(2, -sign), new Axis(1, sign) };
            default -> new Axis[] { new Axis(0, sign), new Axis(1, 1), new Axis(2, sign) };
        };
    }

    private static boolean intersects(Box box, Vector eye, Vector direction, double reach) {
        double low = 0;
        double high = reach;
        for (int index = 0; index < 3; index++) {
            double origin = component(eye, index);
            double delta = component(direction, index);
            double minimum = component(box.minimum, index);
            double maximum = component(box.maximum, index);
            if (Math.abs(delta) < 1e-12) {
                if (origin < minimum || origin > maximum) {
                    return false;
                }
                continue;
            }
            double a = (minimum - origin) / delta;
            double b = (maximum - origin) / delta;
            low = Math.max(low, Math.min(a, b));
            high = Math.min(high, Math.max(a, b));
            if (high < low) {
                return false;
            }
        }
        return true;
    }

    private static double component(Vector vector, int index) {
        return switch (index) {
            case 0 -> vector.x();
            case 1 -> vector.y();
            default -> vector.z();
        };
    }
}
