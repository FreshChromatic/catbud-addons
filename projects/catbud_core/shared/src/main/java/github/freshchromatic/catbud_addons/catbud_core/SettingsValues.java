package github.freshchromatic.catbud_addons.catbud_core;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;

public final class SettingsValues {
    private final List<Setting> schema;
    private final JsonObject data;
    public SettingsValues(List<Setting> schema, JsonObject source) {
        this.schema = List.copyOf(schema);
        data = source.deepCopy(); // Preserve unknown fields for addons from newer versions.
        for (Setting setting : schema) {
            var input = data.get(setting.id());
            data.add(setting.id(), setting.validate(input != null && input.isJsonPrimitive()
                ? input.getAsJsonPrimitive() : setting.defaultValue()));
        }
    }
    public SettingsValues copy() { return new SettingsValues(schema, data); }
    public JsonObject json() { return data.deepCopy(); }
    public boolean bool(String id) { return data.get(id).getAsBoolean(); }
    public double number(String id) { return data.get(id).getAsDouble(); }
    public int integer(String id) { return (int) Math.round(number(id)); }
    public String text(String id) { return data.get(id).getAsString(); }
    public int color(String id, String opacity) {
        return (int) Math.round(number(opacity) * 255) << 24 | Integer.parseInt(text(id).substring(1), 16);
    }
    public void set(Setting setting, JsonPrimitive value) { data.add(setting.id(), setting.validate(value)); }
    public void reset(String category) {
        schema.stream().filter(s -> s.category().equals(category)).forEach(s -> data.add(s.id(), s.defaultValue()));
    }
}
