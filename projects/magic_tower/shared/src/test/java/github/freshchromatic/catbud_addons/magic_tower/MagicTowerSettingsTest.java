package github.freshchromatic.catbud_addons.magic_tower;

import com.google.gson.*;
import github.freshchromatic.catbud_addons.catbud_core.*;
import java.lang.reflect.Field;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MagicTowerSettingsTest {
    private SettingsValues config;
    private Object previous;
    private Field field;
    @BeforeEach void setup() throws Exception {
        config = new SettingsValues(MagicTowerSettings.schema(), new JsonObject());
        field = MagicTowerSettings.class.getDeclaredField("values"); field.setAccessible(true);
        previous = field.get(null); field.set(null, config);
    }
    @AfterEach void cleanup() throws Exception { field.set(null, previous); }
    private void toggle(String id, boolean value) {
        config.set(MagicTowerSettings.schema().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(), new JsonPrimitive(value));
    }
    @Test void renderingAndRouteSwitchesAreIndependent() {
        toggle("radar.enabled", false); toggle("esp.enabled", false);
        assertTrue(MagicTowerSettings.routes());
        assertTrue(MagicTowerSettings.autoPortal(WorldTargets.PortalType.NETHER));
        toggle("routes.auto.nether", false);
        assertFalse(MagicTowerSettings.autoPortal(WorldTargets.PortalType.NETHER));
        assertTrue(MagicTowerSettings.autoPortal(WorldTargets.PortalType.END));
        toggle("routes.enabled", false);
        assertFalse(MagicTowerSettings.autoPortal(WorldTargets.PortalType.END));
        assertTrue(config.bool("routes.auto.end")); // Dependency never erases preference.
    }
    @Test void floorExplorationUsesSavedDefaultAndOverrideDoesNotMutateIt() {
        toggle("routes.exploreDefault", false);
        TargetLines.onConfigurationChanged(true);
        assertFalse(TargetLines.manualExplorationEnabled());
        toggle("routes.exploreDefault", true);
        TargetLines.onConfigurationChanged(true);
        assertTrue(TargetLines.manualExplorationEnabled());
        assertTrue(TargetLines.setManualExploration(null, new TowerSession(), false));
        assertFalse(TargetLines.manualExplorationEnabled());
        assertTrue(config.bool("routes.exploreDefault"));
    }
    @Test void masterSwitchPreventsForcedSessionFromReportingActive() throws Exception {
        TowerSession session = new TowerSession();
        Field mode = TowerSession.class.getDeclaredField("manualMode"); mode.setAccessible(true);
        Object forced = java.util.Arrays.stream(mode.getType().getEnumConstants()).filter(e -> e.toString().equals("FORCED")).findFirst().orElseThrow();
        mode.set(session, forced); assertTrue(session.isActive());
        toggle("enabled", false); assertFalse(session.isActive());
    }
    @Test void schemaHasNoPerformanceOrDebugControls() {
        assertEquals(java.util.Set.of("general", "radar", "esp", "routes", "hints"),
            MagicTowerSettings.schema().stream().map(Setting::category).collect(java.util.stream.Collectors.toSet()));
        assertTrue(MagicTowerSettings.schema().stream().noneMatch(s -> s.category().equals("performance") || s.id().contains("debug")));
    }
}
