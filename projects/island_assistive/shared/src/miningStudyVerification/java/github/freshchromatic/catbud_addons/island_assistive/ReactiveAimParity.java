package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import github.freshchromatic.catbud_addons.island_assistive.LearnedAimTarget.Box;
import github.freshchromatic.catbud_addons.island_assistive.LearnedAimTarget.Preference;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Context;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Profile;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Rotation;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Vector;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class ReactiveAimParity {

    private record ExpectedChoice(String source, double uniform, double probability, Vector goal) {}

    private record SelectionCase(Context context, List<Box> boxes, double reach, long seed,
        List<ExpectedChoice> choices) {}

    private record Model(List<Profile> profiles, List<Preference> preferences, List<SelectionCase> selections) {}

    private record Step(Vector eye, Vector reference, List<Box> boxes, long revision, Rotation expected, String state,
        boolean canAttack, double yawVelocity, double pitchVelocity) {}

    private record Case(String id, String family, Rotation start, double reach, int rate, long seed, int plans,
        int recoveries, List<Step> steps) {}

    public static void main(String[] arguments) throws Exception {
        var gson = new Gson();
        var model = gson.fromJson(Files.readString(Path.of(arguments[0])), Model.class);
        var preferences = new HashMap<String, Preference>();
        model.preferences().forEach(preference -> preferences.put(preference.source(), preference));
        int selections = 0;
        double maximumGoalError = 0;
        for (var test : model.selections()) {
            var sampler = new MotionSourceSampler(test.seed(), model.profiles(), preferences);
            var options = sampler.compatible(test.context(), test.boxes(), test.reach());
            for (var expected : test.choices()) {
                var actual = sampler.choose(options);
                if (actual.option() == null || !actual.option().profile().id().equals(expected.source()) || actual
                    .uniform() != expected.uniform() || Math.abs(actual.probability() - expected
                        .probability()) > 1e-9) {
                    throw new IllegalStateException("Source selection differs");
                }
                maximumGoalError = Math.max(maximumGoalError, actual.option().goal().subtract(expected.goal())
                    .length());
                selections++;
            }
        }
        int cases = 0;
        int samples = 0;
        int recoveries = 0;
        double maximumAngleError = 0;
        double maximumVelocityError = 0;
        var planning = new ArrayList<Double>();
        try (var source = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(Path.of(
            arguments[1]))), StandardCharsets.UTF_8))) {
            String line;
            while ((line = source.readLine()) != null) {
                var test = gson.fromJson(line, Case.class);
                var controller = new ReactiveAimMotion(test.start(), test.seed(), model.profiles(), preferences);
                long plans = 0;
                int caseRecoveries = 0;
                for (var step : test.steps()) {
                    var context = new Context("variation-development", test.family(), step.eye(), controller.tracker()
                        .rotation(), step.reference());
                    var actual = controller.advance(context, step.boxes(), test.reach(), step.revision(), 1.0 / test
                        .rate());
                    double angleError = Math.max(Math.abs(RhythmMotion.wrap(actual.yaw() - step.expected().yaw())), Math
                        .abs(actual.pitch() - step.expected().pitch()));
                    double velocityError = Math.max(Math.abs(controller.tracker().velocity().yaw() - step
                        .yawVelocity()), Math.abs(controller.tracker().velocity().pitch() - step.pitchVelocity()));
                    maximumAngleError = Math.max(maximumAngleError, angleError);
                    maximumVelocityError = Math.max(maximumVelocityError, velocityError);
                    if (angleError > .0001 || velocityError > .001 || !controller.state().name().equals(step.state())
                        || controller.canAttack() != step.canAttack()) {
                        throw new IllegalStateException("Reactive output differs: " + test.id() + ";sample=" + samples
                            + ";angle=" + angleError + ";velocity=" + velocityError + ";state=" + controller.state());
                    }
                    if (controller.plans() != plans) {
                        planning.add(controller.lastDecision().planningMs());
                        if (controller.lastDecision().recoveryError() != null) {
                            caseRecoveries++;
                        }
                        plans = controller.plans();
                    }
                    samples++;
                }
                if (controller.plans() != test.plans() || caseRecoveries != test.recoveries()) {
                    throw new IllegalStateException("Planning decisions differ: " + test.id());
                }
                recoveries += caseRecoveries;
                cases++;
            }
        }
        if (maximumGoalError > 1e-8) {
            throw new IllegalStateException("Learned goal differs");
        }
        System.out.println(gson.toJson(Map.of("status", "PASS", "cases", cases, "samples", samples, "source_choices",
            selections, "curve_recoveries", recoveries, "maximum_angle_error_degrees", maximumAngleError,
            "maximum_velocity_error", maximumVelocityError, "maximum_goal_error_blocks", maximumGoalError,
            "planning_ms", planning)));
    }
}
