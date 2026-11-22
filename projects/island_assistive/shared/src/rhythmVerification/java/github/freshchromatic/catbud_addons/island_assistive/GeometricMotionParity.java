package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class GeometricMotionParity {

    private record Case(RhythmMotion.Context context, List<RhythmMotion.Rotation> expected) {}

    private record Data(List<Case> cases) {}

    public static void main(String[] arguments) throws Exception {
        var gson = new Gson();
        var data = gson.fromJson(Files.readString(Path.of(arguments[0])), Data.class);
        double maximum = 0;
        int samples = 0;
        for (var test : data.cases) {
            var motion = GeometricStudyMotion.create(test.context);
            for (int index = 1; index < test.expected.size(); index++) {
                var actual = motion.advance(1.0 / 120);
                var expected = test.expected.get(index);
                maximum = Math.max(maximum, Math.max(Math.abs(RhythmMotion.wrap(actual.yaw() - expected.yaw())), Math
                    .abs(actual.pitch() - expected.pitch())));
                samples++;
            }
            if (motion.peakSpeed() > 180.000001 || motion.peakAcceleration() > 1440.000001) {
                throw new IllegalStateException("Geometric fallback exceeded limits");
            }
        }
        if (maximum > .0001) {
            throw new IllegalStateException("Geometric fallback differs: " + maximum);
        }
        System.out.println(gson.toJson(Map.of("status", "PASS", "cases", data.cases.size(), "samples", samples,
            "maximum_angle_error_degrees", maximum)));
    }
}
