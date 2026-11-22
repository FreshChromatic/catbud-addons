package github.freshchromatic.catbud_addons.island_assistive;

import java.util.List;

final class GeometricStudyMotion {

    static StudyMotion create(RhythmMotion.Context context) {
        var profile = new RhythmMotion.Profile("geometric_fallback", "", "", context.family(), context.start().pitch(),
            0, 0, 0, .001, List.of(new RhythmMotion.Knot(0, 0, 0), new RhythmMotion.Knot(1, 1, 0)));
        var target = RhythmMotion.Rotation.from(context.reference().subtract(context.eye()), context.start().yaw());
        return new StudyMotion.Recorded(new RhythmMotion.Controller(context, profile, true,
            (seconds, previousYaw) -> target));
    }
}
