package github.freshchromatic.catbud_addons.update;

import java.util.Arrays;
import java.util.Optional;

public enum AddonModule {

    CORE("core", "catbud_core"), MAGIC_TOWER("magic_tower", "catbud_addons"), PUBLIC_WARPS("public_warps",
        "catbud_public_warps"), MONSTER_WAVE("monster_wave", "catbud_monster_wave");

    private final String id;
    private final String modId;

    AddonModule(String id, String modId) {
        this.id = id;
        this.modId = modId;
    }

    public String id() {
        return id;
    }

    public String modId() {
        return modId;
    }

    public static Optional<AddonModule> forMod(String modId) {
        return Arrays.stream(values()).filter(module -> module.modId.equals(modId)).findFirst();
    }

    public static AddonModule require(String id) {
        return Arrays.stream(values()).filter(module -> module.id.equals(id)).findFirst().orElseThrow(
            () -> new IllegalArgumentException("Unknown addon module: " + id));
    }
}
