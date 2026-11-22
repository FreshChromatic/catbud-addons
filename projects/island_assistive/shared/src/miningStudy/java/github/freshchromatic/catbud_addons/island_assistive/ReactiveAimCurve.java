package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Context;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Guide;
import github.freshchromatic.catbud_addons.island_assistive.RhythmMotion.Profile;

record ReactiveAimCurve(Guide guide, String strategy, String recoveryError) {

    static ReactiveAimCurve create(Context context, Profile profile) {
        var plan = new RhythmMotion.Controller(context, profile, true);
        try {
            return new ReactiveAimCurve(RetimedRhythmCurve.create(plan, profile), "local_source_floor", null);
        } catch (IllegalStateException failure) {
            return new ReactiveAimCurve(UniformAimCurve.create(plan, profile), "uniform_certified", failure
                .getMessage());
        }
    }
}
