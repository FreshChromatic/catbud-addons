package github.freshchromatic.catbud_addons.island_assistive;

import java.util.ArrayList;
import java.util.List;

import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Profile;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Rotation;

final class FeasibleRhythmCurve implements RhythmMotion.Guide {

    private record Cubic(double a, double b, double c, double d) {

        double value(double fraction) {
            return ((a * fraction + b) * fraction + c) * fraction + d;
        }

        double speedBound() {
            double bound = Math.max(Math.abs(c), Math.abs(3 * a + 2 * b + c));
            if (Math.abs(a) > 1e-15) {
                double vertex = -b / (3 * a);
                if (vertex > 0 && vertex < 1) {
                    bound = Math.max(bound, Math.abs((3 * a * vertex + 2 * b) * vertex + c));
                }
            }
            return bound;
        }

        double accelerationBound() {
            return Math.max(Math.abs(2 * b), Math.abs(6 * a + 2 * b));
        }
    }

    private record Interval(double start, double end, Cubic yaw, Cubic pitch) {}

    private final double         delay;
    private final double         duration;
    private final List<Interval> intervals;

    private FeasibleRhythmCurve(double delay, double duration, List<Interval> intervals) {
        this.delay = delay;
        this.duration = duration;
        this.intervals = List.copyOf(intervals);
    }

    static FeasibleRhythmCurve create(RhythmMotion.Controller plan, Profile profile) {
        int count = profile.knots().size();
        double[] yaw = new double[count];
        double[] pitch = new double[count];
        double[] widths = new double[count - 1];
        double previousYaw = 0;
        for (int index = 0; index < count; index++) {
            double fraction = profile.knots().get(index).fraction();
            var rotation = plan.target(profile.delaySeconds() + fraction * plan.duration(), previousYaw);
            yaw[index] = rotation.yaw();
            pitch[index] = rotation.pitch();
            previousYaw = rotation.yaw();
            if (index > 0) {
                widths[index - 1] = (fraction - profile.knots().get(index - 1).fraction()) * plan.duration();
            }
        }
        for (int iteration = 0; iteration < 64; iteration++) {
            double[] times = new double[count];
            for (int index = 0; index < widths.length; index++) {
                times[index + 1] = times[index] + widths[index];
            }
            var yawCubics = cubics(times, yaw);
            var pitchCubics = cubics(times, pitch);
            double[] factors = new double[widths.length];
            double maximum = 1;
            for (int index = 0; index < widths.length; index++) {
                double speed = Math.hypot(yawCubics[index].speedBound(), pitchCubics[index].speedBound())
                    / widths[index];
                double acceleration = Math.hypot(yawCubics[index].accelerationBound(), pitchCubics[index]
                    .accelerationBound()) / (widths[index] * widths[index]);
                factors[index] = Math.max(1, Math.max(speed / 180, Math.sqrt(acceleration / 1440)));
                maximum = Math.max(maximum, factors[index]);
            }
            if (maximum <= 1 + 1e-10) {
                double duration = times[count - 1];
                var intervals = new ArrayList<Interval>();
                for (int index = 0; index < widths.length; index++) {
                    intervals.add(new Interval(times[index] / duration, times[index + 1] / duration, yawCubics[index],
                        pitchCubics[index]));
                }
                return new FeasibleRhythmCurve(profile.delaySeconds(), duration, intervals);
            }
            for (int index = 0; index < widths.length; index++) {
                widths[index] *= factors[index];
            }
        }
        throw new IllegalStateException("Local curve timing did not reach certified limits");
    }

    @Override
    public Rotation direction(double seconds, double previousYaw) {
        double fraction = Math.clamp((seconds - delay) / duration, 0, 1);
        var selected = intervals.getLast();
        for (var interval : intervals) {
            if (fraction <= interval.end) {
                selected = interval;
                break;
            }
        }
        double amount = (fraction - selected.start) / (selected.end - selected.start);
        return new Rotation(selected.yaw.value(amount), selected.pitch.value(amount));
    }

    double duration() {
        return duration;
    }

    private static Cubic[] cubics(double[] times, double[] values) {
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
        Cubic[] result = new Cubic[count - 1];
        for (int index = 0; index < count - 1; index++) {
            double a = values[index];
            double b = values[index + 1];
            double left = slopes[index];
            double right = slopes[index + 1];
            double width = widths[index];
            result[index] = new Cubic(2 * a - 2 * b + width * (left + right), -3 * a + 3 * b - width * (2 * left
                + right), width * left, a);
        }
        return result;
    }
}
