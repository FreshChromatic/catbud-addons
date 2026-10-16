package github.freshchromatic.catbud_addons.magic_tower;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** A white band enters, crosses and leaves the route without wrapping onto its start. */
final class RouteSweep {

    record Span(Vec3 from, Vec3 to, boolean highlighted) {}

    private RouteSweep() {
    }

    static List<Span> spans(List<Vec3> points, double phase) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) {
            length += points.get(i - 1).distanceTo(points.get(i));
        }
        if (length < 1e-6) {
            return List.of();
        }
        double band = length * 0.30;
        // Leave a short all-base-color pause at each end of the cycle.
        double head = (phase * 1.5 - 0.1) * length;
        double tail = head - band;
        List<Span> result = new ArrayList<>();
        double walked = 0;
        for (int i = 1; i < points.size(); i++) {
            Vec3 from = points.get(i - 1), to = points.get(i);
            double segment = from.distanceTo(to);
            if (segment < 1e-6) {
                continue;
            }
            double end = walked + segment;
            double cursor = walked;
            for (double cut : new double[] { tail, head, end }) {
                double stop = Math.min(end, cut);
                if (stop <= cursor) {
                    continue;
                }
                result.add(new Span(from.lerp(to, (cursor - walked) / segment), from.lerp(to, (stop - walked)
                    / segment), cursor >= tail && stop <= head));
                cursor = stop;
            }
            walked = end;
        }
        return List.copyOf(result);
    }
}
