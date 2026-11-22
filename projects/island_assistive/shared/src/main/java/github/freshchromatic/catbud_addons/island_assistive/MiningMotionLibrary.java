package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

final class MiningMotionLibrary {

    private record Data(List<AimTrajectory.Profile> profiles, List<AimTargetTransfer.Preference> targets) {

        private Data {
            profiles = List.copyOf(profiles);
            targets = List.copyOf(targets);
        }
    }

    private static final MiningMotionLibrary INSTANCE = new MiningMotionLibrary();

    private final List<AimTrajectory.Profile>               profiles;
    private final Map<String, AimTargetTransfer.Preference> targets;

    private MiningMotionLibrary() {
        try (var input = Objects.requireNonNull(MiningMotionLibrary.class.getResourceAsStream(
            "/assets/catbud_island_assistive/mining-motion.json"));
            var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var data = Objects.requireNonNull(new Gson().fromJson(reader, Data.class));
            profiles = data.profiles();
            targets = data.targets().stream().collect(Collectors.toUnmodifiableMap(AimTargetTransfer.Preference::source,
                Function.identity()));
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static MiningMotionLibrary instance() {
        return INSTANCE;
    }

    List<AimTrajectory.Profile> profiles() {
        return profiles;
    }

    Map<String, AimTargetTransfer.Preference> targets() {
        return targets;
    }
}
