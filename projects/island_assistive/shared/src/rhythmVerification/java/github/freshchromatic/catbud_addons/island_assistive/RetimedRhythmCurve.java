package github.freshchromatic.catbud_addons.island_assistive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class RetimedRhythmCurve implements RhythmMotion.Guide {

    private record Cubic(double a, double b, double c, double d) {

        double value(double x) {
            return ((a * x + b) * x + c) * x + d;
        }
    }

    private record Segment(Cubic yaw, Cubic pitch) {

        double[] bounds(double width) {
            double ay = 3 * yaw.a, by = 2 * yaw.b, cy = yaw.c;
            double ap = 3 * pitch.a, bp = 2 * pitch.b, cp = pitch.c;
            var candidates = new ArrayList<Double>(List.of(0.0, 1.0));
            candidates.addAll(roots(4 * (ay * ay + ap * ap), 6 * (ay * by + ap * bp), 2 * (by * by + bp * bp + 2 * ay
                * cy + 2 * ap * cp), 2 * (by * cy + bp * cp)));
            double speed = 0;
            for (double x : candidates) {
                speed = Math.max(speed, Math.hypot((ay * x + by) * x + cy, (ap * x + bp) * x + cp));
            }
            double acceleration = Math.max(Math.hypot(2 * yaw.b, 2 * pitch.b), Math.hypot(6 * yaw.a + 2 * yaw.b, 6
                * pitch.a + 2 * pitch.b));
            return new double[] { speed / width * (1 + 1e-12), acceleration / (width * width) * (1 + 1e-12) };
        }
    }

    private record Interval(double start, double end, Segment segment) {}

    private final double         delay;
    private final double         duration;
    private final List<Interval> intervals;

    private RetimedRhythmCurve(double delay, double duration, List<Interval> intervals) {
        this.delay = delay;
        this.duration = duration;
        this.intervals = List.copyOf(intervals);
    }

    static RetimedRhythmCurve create(RhythmMotion.Controller plan, RhythmMotion.Profile profile) {
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
        double[] sourceWidths = widths.clone();
        boolean converged = false;
        for (int iteration = 0; iteration < 64; iteration++) {
            double[] factors = new double[widths.length];
            double maximum = 1;
            for (int index = 0; index < widths.length; index++) {
                double[] bounds = segment(index, widths, yaw, pitch).bounds(widths[index]);
                factors[index] = Math.max(1, Math.max(bounds[0] / 180, Math.sqrt(bounds[1] / 1440)));
                maximum = Math.max(maximum, factors[index]);
            }
            if (maximum <= 1 + 1e-10) {
                converged = true;
                break;
            }
            for (int index = 0; index < widths.length; index++) {
                widths[index] *= factors[index];
            }
        }
        if (!converged) {
            throw new IllegalStateException("Exact-bound timing did not converge");
        }
        for (int round = 0; round < 2; round++) {
            for (boolean reverse : new boolean[] { false, true }) {
                for (int step = 0; step < widths.length; step++) {
                    int index = reverse ? widths.length - 1 - step : step;
                    double low = sourceWidths[index];
                    double high = widths[index];
                    if (high <= low * (1 + 1e-9)) {
                        continue;
                    }
                    for (int attempt = 0; attempt < 9; attempt++) {
                        double candidate = attempt == 0 ? low : (low + high) * .5;
                        widths[index] = candidate;
                        boolean safe = true;
                        for (int neighbor = Math.max(0, index - 1); neighbor < Math.min(widths.length, index
                            + 2); neighbor++) {
                            double[] bounds = segment(neighbor, widths, yaw, pitch).bounds(widths[neighbor]);
                            if (bounds[0] > 180 * (1 + 1e-10) || bounds[1] > 1440 * (1 + 1e-10)) {
                                safe = false;
                                break;
                            }
                        }
                        if (safe) {
                            high = candidate;
                            if (attempt == 0) {
                                break;
                            }
                        } else {
                            low = candidate;
                        }
                    }
                    widths[index] = high;
                }
            }
        }
        double duration = 0;
        for (double width : widths) {
            duration += width;
        }
        double elapsed = 0;
        var intervals = new ArrayList<Interval>();
        for (int index = 0; index < widths.length; index++) {
            var segment = segment(index, widths, yaw, pitch);
            double[] bounds = segment.bounds(widths[index]);
            if (bounds[0] > 180 * (1 + 1e-9) || bounds[1] > 1440 * (1 + 1e-9)) {
                throw new IllegalStateException("Compressed timing failed full-curve certification");
            }
            intervals.add(new Interval(elapsed / duration, (elapsed + widths[index]) / duration, segment));
            elapsed += widths[index];
        }
        return new RetimedRhythmCurve(profile.delaySeconds(), duration, intervals);
    }

    @Override
    public RhythmMotion.Rotation direction(double seconds, double previousYaw) {
        double fraction = Math.clamp((seconds - delay) / duration, 0, 1);
        var selected = intervals.getLast();
        for (var interval : intervals) {
            if (fraction <= interval.end) {
                selected = interval;
                break;
            }
        }
        double amount = (fraction - selected.start) / (selected.end - selected.start);
        return new RhythmMotion.Rotation(selected.segment.yaw.value(amount), selected.segment.pitch.value(amount));
    }

    private static Segment segment(int index, double[] widths, double[] yaw, double[] pitch) {
        int first = Math.max(0, index - 1);
        int last = Math.min(yaw.length - 1, index + 2);
        double[] times = new double[last - first + 1];
        for (int position = first; position < last; position++) {
            times[position - first + 1] = times[position - first] + widths[position];
        }
        int position = index - first;
        return new Segment(cubic(times, Arrays.copyOfRange(yaw, first, last + 1), position), cubic(times, Arrays
            .copyOfRange(pitch, first, last + 1), position));
    }

    private static Cubic cubic(double[] times, double[] values, int selected) {
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
        double a = values[selected], b = values[selected + 1];
        double left = slopes[selected], right = slopes[selected + 1], width = widths[selected];
        return new Cubic(2 * a - 2 * b + width * (left + right), -3 * a + 3 * b - width * (2 * left + right), width
            * left, a);
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
