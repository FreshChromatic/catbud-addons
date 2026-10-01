package github.freshchromatic.catbud_addons.catbud_core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SettingsStoreTest {
    @TempDir Path directory;
    private final List<Setting> schema = List.of(
        Setting.toggle("enabled", "general", true, v -> true),
        Setting.number("size", "display", 1, 0.5, 2, 0.05, v -> v.bool("enabled")),
        Setting.color("color", "display", "#088DA5", v -> true),
        Setting.choice("message", "general", "chat", List.of("chat", "off"), v -> true));
    @Test void validatedRoundTripAndUnknownFieldsSurvive() throws Exception {
        Path path = directory.resolve("nested/options.json");
        AtomicInteger notifications = new AtomicInteger();
        var store = new SettingsStore(path, schema, v -> notifications.incrementAndGet());
        JsonObject raw = new JsonObject(); raw.addProperty("futureOption", "preserve");
        raw.addProperty("enabled", false); raw.addProperty("size", 2.9); raw.addProperty("color", "#aabbcc");
        var draft = new SettingsValues(schema, raw);
        store.apply(draft);
        assertEquals(1, notifications.get());
        var reloaded = new SettingsStore(path, schema, v -> {});
        assertFalse(reloaded.current().bool("enabled"));
        assertEquals(2, reloaded.current().number("size"));
        assertEquals("#AABBCC", reloaded.current().text("color"));
        assertEquals("preserve", reloaded.current().json().get("futureOption").getAsString());
    }
    @Test void malformedTypesDoNotDisableUnrelatedDefaults() {
        JsonObject raw = new JsonObject();
        raw.addProperty("enabled", "false"); raw.addProperty("size", "bad");
        raw.addProperty("color", "#00000000"); raw.addProperty("message", "unknown");
        var values = new SettingsValues(schema, raw);
        assertTrue(values.bool("enabled")); assertEquals(1, values.number("size"));
        assertEquals("#088DA5", values.text("color")); assertEquals("chat", values.text("message"));
    }
    @Test void failedSaveDoesNotPublishOrNotify() throws Exception {
        Path parent = directory.resolve("not-a-directory"); Files.writeString(parent, "data");
        AtomicInteger notifications = new AtomicInteger();
        var store = new SettingsStore(parent.resolve("options.json"), schema, v -> notifications.incrementAndGet());
        var draft = store.current(); draft.set(schema.getFirst(), new JsonPrimitive(false));
        assertThrows(java.io.IOException.class, () -> store.apply(draft));
        assertTrue(store.current().bool("enabled")); assertEquals(0, notifications.get());
    }
    @Test void corruptedFileBackedUpAndDefaultsCanBeSaved() throws Exception {
        Path path = directory.resolve("options.json"); Files.writeString(path, "{ invalid");
        var store = new SettingsStore(path, schema, v -> {});
        assertNotNull(store.loadWarning()); assertTrue(store.current().bool("enabled"));
        try (var files = Files.list(directory)) { assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".invalid-"))); }
        store.apply(store.current()); assertNull(store.loadWarning());
        assertTrue(JsonParser.parseString(Files.readString(path)).isJsonObject());
    }
    @Test void cancelDraftAndCategoryResetAreIsolated() {
        var store = new SettingsStore(directory.resolve("options.json"), schema, v -> {});
        var draft = store.current(); draft.set(schema.getFirst(), new JsonPrimitive(false));
        draft.set(schema.get(1), new JsonPrimitive(1.7)); draft.reset("display");
        assertFalse(draft.bool("enabled")); assertEquals(1, draft.number("size"));
        assertTrue(store.current().bool("enabled"));
    }
}
