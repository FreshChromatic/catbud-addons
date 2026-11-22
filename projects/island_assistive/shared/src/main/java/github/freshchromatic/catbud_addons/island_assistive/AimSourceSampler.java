package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.AimTargetTransfer.Box;
import github.freshchromatic.catbud_addons.island_assistive.AimTargetTransfer.Preference;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Context;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Profile;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Vector;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class AimSourceSampler {

    record Option(Profile profile, Preference preference, double score, Vector goal) {}

    record Choice(long index, double uniform, Option option, int poolSize, Double probability) {}

    private final long                    seed;
    private final List<Profile>           profiles;
    private final Map<String, Preference> preferences;
    private final MessageDigest           digest;
    private final ArrayDeque<String>      recent = new ArrayDeque<>();

    private long index;

    AimSourceSampler(long seed, List<Profile> profiles, Map<String, Preference> preferences) {
        this.seed = seed;
        this.profiles = List.copyOf(profiles);
        this.preferences = Map.copyOf(preferences);
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    List<Option> compatible(Context context, List<Box> boxes, double reach) {
        var options = new ArrayList<Option>();
        double angle = context.start().vector().angle(context.reference().subtract(context.eye()));
        double distance = context.reference().subtract(context.eye()).length();
        for (var profile : profiles) {
            var preference = preferences.get(profile.id());
            if (preference == null || AimTrajectory.select(context, List.of(profile)) == null) {
                continue;
            }
            var goal = AimTargetTransfer.select(context, boxes, preference, reach);
            if (goal == null) {
                continue;
            }
            double score = Math.abs(Math.log(angle / profile.referenceAngle())) + Math.abs(context.start().pitch()
                - profile.initialPitch()) / 30 + Math.abs(distance - profile.referenceDistance()) / 3;
            options.add(new Option(profile, preference, score, goal));
        }
        return options.stream().sorted(Comparator.comparingDouble(Option::score).thenComparing(option -> option
            .profile().id())).limit(8).toList();
    }

    Choice choose(List<Option> options) {
        long selectedIndex = index++;
        byte[] hash = digest.digest((seed + ":" + selectedIndex).getBytes(StandardCharsets.US_ASCII));
        double uniform = (ByteBuffer.wrap(hash).getLong() >>> 11) * 0x1.0p-53;
        if (options.isEmpty()) {
            return new Choice(selectedIndex, uniform, null, 0, null);
        }
        double best = options.stream().mapToDouble(Option::score).min().orElseThrow();
        double[] weights = new double[options.size()];
        double total = 0;
        for (int position = 0; position < options.size(); position++) {
            var option = options.get(position);
            long count = recent.stream().filter(option.profile().id()::equals).count();
            weights[position] = Math.exp(-(option.score() - best) / .25) * Math.pow(.25, count);
            total += weights[position];
        }
        double remaining = uniform * total;
        int selected = options.size() - 1;
        for (int position = 0; position < weights.length; position++) {
            remaining -= weights[position];
            if (remaining < 0) {
                selected = position;
                break;
            }
        }
        var option = options.get(selected);
        if (recent.size() == 2) {
            recent.removeFirst();
        }
        recent.addLast(option.profile().id());
        return new Choice(selectedIndex, uniform, option, options.size(), weights[selected] / total);
    }
}
