package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Rotation;
import github.freshchromatic.catbud_addons.island_assistive.NeuralObservation.AngularVelocity;

public final class NeuralPolicyParity {

    private record Expected(float moveLogit, float[] mixtureLogits, float[][] means, float[][] scales, float[] hidden,
        double[] requested, double[] applied, double[] rotation, boolean limited, boolean pitchLimited) {}

    private record Case(String id, NeuralObservation.State state, boolean reset, Expected expected) {}

    public static void main(String[] arguments) throws Exception {
        var gson = new Gson();
        var cases = gson.fromJson(Files.readString(Path.of(arguments[2])), Case[].class);
        double maximum = 0;
        var times = new ArrayList<Double>();
        verifyActionBoundaries();
        try (var policy = new NeuralPolicy(Path.of(arguments[0]), Path.of(arguments[1]))) {
            for (var test : cases) {
                if (test.reset()) {
                    policy.reset();
                }
                long started = System.nanoTime();
                var actual = policy.infer(NeuralObservation.encode(test.state(), policy.definition()
                    .observationVersion()));
                times.add((System.nanoTime() - started) / 1e6);
                maximum = Math.max(maximum, error(actual.moveLogit(), test.expected().moveLogit()));
                for (int index = 0; index < actual.mixtureLogits().length; index++) {
                    maximum = Math.max(maximum, error(actual.mixtureLogits()[index], test.expected()
                        .mixtureLogits()[index]));
                    for (int axis = 0; axis < 2; axis++) {
                        maximum = Math.max(maximum, error(actual.means()[index][axis], test.expected()
                            .means()[index][axis]));
                        maximum = Math.max(maximum, error(actual.scales()[index][axis], test.expected()
                            .scales()[index][axis]));
                    }
                }
                var hidden = policy.hiddenState();
                for (int index = 0; index < hidden.length; index++) {
                    maximum = Math.max(maximum, error(hidden[index], test.expected().hidden()[index]));
                }
                var requested = NeuralAction.select(actual, null);
                var step = NeuralAction.advance(test.state().rotation(), test.state().previousVelocity(), requested);
                var expected = test.expected();
                maximum = Math.max(maximum, error(requested.yaw(), expected.requested()[0]));
                maximum = Math.max(maximum, error(requested.pitch(), expected.requested()[1]));
                maximum = Math.max(maximum, error(step.applied().yaw(), expected.applied()[0]));
                maximum = Math.max(maximum, error(step.applied().pitch(), expected.applied()[1]));
                maximum = Math.max(maximum, error(step.rotation().yaw(), expected.rotation()[0]));
                maximum = Math.max(maximum, error(step.rotation().pitch(), expected.rotation()[1]));
                if (step.limited() != expected.limited() || step.pitchLimited() != expected.pitchLimited()) {
                    throw new IllegalStateException("Neural action limit flags differ: " + test.id());
                }
            }
        }
        times.sort(Double::compare);
        System.out.println(gson.toJson(Map.of("status", "PASS", "cases", cases.length, "maximum_absolute_error",
            maximum, "encode_and_inference_ms_p50", times.get(times.size() / 2), "encode_and_inference_ms_p99", times
                .get((int) ((times.size() - 1) * .99)))));
    }

    private static void verifyActionBoundaries() {
        var rotation = new Rotation(20, 89.99);
        var zero = new AngularVelocity(0, 0);
        var stopped = NeuralAction.advance(rotation, zero, zero);
        if (!stopped.rotation().equals(rotation) || stopped.limited() || stopped.pitchLimited()) {
            throw new IllegalStateException("Zero neural action created movement");
        }
        var bounded = NeuralAction.advance(rotation, zero, new AngularVelocity(0, 900));
        error(bounded.rotation().pitch(), 90);
        error(bounded.applied().pitch(), .5);
        if (!bounded.limited() || !bounded.pitchLimited()) {
            throw new IllegalStateException("Neural pitch limit was not reported");
        }
        var distribution = new NeuralPolicy.Distribution(1, new float[] { -1000, 1000 }, new float[][] { { -1, -1 }, {
            .25f, .5f } }, new float[][] { { .1f, .1f }, { .1f, .1f } });
        var first = NeuralAction.select(distribution, new Random(17));
        var repeated = NeuralAction.select(distribution, new Random(17));
        if (!first.equals(repeated) || first.equals(NeuralAction.select(distribution, null))) {
            throw new IllegalStateException("Seeded learned-distribution sampling failed");
        }
        var waiting = new NeuralPolicy.Distribution(-1, distribution.mixtureLogits(), distribution.means(), distribution
            .scales());
        if (!NeuralAction.select(waiting, new Random(17)).equals(zero)) {
            throw new IllegalStateException("Waiting gate produced a neural action");
        }
    }

    private static double error(double actual, double expected) {
        double error = Math.abs(actual - expected);
        if (!Double.isFinite(actual) || error > 1e-4) {
            throw new IllegalStateException("Neural Java/Python output differs: " + actual + " versus " + expected);
        }
        return error;
    }
}
