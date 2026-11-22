package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class TargetedMotionParity {

    private record Case(RhythmMotion.Context context, List<LearnedAimTarget.Box> boxes, double reach, String source,
        RhythmMotion.Vector learnedGoal, Map<String, List<RhythmMotion.Rotation>> expected) {}

    private record Data(List<RhythmMotion.Profile> profiles, List<LearnedAimTarget.Preference> preferences,
        List<Case> cases) {}

    public static void main(String[] arguments) throws Exception {
        var gson = new Gson();
        var data = gson.fromJson(Files.readString(Path.of(arguments[0])), Data.class);
        double maximum = 0;
        double targetError = 0;
        int samples = 0;
        for (var test : data.cases) {
            var profile = RhythmMotion.select(test.context, data.profiles);
            if (profile == null || !profile.id().equals(test.source)) {
                throw new IllegalStateException("Source selection differs");
            }
            var preference = data.preferences.stream().filter(value -> value.source().equals(profile.id())).findFirst()
                .orElseThrow();
            var target = LearnedAimTarget.select(test.context, test.boxes, preference, test.reach);
            if (target == null) {
                throw new IllegalStateException("Learned target missing");
            }
            targetError = Math.max(targetError, target.subtract(test.learnedGoal).length());
            for (var entry : test.expected.entrySet()) {
                var controller = TargetedRhythmMotion.create(test.context, profile, preference, test.boxes, test.reach,
                    entry.getKey());
                for (int index = 1; index < entry.getValue().size(); index++) {
                    var actual = controller.advance(1.0 / 120);
                    var expected = entry.getValue().get(index);
                    maximum = Math.max(maximum, Math.max(Math.abs(RhythmMotion.wrap(actual.yaw() - expected.yaw())),
                        Math.abs(actual.pitch() - expected.pitch())));
                    samples++;
                }
                if (controller.peakSpeed() > 180.000001 || controller.peakAcceleration() > 1440.000001) {
                    throw new IllegalStateException("Controller limits exceeded");
                }
            }
        }
        if (maximum > .0001 || targetError > 1e-8) {
            throw new IllegalStateException("Numeric parity differs: " + maximum + "/" + targetError);
        }
        System.out.println(gson.toJson(Map.of("cases", data.cases.size(), "samples", samples,
            "maximum_angle_error_degrees", maximum, "maximum_target_error_blocks", targetError, "status", "PASS")));
    }
}
