package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Context;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Guide;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Profile;

record AimCurve(Guide guide, String strategy, String recoveryError) {

    static AimCurve create(Context context, Profile profile) {
        var plan = new AimTrajectory.Controller(context, profile, true);
        try {
            return new AimCurve(RetimedAimCurve.create(plan, profile), "local_source_floor", null);
        } catch (IllegalStateException failure) {
            return new AimCurve(UniformRetimedAimCurve.create(plan, profile), "uniform_certified", failure
                .getMessage());
        }
    }
}
