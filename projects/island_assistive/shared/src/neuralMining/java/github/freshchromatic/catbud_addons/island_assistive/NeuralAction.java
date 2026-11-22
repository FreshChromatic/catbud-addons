package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Rotation;
import github.freshchromatic.catbud_addons.island_assistive.NeuralObservation.AngularVelocity;
import java.util.random.RandomGenerator;

final class NeuralAction {

    record Step(Rotation rotation, AngularVelocity requested, AngularVelocity applied, boolean limited,
        boolean pitchLimited) {}

    private static final double STEP_SECONDS = .02;
    private static final double ACTION_SCALE = 180;
    private static final double MAX_CHANGE   = 1440 * STEP_SECONDS;

    static AngularVelocity select(NeuralPolicy.Distribution distribution, RandomGenerator random) {
        if (distribution.moveLogit() < 0) {
            return new AngularVelocity(0, 0);
        }
        var logits = distribution.mixtureLogits();
        int selected = 0;
        for (int index = 1; index < logits.length; index++) {
            if (logits[index] > logits[selected]) {
                selected = index;
            }
        }
        if (random != null) {
            double maximum = logits[selected];
            double total = 0;
            for (float logit : logits) {
                total += Math.exp(logit - maximum);
            }
            double draw = random.nextDouble() * total;
            selected = logits.length - 1;
            for (int index = 0; index < logits.length; index++) {
                draw -= Math.exp(logits[index] - maximum);
                if (draw < 0) {
                    selected = index;
                    break;
                }
            }
        }
        double yaw = distribution.means()[selected][0];
        double pitch = distribution.means()[selected][1];
        if (random != null) {
            yaw += random.nextGaussian() * distribution.scales()[selected][0];
            pitch += random.nextGaussian() * distribution.scales()[selected][1];
        }
        return new AngularVelocity(yaw * ACTION_SCALE, pitch * ACTION_SCALE);
    }

    static Step advance(Rotation rotation, AngularVelocity previous, AngularVelocity requested) {
        double yaw = requested.yaw();
        double pitch = requested.pitch();
        double speed = Math.hypot(yaw, pitch);
        if (speed > ACTION_SCALE) {
            yaw *= ACTION_SCALE / speed;
            pitch *= ACTION_SCALE / speed;
        }
        double changeYaw = yaw - previous.yaw();
        double changePitch = pitch - previous.pitch();
        double change = Math.hypot(changeYaw, changePitch);
        if (change > MAX_CHANGE) {
            yaw = previous.yaw() + changeYaw * MAX_CHANGE / change;
            pitch = previous.pitch() + changePitch * MAX_CHANGE / change;
        }
        boolean limited = Math.hypot(yaw - requested.yaw(), pitch - requested.pitch()) > 1e-6;
        double nextPitch = rotation.pitch() + pitch * STEP_SECONDS;
        double boundedPitch = Math.clamp(nextPitch, -90, 90);
        var next = new Rotation(rotation.yaw() + yaw * STEP_SECONDS, boundedPitch);
        var applied = new AngularVelocity(yaw, (boundedPitch - rotation.pitch()) / STEP_SECONDS);
        return new Step(next, requested, applied, limited, nextPitch != boundedPitch);
    }
}
