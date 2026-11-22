package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Profile;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Rotation;
import java.util.ArrayList;
import java.util.List;

final class UniformAimCurve implements RhythmMotion.Guide {

    private record Cubic(double a, double b, double c, double d) {

        double value(double fraction) {
            return ((a * fraction + b) * fraction + c) * fraction + d;
        }
    }

    private record Segment(double start, double end, Cubic yaw, Cubic pitch) {

        double[] bounds(double width) {
            double ay = 3 * yaw.a, by = 2 * yaw.b, cy = yaw.c;
            double ap = 3 * pitch.a, bp = 2 * pitch.b, cp = pitch.c;
            var candidates = new ArrayList<Double>(List.of(0.0, 1.0));
            candidates.addAll(roots(4 * (ay * ay + ap * ap), 6 * (ay * by + ap * bp), 2 * (by * by + bp * bp + 2 * ay
                * cy + 2 * ap * cp), 2 * (by * cy + bp * cp)));
            double speed = 0;
            for (double fraction : candidates) {
                speed = Math.max(speed, Math.hypot((ay * fraction + by) * fraction + cy, (ap * fraction + bp) * fraction
                    + cp));
            }
            double acceleration = Math.max(Math.hypot(2 * yaw.b, 2 * pitch.b), Math.hypot(6 * yaw.a + 2 * yaw.b, 6
                * pitch.a + 2 * pitch.b));
            return new double[] { speed / width * (1 + 1e-12), acceleration / (width * width) * (1 + 1e-12) };
        }
    }

    private final double        delay;
    private final double        duration;
    private final List<Segment> segments;

    private UniformAimCurve(double delay, double duration, List<Segment> segments) {
        this.delay = delay;
        this.duration = duration;
        this.segments = List.copyOf(segments);
    }

    static UniformAimCurve create(RhythmMotion.Controller plan, Profile profile) {
        int count = profile.knots().size();
        double[] yaw = new double[count];
        double[] pitch = new double[count];
        double[] times = new double[count];
        double previousYaw = 0;
        for (int index = 0; index < count; index++) {
            double fraction = profile.knots().get(index).fraction();
            var direction = plan.target(profile.delaySeconds() + fraction * plan.duration(), previousYaw);
            yaw[index] = direction.yaw();
            pitch[index] = direction.pitch();
            times[index] = fraction * plan.duration();
            previousYaw = direction.yaw();
        }
        var yawCubics = cubics(times, yaw);
        var pitchCubics = cubics(times, pitch);
        var segments = new ArrayList<Segment>();
        double scale = 1;
        for (int index = 0; index < count - 1; index++) {
            var segment = new Segment(profile.knots().get(index).fraction(), profile.knots().get(index + 1).fraction(),
                yawCubics.get(index), pitchCubics.get(index));
            var bounds = segment.bounds(times[index + 1] - times[index]);
            scale = Math.max(scale, Math.max(bounds[0] / 180, Math.sqrt(bounds[1] / 1440)));
            segments.add(segment);
        }
        scale *= 1 + 1e-9;
        for (int index = 0; index < segments.size(); index++) {
            var bounds = segments.get(index).bounds((times[index + 1] - times[index]) * scale);
            if (bounds[0] > 180 || bounds[1] > 1440) {
                throw new IllegalStateException("Uniform recovery failed full-curve certification");
            }
        }
        return new UniformAimCurve(profile.delaySeconds(), plan.duration() * scale, segments);
    }

    @Override
    public Rotation direction(double seconds, double previousYaw) {
        double fraction = Math.clamp((seconds - delay) / duration, 0, 1);
        var selected = segments.getLast();
        for (var segment : segments) {
            if (fraction <= segment.end()) {
                selected = segment;
                break;
            }
        }
        double amount = Math.clamp((fraction - selected.start()) / (selected.end() - selected.start()), 0, 1);
        return new Rotation(selected.yaw().value(amount), selected.pitch().value(amount));
    }

    private static List<Cubic> cubics(double[] times, double[] values) {
        int count = times.length;
        double[] widths = new double[count - 1];
        double[] secants = new double[count - 1];
        double[] slopes = new double[count];
        for (int index = 0; index < count - 1; index++) {
            widths[index] = times[index + 1] - times[index];
            secants[index] = (values[index + 1] - values[index]) / widths[index];
        }
        for (int index = 1; index < count - 1; index++) {
            double previous = secants[index - 1];
            double following = secants[index];
            if (previous * following > 0) {
                double a = 2 * widths[index] + widths[index - 1];
                double b = widths[index] + 2 * widths[index - 1];
                slopes[index] = (a + b) / (a / previous + b / following);
            }
        }
        var result = new ArrayList<Cubic>();
        for (int index = 0; index < count - 1; index++) {
            double a = values[index], b = values[index + 1], width = widths[index];
            double left = slopes[index], right = slopes[index + 1];
            result.add(new Cubic(2 * a - 2 * b + width * (left + right), -3 * a + 3 * b - width * (2 * left + right),
                width * left, a));
        }
        return result;
    }

    private static List<Double> roots(double a, double b, double c, double d) {
        double scale = Math.max(Math.max(Math.abs(a), Math.abs(b)), Math.max(Math.abs(c), Math.abs(d)));
        if (scale == 0) {
            return List.of();
        }
        a /= scale;
        b /= scale;
        c /= scale;
        d /= scale;
        double quadratic = 3 * a, linear = 2 * b;
        var turning = new ArrayList<Double>();
        if (quadratic == 0) {
            if (linear != 0) {
                turning.add(-c / linear);
            }
        } else {
            double discriminant = linear * linear - 4 * quadratic * c;
            if (discriminant >= 0) {
                double q = -.5 * (linear + Math.copySign(Math.sqrt(discriminant), linear));
                if (q == 0) {
                    turning.add(0.0);
                } else {
                    turning.add(q / quadratic);
                    turning.add(c / q);
                }
            }
        }
        var boundaries = new ArrayList<Double>();
        boundaries.add(0.0);
        turning.stream().filter(value -> value > 0 && value < 1).distinct().sorted().forEach(boundaries::add);
        boundaries.add(1.0);
        var polynomial = new Cubic(a, b, c, d);
        var result = new ArrayList<Double>();
        for (double boundary : boundaries) {
            if (Math.abs(polynomial.value(boundary)) < 1e-12) {
                result.add(boundary);
            }
        }
        for (int index = 0; index < boundaries.size() - 1; index++) {
            double low = boundaries.get(index), high = boundaries.get(index + 1);
            double first = polynomial.value(low), last = polynomial.value(high);
            if (first * last >= 0) {
                continue;
            }
            for (int iteration = 0; iteration < 48; iteration++) {
                double middle = (low + high) * .5;
                double value = polynomial.value(middle);
                if (first * value <= 0) {
                    high = middle;
                } else {
                    low = middle;
                    first = value;
                }
            }
            result.add((low + high) * .5);
        }
        return result;
    }
}
