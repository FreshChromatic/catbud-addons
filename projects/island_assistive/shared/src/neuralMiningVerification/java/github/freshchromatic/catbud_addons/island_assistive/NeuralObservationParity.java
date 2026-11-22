package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class NeuralObservationParity {

    private record Case(String id, NeuralObservation.State state, int version, float[] expected) {}

    public static void main(String[] arguments) throws Exception {
        var gson = new Gson();
        var cases = gson.fromJson(Files.readString(Path.of(arguments[0])), Case[].class);
        double maximum = 0;
        int features = 0;
        for (var test : cases) {
            var actual = NeuralObservation.encode(test.state(), test.version());
            if (actual.length != test.expected().length) {
                throw new IllegalStateException("Feature count differs: " + test.id());
            }
            for (int index = 0; index < actual.length; index++) {
                double error = Math.abs(actual[index] - test.expected()[index]);
                maximum = Math.max(maximum, error);
                if (!Float.isFinite(actual[index]) || error > 1e-5) {
                    throw new IllegalStateException("Feature differs: " + test.id() + " index=" + index + " error="
                        + error);
                }
                features++;
            }
        }
        System.out.println(gson.toJson(Map.of("status", "PASS", "cases", cases.length, "features", features,
            "maximum_absolute_error", maximum)));
    }
}
