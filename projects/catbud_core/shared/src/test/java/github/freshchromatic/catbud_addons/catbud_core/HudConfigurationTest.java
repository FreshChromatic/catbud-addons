package github.freshchromatic.catbud_addons.catbud_core;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Map;

class HudConfigurationTest {

    @TempDir Path temporary;

    @Test
    void undoingAnAlreadySavedDuplicateDoesNotResurrectItAfterReload() throws Exception {
        var registry = new HudRegistry();
        registry.register(element("a"));
        var session = new HudEditorSession(registry, Map.of());
        var store = new HudConfigStore(temporary.resolve("saved-copy.json"));
        session.select("test:a", false);
        session.duplicateSelected();
        store.apply(session.snapshot());
        session.markSaved();
        session.undo();
        store.apply(session.snapshot());
        var reloaded = new HudConfigStore(temporary.resolve("saved-copy.json"));
        assertEquals(1, registry.elements(reloaded.current()).size());
        assertEquals(session.snapshot(), store.current());
    }

    @Test
    void attachedHudFollowsResizedParentAndIndependentScaleKeepsPixelSize() {
        var config = new HudElementConfig(true, false, new HudPlacement(null, 10, 12, 1), null);
        var child = config.withPlacement(new HudPlacement(null, 0, 4, 1).withRelative("parent",
            HudPlacement.Anchor.BOTTOM_LEFT));
        var configs = Map.of("parent", config, "child", child);
        var sizes = Map.of("parent", new HudLayoutResolver.Size(90, 30), "child", new HudLayoutResolver.Size(60, 20));
        var bounds = new HudLayoutResolver(configs, sizes, 640, 400, 2).resolve();
        assertEquals(10, bounds.get("child").x());
        assertEquals(46, bounds.get("child").y());
        var taller = new HudLayoutResolver(configs, Map.of("parent", new HudLayoutResolver.Size(90, 50), "child", sizes
            .get("child")), 640, 400, 2).resolve();
        assertEquals(66, taller.get("child").y());
        var placement = config.placement().withGuiScale(2);
        var atTwo = HudLayoutEngine.place(placement, 90, 30, 640, 400, null, 2);
        var atFour = HudLayoutEngine.place(placement, 90, 30, 320, 200, null, 4);
        assertEquals(atTwo.width() * 2, atFour.width() * 4);
        assertEquals(atTwo.x() * 2, atFour.x() * 4);
        var moved = HudLayoutEngine.at(child.placement(), 22, 51, bounds.get("child"), 640, 400, bounds.get("parent"),
            2);
        assertEquals(12, moved.x());
        assertEquals(9, moved.y());
    }

    @Test
    void cyclicAndMissingAttachmentsFallBackToScreenWithoutRecursion() {
        var a = new HudElementConfig(true, false, new HudPlacement(null, 10, 10, 1).withRelative("b", null), null);
        var b = a.withPlacement(a.placement().withRelative("a", null));
        var configs = Map.of("a", a, "b", b);
        assertTrue(HudLayoutResolver.wouldCycle(configs, "a", "b"));
        var sizes = Map.of("a", new HudLayoutResolver.Size(20, 20), "b", new HudLayoutResolver.Size(20, 20));
        assertEquals(2, new HudLayoutResolver(configs, sizes, 320, 180, 2).resolve().size());
        var missing = new HudLayoutResolver(Map.of("a", a), Map.of("a", sizes.get("a")), 320, 180, 2).resolve();
        assertEquals(10, missing.get("a").x());
    }

    @Test
    void mergedBackgroundsShareAppearanceAndCopiesFormTheirOwnGroup() {
        var registry = new HudRegistry();
        registry.register(element("a"));
        registry.register(element("b"));
        var session = new HudEditorSession(registry, Map.of());
        session.selectAll(java.util.List.of("test:a", "test:b"));
        session.mergeSelectedBackgrounds();
        String originalGroup = session.config("test:a").style().backgroundGroup();
        assertFalse(originalGroup.isEmpty());
        assertEquals(originalGroup, session.config("test:b").style().backgroundGroup());
        session.select("test:a", false);
        session.updateSelected(c -> c.withStyle(c.style().edit(builder -> builder.radius = 14)));
        assertEquals(14, session.config("test:b").style().radius());
        session.selectMergedGroup("test:a");
        assertEquals(2, session.selection().size());
        session.update("test:b", c -> c.withPlacement(c.placement().withRelative("test:a", null)));
        session.duplicateSelected();
        var copies = session.selection();
        assertEquals(2, copies.size());
        assertTrue(copies.stream().noneMatch(id -> session.config(id).style().backgroundGroup().equals(originalGroup)));
        assertTrue(copies.stream().anyMatch(id -> copies.contains(session.config(id).placement().relativeTo())));
        session.unmergeSelectedBackgrounds();
        assertTrue(copies.stream().allMatch(id -> session.config(id).style().backgroundGroup().isEmpty()));
        assertEquals(originalGroup, session.config("test:a").style().backgroundGroup());
    }

    @Test
    void duplicatedElementsPersistWithoutRegisteringOrLosingTheirSource() throws Exception {
        var registry = new HudRegistry();
        registry.register(element("a"));
        var session = new HudEditorSession(registry, Map.of());
        session.select("test:a", false);
        session.duplicateSelected();
        String copy = session.primary();
        assertEquals("test:a", session.config(copy).sourceId());
        assertEquals(1, registry.elements().size());
        assertEquals(2, registry.elements(session.snapshot()).size());
        session.updateSelected(c -> c.withStyle(c.style().edit(b -> b.radius = 17)));
        assertEquals(4, session.config("test:a").style().radius());
        var store = new HudConfigStore(temporary.resolve("copies.json"));
        store.apply(session.snapshot());
        var loaded = new HudConfigStore(temporary.resolve("copies.json"));
        assertEquals(2, registry.elements(loaded.current()).size());
        assertEquals(17, loaded.current().get(copy).style().radius());
        session.deleteSelectedCopies();
        assertEquals(1, registry.elements(session.snapshot()).size());
        session.undo();
        assertEquals(2, registry.elements(session.snapshot()).size());
        session.select(copy, false);
        session.duplicateSelected();
        assertEquals("test:a", session.config(session.primary()).sourceId());
        session.deleteSelectedCopies();
        store.apply(session.snapshot());
        assertEquals(2, registry.elements(new HudConfigStore(temporary.resolve("copies.json")).current()).size());
    }

    @Test
    void changeSummaryTracksNetChangesAndSavedBaselineAcrossUndo() {
        var registry = new HudRegistry();
        registry.register(element("a"));
        var session = new HudEditorSession(registry, Map.of());
        session.select("test:a", false);
        session.updateSelected(c -> c.withStyle(c.style().edit(b -> b.radius = 12)));
        var changes = HudChanges.between(session.entry(), session.snapshot());
        assertEquals(1, changes.size());
        assertEquals("style.radius", changes.getFirst().property());
        assertEquals("4.0", changes.getFirst().before());
        assertEquals("12.0", changes.getFirst().after());
        session.undo();
        assertTrue(HudChanges.between(session.entry(), session.snapshot()).isEmpty());
        session.redo();
        session.markSaved();
        assertTrue(HudChanges.between(session.entry(), session.snapshot()).isEmpty());
        session.undo();
        assertEquals("4.0", HudChanges.between(session.entry(), session.snapshot()).getFirst().after());
    }

    @Test
    void newAppearanceOptionsPersistAndDoNotChangeUnrelatedFields() throws Exception {
        var registry = new HudRegistry();
        var element = element("appearance");
        registry.register(element);
        var original = element.defaults();
        var customized = original.withStyle(original.style().edit(b -> {
            b.fixedWidth = 160;
            b.fixedHeight = 48;
            b.formatting = new HudTextFormat(true, HudTextFormat.LetterCase.UPPER, "tower ", "!", true);
            b.chroma = new HudChroma(1, 2, 3, 4);
        }));
        var store = new HudConfigStore(temporary.resolve("appearance.json"));
        store.apply(Map.of(element.id(), customized));
        var reloaded = new HudConfigStore(temporary.resolve("appearance.json")).config(element);
        assertEquals(customized, reloaded);
        assertEquals(original.placement(), reloaded.placement());
        assertEquals("[TOWER FLOOR 12!]", reloaded.style().formatting().apply("Floor 12"));
        assertEquals(original.style().backgroundColor(), reloaded.style().backgroundColor());
    }

    @Test
    void rainbowPreservesOpacityAndStaticColorsAndUsesIndependentSpeeds() {
        int transparent = 0x50112233;
        assertEquals(transparent, HudChroma.color(transparent, 0, 1000));
        assertEquals(0x50FF0000, HudChroma.color(transparent, 1, 0));
        assertEquals(0x50FFFF00, HudChroma.color(transparent, 1, 1000));
        var style = HudStyle.defaults().edit(b -> b.chroma = new HudChroma(1, 0, 2, 0));
        var animated = style.chroma().resolve(style, 1000);
        assertEquals(style.backgroundColor(), animated.backgroundColor());
        assertEquals(style.boxShadow(), animated.boxShadow());
        assertNotEquals(animated.textColor() & 0xFFFFFF, animated.textShadow().color() & 0xFFFFFF);
        assertEquals(style.textShadow().color() >>> 24, animated.textShadow().color() >>> 24);
        assertEquals(0xFFFFFFFF, style.textColor());
    }

    @Test
    void copyingStylePreservesDestinationPositionAndCanUndoTheWholePaste() {
        var registry = new HudRegistry();
        registry.register(element("a"));
        registry.register(element("b"));
        var session = new HudEditorSession(registry, Map.of());
        session.select("test:a", false);
        session.updateSelected(c -> c.withStyle(c.style().edit(b -> b.radius = 13)));
        session.copyStyle();
        session.select("test:b", false);
        session.updateSelected(c -> c.withPlacement(new HudPlacement(HudPlacement.Anchor.BOTTOM_RIGHT, -20, -30, 2)));
        var destination = session.config("test:b");
        session.pasteStyle();
        assertEquals(destination.placement(), session.config("test:b").placement());
        assertEquals(13, session.config("test:b").style().radius());
        session.undo();
        assertEquals(destination, session.config("test:b"));
    }

    private static HudElement element(String id) {
        return new HudElement() {

            public String id() {
                return "test:" + id;
            }

            public String addonId() {
                return "test";
            }

            public Component name() {
                return Component.literal(id);
            }

            public HudElementConfig defaults() {
                return new HudElementConfig(true, false, null, null);
            }

            public boolean visible() {
                return true;
            }

            public HudContent content(boolean preview) {
                return new HudContent(null, java.util.List.of());
            }
        };
    }

    @Test
    void multiSelectionChangesOnlyEditedPropertyAndGestureUndoesAsOneAction() {
        var registry = new HudRegistry();
        registry.register(element("a"));
        registry.register(element("b"));
        var session = new HudEditorSession(registry, Map.of());
        session.selectAll(java.util.List.of("test:a", "test:b"));
        session.update("test:b", c -> c.withPlacement(new HudPlacement(HudPlacement.Anchor.BOTTOM_RIGHT, -8, -12, 2)));
        var before = session.snapshot();
        session.beginGesture();
        for (int move = 1; move < 20; move++) {
            int x = move;
            session.update("test:a", c -> c.withPlacement(new HudPlacement(null, x, x, 1)));
        }
        session.endGesture();
        session.undo();
        assertEquals(before, session.snapshot());
        session.redo();
        assertEquals(19, session.config("test:a").placement().x());
        var placement = session.config("test:b").placement();
        session.updateSelected(c -> c.withStyle(c.style().edit(b -> b.radius = 9)));
        assertEquals(9, session.config("test:a").style().radius());
        assertEquals(9, session.config("test:b").style().radius());
        assertEquals(placement, session.config("test:b").placement());
        assertEquals(8, session.entry().get("test:a").placement().x());
    }

    @Test
    void placementKeepsAnchoredContentAndOversizedElementsVisible() {
        var placement = new HudPlacement(HudPlacement.Anchor.BOTTOM_RIGHT, -8, -12, 2);
        var bounds = HudLayoutEngine.place(placement, 40, 20, 320, 180);
        assertEquals(232, bounds.x());
        assertEquals(128, bounds.y());
        var larger = HudLayoutEngine.place(placement, 80, 20, 320, 180);
        assertEquals(bounds.x() + bounds.width(), larger.x() + larger.width());
        var oversized = HudLayoutEngine.place(placement, 500, 300, 320, 180);
        assertTrue(oversized.x() >= 0 && oversized.y() >= 0);
        assertTrue(oversized.x() + oversized.width() <= 320.001);
        assertTrue(oversized.y() + oversized.height() <= 180.001);
    }

    @Test
    void storePreservesAbsentModulesAndUnknownFieldsAndFillsMissingDefaults() throws Exception {
        Path path = temporary.resolve("hud.json");
        Files.writeString(path, """
            {"configVersion":1,"futureFlag":true,"elements":{
              "test:a":{"enabled":false,"style":{"radius":7,"futureStyle":9}},
              "absent:element":{"enabled":true,"futureOption":"retained"}}}
            """);
        var store = new HudConfigStore(path);
        assertFalse(store.warning());
        var config = store.current().get("test:a");
        assertFalse(config.enabled());
        assertEquals(7, config.style().radius());
        assertEquals(1, config.style().textScale());
        assertEquals(0xFFFFFFFF, config.style().textColor());
        store.apply(store.current());
        String saved = Files.readString(path);
        assertTrue(saved.contains("futureFlag") && saved.contains("futureStyle") && saved.contains("retained"));
        assertEquals(store.current(), new HudConfigStore(path).current());
    }

    @Test
    void failedSaveDoesNotPublishDraftAndCorruptConfigIsBackedUp() throws Exception {
        Path parent = temporary.resolve("not-a-directory");
        var store = new HudConfigStore(parent.resolve("hud.json"));
        Files.writeString(parent, "blocked");
        assertThrows(java.io.IOException.class, () -> store.apply(Map.of("test:a", element("a").defaults())));
        assertTrue(store.current().isEmpty());
        Path broken = temporary.resolve("broken.json");
        Files.writeString(broken, "{broken");
        assertTrue(new HudConfigStore(broken).warning());
        try (var files = Files.list(temporary)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("broken.json.invalid-")));
        }
    }
}
