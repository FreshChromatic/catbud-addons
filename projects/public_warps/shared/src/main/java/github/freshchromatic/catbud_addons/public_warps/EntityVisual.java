package github.freshchromatic.catbud_addons.public_warps;

import net.minecraft.nbt.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.storage.*;

import java.util.*;

/** Only visual fields; never persist entity UUIDs, owner data, AI, passengers or inventories. */
public final class EntityVisual {

    public static final Set<String> FIELDS = Set.of("variant", "Variant", "Color", "CollarColor", "Age", "Baby",
        "Saddle", "Saddled", "Sheared", "Type", "type", "Size", "size", "VillagerData", "villager_data",
        "carriedBlockState", "carried_block_state", "equipment", "Equipment", "ArmorItems", "HandItems", "Item", "item",
        "Facing", "facing", "ItemRotation", "item_rotation", "Invisible", "invisible", "ShowArms", "NoBasePlate",
        "Small", "Pose", "pose", "BlockState", "block_state", "painting_variant", "billboard", "transformation",
        "brightness", "view_range", "shadow_radius", "shadow_strength", "width", "height", "glow_color_override",
        "text", "line_width", "background", "text_opacity", "alignment");

    private EntityVisual() {
    }

    public static Map<String, String> capture(Entity entity) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.level().registryAccess());
        entity.saveWithoutId(output);
        var tag = output.buildResult();
        var result = new LinkedHashMap<String, String>();
        int bytes = 0;
        for (String key : tag.keySet()) {
            if (FIELDS.contains(key)) {
                String value = tag.get(key).toString();
                bytes += value.length();
                if (value.length() <= 4096 && bytes <= 16384) {
                    result.put(key, value);
                }
            }
        }
        return result;
    }

    public static void apply(Entity entity, Map<String, String> fields) throws Exception {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        // Detached item frames/paintings need a local anchor; absolute saved anchors are never
        // shared.
        if (entity instanceof net.minecraft.world.entity.decoration.BlockAttachedEntity) {
            tag.put("block_pos", new IntArrayTag(new int[] { 0, 0, 0 }));
        }
        for (var entry : fields.entrySet()) {
            if (FIELDS.contains(entry.getKey())) {
                var parsed = TagParser.parseCompoundFully("{" + entry.getKey() + ":" + entry.getValue() + "}");
                tag.put(entry.getKey(), parsed.get(entry.getKey()));
            }
        }
        entity.load(TagValueInput.create(ProblemReporter.DISCARDING, entity.level().registryAccess(), tag));
    }
}
