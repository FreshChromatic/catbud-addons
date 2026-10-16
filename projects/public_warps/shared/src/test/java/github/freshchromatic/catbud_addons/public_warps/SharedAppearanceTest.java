package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.TagParser;

import org.junit.jupiter.api.Test;

import java.util.Map;

class SharedAppearanceTest {

    @Test
    void keepsEquipmentVisualsWithoutNamesLoreContainersOrOwnerData() throws Exception {
        String equipment = "{head:{id:\"minecraft:leather_helmet\",count:1,Owner:\"private-owner\",components:{"
            + "\"minecraft:dyed_color\":123,\"minecraft:item_name\":\"private-name\","
            + "\"minecraft:lore\":[\"private-lore\"],\"minecraft:container\":[{private:1}],"
            + "\"minecraft:trim\":{material:\"minecraft:gold\",pattern:\"minecraft:coast\","
            + "secret:\"private-secret\"}}}}";
        var clean = SharedAppearance.sanitize(Map.of("equipment", equipment, "Color", "14b", "text",
            "\"private-text\""));
        assertEquals("14b", clean.get("Color"));
        assertFalse(clean.containsKey("text"));
        assertFalse(clean.get("equipment").contains("private"));
        var tag = TagParser.parseCompoundFully(clean.get("equipment"));
        var head = tag.getCompoundOrEmpty("head");
        assertEquals("minecraft:leather_helmet", head.getStringOr("id", ""));
        var components = head.getCompoundOrEmpty("components");
        assertEquals(123, components.getIntOr("minecraft:dyed_color", -1));
        assertEquals("minecraft:gold", components.getCompoundOrEmpty("minecraft:trim").getStringOr("material", ""));
        assertTrue(equipment.contains("private"));
    }

    @Test
    void preservesEnchantmentGlintWithoutSharingTheEnchantmentPayload() throws Exception {
        var clean = SharedAppearance.sanitize(Map.of("Item", "{id:\"minecraft:diamond_sword\",count:1,components:{"
            + "\"minecraft:enchantments\":{\"minecraft:unbreaking\":3,secret:\"private\"}}}"));
        var item = TagParser.parseCompoundFully(clean.get("Item"));
        var components = item.getCompoundOrEmpty("components");
        assertTrue(components.getBooleanOr("minecraft:enchantment_glint_override", false));
        assertFalse(clean.get("Item").contains("private"));
        assertFalse(components.contains("minecraft:enchantments"));
    }

    @Test
    void rejectsMalformedOrOutOfRangeVisualsWithoutChangingTheInput() {
        var source = Map.of("Color", "{broken", "Size", "999999999", "variant", "\"private note\"");
        assertTrue(SharedAppearance.sanitize(source).isEmpty());
        assertEquals("{broken", source.get("Color"));
    }
}
