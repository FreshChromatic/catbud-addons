package github.freshchromatic.catbud_addons.magic_tower;

import static org.junit.jupiter.api.Assertions.*;

import github.freshchromatic.catbud_addons.catbud_core.*;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class TowerHudDefaultsTest {

    @Test
    void freshAndResetElementsUseTheCenteredLayoutWithoutBackgrounds() {
        List<HudElement> elements = List.of(new CoinHudElement(null), new FloorHudElement(null),
            new EvacuationHudElement(null));
        var registry = new HudRegistry();
        elements.forEach(registry::register);
        var session = new HudEditorSession(registry, Map.of());
        Map<String, HudElementConfig> defaults = session.snapshot();
        Map<String, HudLayoutResolver.Size> sizes = new LinkedHashMap<>();
        for (HudElement element : elements) {
            var config = session.config(element.id());
            assertEquals(element.defaults(), config);
            assertTrue(config.enabled());
            assertFalse(config.locked());
            assertFalse(config.style().background());
            assertEquals(0.8, config.placement().scale());
            sizes.put(element.id(), new HudLayoutResolver.Size(config.style().fixedWidth(), config.style()
                .fixedHeight()));
        }
        var bounds = new HudLayoutResolver(defaults, sizes, 640, 330, 3).resolve();
        var coins = bounds.get("catbud_addons:tower_coins");
        var floor = bounds.get("catbud_addons:tower_floor");
        var evacuation = bounds.get("catbud_addons:tower_evacuation");
        assertEquals(coins.x() + coins.width(), floor.x(), 0.001);
        assertEquals(floor.x() + floor.width(), evacuation.x(), 0.001);
        assertEquals(320, (coins.x() + evacuation.x() + evacuation.width()) / 2, 0.001);
        assertTrue(bounds.values().stream().allMatch(box -> box.y() == 22 && box.height() == 24));

        session.selectAll(defaults.keySet());
        session.updateSelected(config -> config.withEnabled(false).withStyle(config.style().edit(style -> {
            style.radius = 20;
            style.background = true;
        })));
        session.resetSelected();
        assertEquals(defaults, session.snapshot());

        var personal = defaults.get("catbud_addons:tower_coins").withPlacement(new HudPlacement(null, 25, 45, 1));
        var existing = new HudEditorSession(registry, Map.of("catbud_addons:tower_coins", personal));
        assertEquals(personal, existing.config("catbud_addons:tower_coins"));
    }
}
