package github.freshchromatic.catbud_addons.public_warps;

public final class PreviewMotion {

    private double value, start, target, started, angle, last;
    private double hoveredAt = Double.NaN;

    public double updateHover(double now, boolean hovered, boolean reduced) {
        if (hovered && Double.isNaN(hoveredAt)) {
            hoveredAt = now;
        }
        double elapsed = hovered ? Math.max(0, now - hoveredAt) : 0;
        boolean inside = hovered && !reduced && elapsed % 5 >= 2.5;
        double blend = update(now, false, inside, reduced);
        if (hovered && !reduced) {
            angle = elapsed * Math.PI * 2 / 2.5;
        } else {
            hoveredAt = Double.NaN;
            if (blend == 0 || reduced) {
                angle = 0;
            }
        }
        return blend;
    }

    public double update(double now, boolean hovered, boolean inside, boolean reduced) {
        double next = inside ? 1 : 0;
        // Sample the old trajectory before retargeting, including events between frames.
        value = start + (target - start) * ease(Math.max(0, Math.min(1, (now - started) / .25)));
        if (next != target) {
            start = value;
            target = next;
            started = now;
        }
        double t = Math.max(0, Math.min(1, (now - started) / .25));
        value = reduced ? next : start + (target - start) * ease(t);
        if (last != 0 && hovered && !reduced) {
            angle += Math.max(0, Math.min(.1, now - last)) * Math.PI / 4;
        }
        last = now;
        return value;
    }

    public double angle() {
        return angle;
    }

    // Solve the strong ease-out cubic-bezier(0.23,1,0.32,1), including its x time mapping.
    private static double ease(double x) {
        if (x <= 0) {
            return 0;
        }
        if (x >= 1) {
            return 1;
        }
        double lo = 0, hi = 1;
        for (int i = 0; i < 18; i++) {
            double u = (lo + hi) / 2, v = 3 * (1 - u) * (1 - u) * u * .23 + 3 * (1 - u) * u * u * .32 + u * u * u;
            if (v < x) {
                lo = u;
            } else {
                hi = u;
            }
        }
        double u = (lo + hi) / 2;
        return 1 - Math.pow(1 - u, 3);
    }
}
