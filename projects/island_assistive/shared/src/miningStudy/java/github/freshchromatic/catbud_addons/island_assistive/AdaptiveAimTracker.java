package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Rotation;

final class AdaptiveAimTracker {

    record Rates(double yaw, double pitch) {}

    private Rotation rotation;
    private double   yawVelocity;
    private double   pitchVelocity;
    private double   peakSpeed;
    private double   peakAcceleration;

    AdaptiveAimTracker(Rotation start) {
        rotation = start;
    }

    Rotation rotation() {
        return rotation;
    }

    Rates velocity() {
        return new Rates(yawVelocity, pitchVelocity);
    }

    double peakSpeed() {
        return peakSpeed;
    }

    double peakAcceleration() {
        return peakAcceleration;
    }

    Rotation advance(Rotation target, Rotation previousTarget, double seconds) {
        var feed = limited(RhythmMotion.wrap(target.yaw() - previousTarget.yaw()) / seconds, (target.pitch()
            - previousTarget.pitch()) / seconds, 180);
        var desired = limited(feed.yaw() + RhythmMotion.wrap(target.yaw() - rotation.yaw()) * 12, feed.pitch() + (target
            .pitch() - rotation.pitch()) * 10, 180);
        var acceleration = limited((desired.yaw() - yawVelocity) * 24, (desired.pitch() - pitchVelocity) * 20, 1440);
        double oldYaw = yawVelocity;
        double oldPitch = pitchVelocity;
        double upper = pitchLimit(90 - rotation.pitch(), seconds);
        double lower = -pitchLimit(90 + rotation.pitch(), seconds);
        double nextPitch = Math.clamp(oldPitch + acceleration.pitch() * seconds, lower, upper);
        double pitchAcceleration = (nextPitch - oldPitch) / seconds;
        if (Math.abs(pitchAcceleration) > 1440.000001) {
            throw new IllegalStateException("No feasible pitch braking step");
        }
        double yawLimit = Math.sqrt(Math.max(0, 1440 * 1440 - pitchAcceleration * pitchAcceleration));
        double yawAcceleration = Math.clamp(acceleration.yaw(), -yawLimit, yawLimit);
        var velocity = limited(oldYaw + yawAcceleration * seconds, nextPitch, 180);
        yawVelocity = velocity.yaw();
        pitchVelocity = velocity.pitch();
        peakSpeed = Math.max(peakSpeed, Math.hypot(yawVelocity, pitchVelocity));
        peakAcceleration = Math.max(peakAcceleration, Math.hypot(yawVelocity - oldYaw, pitchVelocity - oldPitch)
            / seconds);
        rotation = new Rotation(rotation.yaw() + yawVelocity * seconds, rotation.pitch() + pitchVelocity * seconds);
        if (!Double.isFinite(rotation.yaw()) || rotation.pitch() < -90 || rotation.pitch() > 90) {
            throw new IllegalStateException("Generated view outside legal bounds");
        }
        return rotation;
    }

    private static Rates limited(double yaw, double pitch, double limit) {
        double length = Math.hypot(yaw, pitch);
        double scale = length > limit ? limit / length : 1;
        return new Rates(yaw * scale, pitch * scale);
    }

    private static double pitchLimit(double margin, double seconds) {
        return 2 * margin / (Math.sqrt(seconds * seconds + 2 * margin / 1440) + seconds);
    }
}
