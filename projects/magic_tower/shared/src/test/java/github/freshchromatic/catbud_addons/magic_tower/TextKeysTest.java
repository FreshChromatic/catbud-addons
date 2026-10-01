package github.freshchromatic.catbud_addons.magic_tower;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class TextKeysTest {
    @Test
    void findsKeysInsideTranslationArguments() {
        Component bar = Component.translatable("plugins.venue_manager.room_queuing_distance",
            Component.translatable("plugins.magic_tower.magic_tower"), 5);
        assertTrue(TextKeys.contains(bar, "plugins.venue_manager.room_queuing_distance"));
        assertTrue(TextKeys.contains(bar, "plugins.magic_tower.magic_tower"));
    }

    @Test
    void doesNotConfuseOtherBossbarsWithTowerQueue() {
        Component otherRoom = Component.translatable("plugins.venue_manager.room_queuing_distance",
            Component.literal("Other room"), 5);
        assertFalse(TextKeys.contains(otherRoom, "plugins.magic_tower.magic_tower"));
    }
}
