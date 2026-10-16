package github.freshchromatic.catbud_addons.public_warps;

import java.util.*;

/** Perspective camera shared across Minecraft adapters. Mesh positions stay in snapshot coordinates. */
public final class SceneProjection {

    private static final Map<BlockSnapshot, double[]> BOUNDS = Collections.synchronizedMap(new WeakHashMap<>());

    static double[] bounds(BlockSnapshot s) {
        return BOUNDS.computeIfAbsent(s, scene -> {
            double[] b = { s.width, s.height, s.depth, 0, 0, 0 };
            boolean any = false;
            for (int y = 0; y < s.height; y++) {
                for (int z = 0; z < s.depth; z++) {
                    for (int x = 0; x < s.width; x++) {
                        String state = s.state(x, y, z);
                        if (state.equals("minecraft:air") || state.equals("minecraft:cave_air") || state.equals(
                            "minecraft:void_air")) {
                            continue;
                        }
                        any = true;
                        b[0] = Math.min(b[0], x);
                        b[1] = Math.min(b[1], y);
                        b[2] = Math.min(b[2], z);
                        b[3] = Math.max(b[3], x + 1);
                        b[4] = Math.max(b[4], y + 1);
                        b[5] = Math.max(b[5], z + 1);
                    }
                }
            }
            for (var e : s.entities) {
                any = true;
                b[0] = Math.min(b[0], e.x() - .5);
                b[1] = Math.min(b[1], e.y());
                b[2] = Math.min(b[2], e.z() - .5);
                b[3] = Math.max(b[3], e.x() + .5);
                b[4] = Math.max(b[4], e.y() + 2);
                b[5] = Math.max(b[5], e.z() + .5);
            }
            return any ? b : new double[] { 0, 0, 0, s.width, s.height, s.depth };
        });
    }

    public record Polygon(float[] vertices, double depth) {}

    public record Point(float x, float y, float scale, double depth) {}

    public Point point(double px, double py, double pz) {
        var p = camera(new float[] { (float) px, (float) py, (float) pz, 0, 0 }, 0);
        if (p[2] < .15) {
            return null;
        }
        return new Point((float) (x + w / 2d + p[0] * focal / p[2]), (float) (y + h / 2d - p[1] * focal / p[2]),
            (float) (focal / p[2]), p[2]);
    }

    public double yaw() {
        return Math.atan2(sy, co);
    }

    public double pitch() {
        return Math.atan2(sp, cp);
    }

    public boolean occluded(BlockSnapshot s, double px, double py, double pz, Set<String> opaque) {
        double dx = px - cx, dy = py - cy, dz = pz - cz, length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) (length * 8);
        for (int i = 1; i < steps - 3; i++) {
            double t = (double) i / steps;
            int bx = (int) Math.floor(cx + dx * t), by = (int) Math.floor(cy + dy * t), bz = (int) Math.floor(cz + dz
                * t);
            if (bx < 0 || bx >= s.width || by < 0 || by >= s.height || bz < 0 || bz >= s.depth) {
                continue;
            }
            String state = s.state(bx, by, bz);
            if (opaque.contains(state)) {
                return true;
            }
        }
        return false;
    }

    private final double cx, cy, cz, sy, co, sp, cp, focal;
    private final int    x, y, w, h;

    public SceneProjection(BlockSnapshot s, int x, int y, int w, int h, double angle, double inside) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        double yaw = Math.PI / 4 + angle + inside * (Math.toRadians(s.viewYaw) + Math.PI - Math.PI / 4);
        double pitch = Math.toRadians(35) * (1 - inside) + Math.toRadians(s.viewPitch) * inside;
        sy = Math.sin(yaw);
        co = Math.cos(yaw);
        sp = Math.sin(pitch);
        cp = Math.cos(pitch);
        var b = bounds(s);
        double bw = b[3] - b[0], bh = b[4] - b[1], bd = b[5] - b[2];
        double radius = Math.max(1, Math.sqrt(bw * bw + bh * bh + bd * bd) / 2);
        double distance = radius * 2.2 * Math.max(1, (double) h / Math.max(1, w));
        double eyeX = s.version == 1 ? s.width / 2d : s.eyeX, eyeY = s.version == 1 ? Math.min(s.height - .1, 2.62)
            : s.eyeY, eyeZ = s.version == 1 ? s.depth / 2d : s.eyeZ;
        cx = ((b[0] + b[3]) / 2 + sy * cp * distance) * (1 - inside) + eyeX * inside;
        cy = ((b[1] + b[4]) / 2 + sp * distance) * (1 - inside) + eyeY * inside;
        cz = ((b[2] + b[5]) / 2 + co * cp * distance) * (1 - inside) + eyeZ * inside;
        focal = h / (2 * Math.tan(Math.toRadians(60) / 2));
    }

    private double[] camera(float[] v, int at) {
        double dx = v[at] - cx, dy = v[at + 1] - cy, dz = v[at + 2] - cz;
        return new double[] { co * dx - sy * dz, -sy * sp * dx + cp * dy - co * sp * dz, -sy * cp * dx - sp * dy - co
            * cp * dz, v[at + 3], v[at + 4] };
    }

    public List<Polygon> face(float[] raw) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            points.add(camera(raw, i * 5));
        }
        List<double[]> clipped = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            double[] a = points.get(i), b = points.get((i + 1) % points.size());
            boolean ai = a[2] >= .15, bi = b[2] >= .15;
            if (ai) {
                clipped.add(a);
            }
            if (ai != bi) {
                double t = (.15 - a[2]) / (b[2] - a[2]);
                double[] v = new double[5];
                for (int j = 0; j < 5; j++) {
                    v[j] = a[j] + t * (b[j] - a[j]);
                }
                clipped.add(v);
            }
        }
        if (clipped.size() < 3) {
            return List.of();
        }
        var polygons = new ArrayList<Polygon>();
        for (int i = 1; i < clipped.size() - 1; i++) {
            double[][] triangle = { clipped.get(0), clipped.get(i), clipped.get(i + 1), clipped.get(i + 1) };
            float[] v = new float[16];
            double depth = 0;
            for (int j = 0; j < 4; j++) {
                double[] p = triangle[j];
                v[j * 4] = (float) (x + w / 2d + p[0] * focal / p[2]);
                v[j * 4 + 1] = (float) (y + h / 2d - p[1] * focal / p[2]);
                v[j * 4 + 2] = (float) p[3];
                v[j * 4 + 3] = (float) p[4];
                depth += p[2];
            }
            polygons.add(new Polygon(v, depth / 4));
        }
        return polygons;
    }
}
