package github.freshchromatic.catbud_addons.island_assistive;

import java.util.List;

interface StudyMotion {

    RhythmMotion.Rotation advance(double seconds);

    double peakSpeed();

    double peakAcceleration();

    static StudyMotion create(RhythmMotion.Context context, RhythmMotion.Profile profile, LearnedAimTarget.Preference preference, List<LearnedAimTarget.Box> boxes, double reach, String method) {
        if (method.equals("timed_feasible")) {
            return AcquiredRhythmMotion.create(context, profile, preference, boxes, reach);
        }
        if (!List.of("full_rhythm_bounded", "learned_target", "feasible_curve", "learned_feasible").contains(method)) {
            throw new IllegalArgumentException("Unknown study motion: " + method);
        }
        var controller = TargetedRhythmMotion.create(context, profile, preference, boxes, reach, method);
        return controller == null ? null : new Recorded(controller);
    }

    record Recorded(RhythmMotion.Controller controller) implements StudyMotion {

        @Override
        public RhythmMotion.Rotation advance(double seconds) {
            return controller.advance(seconds);
        }

        @Override
        public double peakSpeed() {
            return controller.peakSpeed();
        }

        @Override
        public double peakAcceleration() {
            return controller.peakAcceleration();
        }
    }
}
