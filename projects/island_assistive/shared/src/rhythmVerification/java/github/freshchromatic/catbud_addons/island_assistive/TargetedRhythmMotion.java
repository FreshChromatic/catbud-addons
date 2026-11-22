package github.freshchromatic.catbud_addons.island_assistive;

import java.util.List;

final class TargetedRhythmMotion {

    static RhythmMotion.Controller create(RhythmMotion.Context context, RhythmMotion.Profile profile, LearnedAimTarget.Preference preference, List<LearnedAimTarget.Box> boxes, double reach, String method) {
        if (method.equals("learned_target") || method.equals("learned_feasible")) {
            var target = preference == null ? null : LearnedAimTarget.select(context, boxes, preference, reach);
            if (target == null) {
                return null;
            }
            context = new RhythmMotion.Context(context.session(), context.family(), context.eye(), context.start(),
                target);
        }
        var plan = new RhythmMotion.Controller(context, profile, true);
        if (!method.equals("feasible_curve") && !method.equals("learned_feasible")) {
            return plan;
        }
        var guide = FeasibleRhythmCurve.create(plan, profile);
        return new RhythmMotion.Controller(context, profile, true, guide);
    }
}
