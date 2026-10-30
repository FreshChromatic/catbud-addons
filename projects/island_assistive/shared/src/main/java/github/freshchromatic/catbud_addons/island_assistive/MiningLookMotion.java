package github.freshchromatic.catbud_addons.island_assistive;

import net.minecraft.util.Mth;

public final class MiningLookMotion {

    private double yawVelocity;
    private double pitchVelocity;

    public record Rotation(float yaw, float pitch) {}

    public Rotation advance(float yaw, float pitch, float targetYaw, float targetPitch, double speed, double seconds) {
        int steps = Math.max(1, (int) Math.ceil(seconds * 240));
        double interval = seconds / steps;
        for (int step = 0; step < steps; step++) {
            double yawError = Mth.wrapDegrees(targetYaw - yaw);
            double pitchError = targetPitch - pitch;
            double yawAcceleration = yawError * 144 - yawVelocity * 24;
            double pitchAcceleration = pitchError * 100 - pitchVelocity * 20;
            double acceleration = Math.hypot(yawAcceleration, pitchAcceleration);
            double accelerationScale = acceleration > speed * 8 ? speed * 8 / acceleration : 1;
            yawVelocity += yawAcceleration * accelerationScale * interval;
            pitchVelocity += pitchAcceleration * accelerationScale * interval;
            double velocity = Math.hypot(yawVelocity, pitchVelocity);
            if (velocity > speed) {
                yawVelocity *= speed / velocity;
                pitchVelocity *= speed / velocity;
            }
            double yawStep = yawVelocity * interval;
            double pitchStep = pitchVelocity * interval;
            boolean yawSettled = Math.abs(yawError) < 0.001 && Math.abs(yawVelocity) < 0.02;
            boolean pitchSettled = Math.abs(pitchError) < 0.001 && Math.abs(pitchVelocity) < 0.02;
            if (yawSettled || yawStep * yawError >= 0 && Math.abs(yawStep) >= Math.abs(yawError)) {
                yawStep = yawError;
                yawVelocity = 0;
            }
            if (pitchSettled || pitchStep * pitchError >= 0 && Math.abs(pitchStep) >= Math.abs(pitchError)) {
                pitchStep = pitchError;
                pitchVelocity = 0;
            }
            yaw += (float) yawStep;
            pitch = Math.clamp(pitch + (float) pitchStep, -90, 90);
        }
        return new Rotation(yaw, pitch);
    }

    public void reset() {
        yawVelocity = 0;
        pitchVelocity = 0;
    }
}
