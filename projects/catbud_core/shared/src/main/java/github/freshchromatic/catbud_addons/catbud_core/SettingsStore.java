package github.freshchromatic.catbud_addons.catbud_core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Save on disk before publishing a validated snapshot to the running addon. */
public final class SettingsStore {
    private final Path path;
    private final List<Setting> schema;
    private final Consumer<SettingsValues> changed;
    private SettingsValues current;
    private JsonObject document = new JsonObject();
    private String loadWarning;
    public SettingsStore(Path path, List<Setting> schema, Consumer<SettingsValues> changed) {
        this.path = path;
        this.schema = List.copyOf(schema);
        this.changed = changed;
        JsonObject values = new JsonObject();
        try {
            if (Files.exists(path)) {
                document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (document.has("values")) values = document.getAsJsonObject("values");
            }
        } catch (Exception e) {
            loadWarning = "config.catbud_core.load_failed";
            LoggerFactory.getLogger("Catbud Settings").warn("Cannot load {}; using defaults", path, e);
            try { Files.copy(path, path.resolveSibling(path.getFileName() + ".invalid-" + System.currentTimeMillis())); }
            catch (IOException backupError) { LoggerFactory.getLogger("Catbud Settings").warn("Cannot back up invalid configuration", backupError); }
            document = new JsonObject();
        }
        current = new SettingsValues(schema, values);
    }
    public SettingsValues current() { return current.copy(); }
    public String loadWarning() { return loadWarning; }
    public boolean exists() { return Files.exists(path); }
    public void apply(SettingsValues draft) throws IOException {
        SettingsValues validated = new SettingsValues(schema, draft.json());
        JsonObject next = document.deepCopy();
        next.addProperty("configVersion", 1);
        next.add("values", validated.json());
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".catbud-", ".tmp");
        try {
            Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(next));
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
        current = validated;
        document = next;
        loadWarning = null;
        changed.accept(current.copy());
    }
}
