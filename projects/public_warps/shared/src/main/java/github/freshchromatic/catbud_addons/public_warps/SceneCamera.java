package github.freshchromatic.catbud_addons.public_warps;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

public final class SceneCamera {

    public final Matrix4f    projection, view;
    public final Quaternionf orientation;
    public final double      x, y, z;
    public final float       fovDegrees;

    public SceneCamera(BlockSnapshot s, int w, int h, double angle, double pitchOffset, double zoom, double inside) {
        this(s, w, h, angle, pitchOffset, zoom, inside, 0, 0, 0);
    }

    public SceneCamera(BlockSnapshot s, int w, int h, double angle, double pitchOffset, double zoom, double inside, double offsetX, double offsetY, double offsetZ) {
        double yaw = Math.PI / 4 + angle + inside * (Math.toRadians(s.viewYaw) + Math.PI - Math.PI / 4);
        double pitch = Math.max(-1.48, Math.min(1.48, Math.toRadians(35) * (1 - inside) + Math.toRadians(s.viewPitch)
            * inside + pitchOffset));
        var b = SceneProjection.bounds(s);
        double bw = b[3] - b[0], bh = b[4] - b[1], bd = b[5] - b[2];
        double distance = Math.max(1, Math.sqrt(bw * bw + bh * bh + bd * bd) / 2) * 2.2 * Math.max(1, (double) h / w)
            / zoom;
        double sy = Math.sin(yaw), co = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        x = ((b[0] + b[3]) / 2 + sy * cp * distance) * (1 - inside) + (s.version == 1 ? s.width / 2d : s.eyeX) * inside
            + offsetX;
        y = ((b[1] + b[4]) / 2 + sp * distance) * (1 - inside) + (s.version == 1 ? Math.min(s.height - .1, 2.62)
            : s.eyeY) * inside + offsetY;
        z = ((b[2] + b[5]) / 2 + co * cp * distance) * (1 - inside) + (s.version == 1 ? s.depth / 2d : s.eyeZ) * inside
            + offsetZ;
        fovDegrees = 60;
        float farPlane = (float) Math.max(256, Math.sqrt(x * x + y * y + z * z) + Math.sqrt(s.width * s.width + s.height
            * s.height + s.depth * s.depth) * 2);
        projection = new Matrix4f().setPerspective((float) Math.toRadians(fovDegrees), (float) w / h, .15f, farPlane);
        view = new Matrix4f().setLookAt((float) x, (float) y, (float) z, (float) (x - sy * cp), (float) (y - sp),
            (float) (z - co * cp), 0, 1, 0);
        orientation = view.getUnnormalizedRotation(new Quaternionf()).conjugate();
    }
}
