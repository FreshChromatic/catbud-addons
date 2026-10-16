package github.freshchromatic.catbud_addons.public_warps;

import java.util.EnumSet;

public final class PreviewCameraControl {

    public enum Movement {
        FORWARD, BACKWARD, LEFT, RIGHT, UP, DOWN
    }

    private final EnumSet<Movement> held     = EnumSet.noneOf(Movement.class);
    private BlockSnapshot           scene;
    private double                  x, y, z, yaw, pitch;
    private double                  targetX, targetY, targetZ, targetYaw, targetPitch;
    private double                  orbitX, orbitY, orbitZ, initialDistance, radius;
    private double                  lastTime = Double.NaN;
    private boolean                 inside, focused, resetting;

    public void configure(BlockSnapshot snapshot, int width, int height) {
        if (scene == snapshot) {
            return;
        }
        scene = snapshot;
        radius = Math.sqrt(snapshot.width * snapshot.width + snapshot.height * snapshot.height + snapshot.depth
            * snapshot.depth) / 2;
        initialDistance = Math.max(1, radius) * 2.2 * Math.max(1, (double) height / Math.max(1, width));
        resetView(true);
    }

    public void reset() {
        scene = null;
        held.clear();
        focused = false;
        inside = false;
        lastTime = Double.NaN;
    }

    public void resetView(boolean immediate) {
        if (scene == null) {
            return;
        }
        held.clear();
        inside = false;
        orbitX = eyeX();
        orbitY = eyeY();
        orbitZ = eyeZ();
        targetYaw = Math.PI / 4;
        targetPitch = Math.toRadians(35);
        targetX = orbitX + Math.sin(targetYaw) * Math.cos(targetPitch) * initialDistance;
        targetY = orbitY + Math.sin(targetPitch) * initialDistance;
        targetZ = orbitZ + Math.cos(targetYaw) * Math.cos(targetPitch) * initialDistance;
        resetting = !immediate;
        if (immediate) {
            snap();
        }
    }

    public void focus(boolean value) {
        focused = value;
        if (!value) {
            held.clear();
            targetX = x;
            targetY = y;
            targetZ = z;
        }
    }

    public boolean focused() {
        return focused;
    }

    public void hold(Movement movement, boolean pressed) {
        if (!focused) {
            return;
        }
        if (pressed) {
            held.add(movement);
        } else {
            held.remove(movement);
        }
    }

    public void drag(double dx, double dy) {
        if (scene == null) {
            return;
        }
        resetting = false;
        double distance = distance(targetX - orbitX, targetY - orbitY, targetZ - orbitZ);
        targetYaw -= Math.toRadians(.25) * dx;
        targetPitch = Math.clamp(targetPitch + Math.toRadians(.25) * dy, -Math.toRadians(85), Math.toRadians(85));
        if (!inside) {
            targetX = orbitX + Math.sin(targetYaw) * Math.cos(targetPitch) * distance;
            targetY = orbitY + Math.sin(targetPitch) * distance;
            targetZ = orbitZ + Math.cos(targetYaw) * Math.cos(targetPitch) * distance;
        }
        bound();
    }

    public void scroll(double steps) {
        if (scene == null) {
            return;
        }
        resetting = false;
        double travel = Math.clamp(distance(targetX - orbitX, targetY - orbitY, targetZ - orbitZ) * .06, .5, 8) * Math
            .clamp(steps, -8, 8);
        move(travel, 0, 0, false, 1);
    }

    public void advance(double now, boolean windowActive, boolean reducedMotion) {
        if (scene == null) {
            return;
        }
        if (!windowActive) {
            focus(false);
        }
        double dt = Double.isNaN(lastTime) ? 0 : Math.clamp(now - lastTime, 0, .05);
        lastTime = now;
        if (focused && !held.isEmpty()) {
            resetting = false;
            double speed = Math.clamp(distance(targetX - orbitX, targetY - orbitY, targetZ - orbitZ) * .12, 4, 20);
            move(axis(Movement.FORWARD, Movement.BACKWARD), axis(Movement.RIGHT, Movement.LEFT), axis(Movement.UP,
                Movement.DOWN), true, speed * dt);
        }
        double alpha = reducedMotion ? 1 : 1 - Math.exp(-dt / (resetting ? .06 : .035));
        x += (targetX - x) * alpha;
        y += (targetY - y) * alpha;
        z += (targetZ - z) * alpha;
        yaw += Math.atan2(Math.sin(targetYaw - yaw), Math.cos(targetYaw - yaw)) * alpha;
        pitch += (targetPitch - pitch) * alpha;
    }

    private double axis(Movement positive, Movement negative) {
        return (held.contains(positive) ? 1 : 0) - (held.contains(negative) ? 1 : 0);
    }

    private void move(double forward, double right, double up, boolean shiftOrbit, double amount) {
        double dx = -Math.sin(targetYaw) * Math.cos(targetPitch) * forward + Math.cos(targetYaw) * right;
        double dy = -Math.sin(targetPitch) * forward + up;
        double dz = -Math.cos(targetYaw) * Math.cos(targetPitch) * forward - Math.sin(targetYaw) * right;
        double length = shiftOrbit ? distance(dx, dy, dz) : 1;
        if (length < .00001) {
            return;
        }
        dx = dx / length * amount;
        dy = dy / length * amount;
        dz = dz / length * amount;
        targetX += dx;
        targetY += dy;
        targetZ += dz;
        if (shiftOrbit) {
            orbitX += dx;
            orbitY += dy;
            orbitZ += dz;
        }
        bound();
    }

    private void bound() {
        double limit = Math.max(radius * 4, initialDistance * 1.5);
        targetX = Math.clamp(targetX, eyeX() - limit, eyeX() + limit);
        targetY = Math.clamp(targetY, eyeY() - limit, eyeY() + limit);
        targetZ = Math.clamp(targetZ, eyeZ() - limit, eyeZ() + limit);
        double edge = inside ? -1 : 1;
        boolean next = targetX >= edge && targetX <= scene.width - edge && targetY >= edge && targetY <= scene.height
            - edge && targetZ >= edge && targetZ <= scene.depth - edge;
        if (inside && !next) {
            double distance = Math.max(2, distance(targetX - eyeX(), targetY - eyeY(), targetZ - eyeZ()));
            orbitX = targetX - Math.sin(targetYaw) * Math.cos(targetPitch) * distance;
            orbitY = targetY - Math.sin(targetPitch) * distance;
            orbitZ = targetZ - Math.cos(targetYaw) * Math.cos(targetPitch) * distance;
        }
        inside = next;
    }

    private void snap() {
        x = targetX;
        y = targetY;
        z = targetZ;
        yaw = targetYaw;
        pitch = targetPitch;
    }

    private static double distance(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private double eyeX() {
        return scene.version == 1 ? scene.width / 2d : scene.eyeX;
    }

    private double eyeY() {
        return scene.version == 1 ? Math.min(scene.height - .1, 2.62) : scene.eyeY;
    }

    private double eyeZ() {
        return scene.version == 1 ? scene.depth / 2d : scene.eyeZ;
    }

    public double yaw() {
        return yaw - Math.toRadians(scene.viewYaw) - Math.PI;
    }

    public double pitch() {
        return pitch - Math.toRadians(scene.viewPitch);
    }

    public double zoom() {
        return 1;
    }

    public double offsetX() {
        return x - eyeX();
    }

    public double offsetY() {
        return y - eyeY();
    }

    public double offsetZ() {
        return z - eyeZ();
    }

    public boolean inside() {
        return inside;
    }

    public boolean outsideRange() {
        return x < 0 || x > scene.width || y < 0 || y > scene.height || z < 0 || z > scene.depth;
    }
}
