package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

record RhythmGameData(int formatVersion, List<RhythmMotion.Profile> profiles, List<Scene> scenes, List<Clip> clips,
    List<Parity> parity, List<LearnedAimTarget.Preference> targets, List<String> methods, boolean benchOnly,
    boolean geometricFallback) {

    record Scene(String id, String query, String session, String face, RhythmMotion.Vector eye,
        RhythmMotion.Rotation start, RhythmMotion.Vector reference, int targetX, int targetY, int targetZ,
        List<LearnedAimTarget.Box> boxes, double reach) {}

    record Clip(String id, String caseId, String slot, List<RhythmMotion.Rotation> frames) {}

    record Parity(String caseId, String method, List<RhythmMotion.Rotation> expected) {}

    static RhythmGameData load(Path path) throws IOException {
        var result = new Gson().fromJson(Files.readString(path), RhythmGameData.class);
        if (result.formatVersion < 1 || result.formatVersion > 3 || result.scenes.size() != 6 || result.profiles
            .isEmpty() || result.clips.size() != (result.formatVersion == 2 ? 36 : 24)) {
            throw new IOException("Invalid controlled-review input");
        }
        int frames = result.formatVersion == 1 ? 180 : 300;
        if (result.clips.stream().anyMatch(clip -> clip.frames.size() != frames) || result.formatVersion >= 2
            && (result.targets == null || result.methods == null || result.methods.size() != (result.formatVersion == 2
                ? 5
                : 3) || result.scenes.stream().anyMatch(scene -> scene.boxes == null))) {
            throw new IOException("Incomplete controlled-review model or frames");
        }
        if (result.formatVersion == 3 && !result.methods.equals(List.of("legacy_pd", "learned_feasible",
            "timed_feasible"))) {
            throw new IOException("Invalid timing comparison methods");
        }
        return result;
    }

    List<String> controlMethods() {
        return formatVersion == 1 ? List.of("legacy_pd", "timing_only_bounded", "full_rhythm_bounded") : methods;
    }

    LearnedAimTarget.Preference preference(String source) {
        return targets.stream().filter(value -> value.source().equals(source)).findFirst().orElse(null);
    }

    Scene scene(String id) {
        return scenes.stream().filter(scene -> scene.id.equals(id)).findFirst().orElseThrow();
    }
}
