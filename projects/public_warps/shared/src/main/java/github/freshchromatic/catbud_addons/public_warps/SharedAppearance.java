package github.freshchromatic.catbud_addons.public_warps;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.nbt.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class SharedAppearance {

    private static final JsonObject POLICY = loadPolicy();

    private SharedAppearance() {
    }

    private static JsonObject loadPolicy() {
        try (var stream = SharedAppearance.class.getResourceAsStream(
            "/assets/catbud_public_warps/shared-appearance.json")) {
            if (stream == null) {
                throw new IllegalStateException("Shared appearance policy missing");
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public static Map<String, String> sanitize(Map<String, String> appearance) {
        var result = new TreeMap<String, String>();
        if (appearance == null) {
            return result;
        }
        for (var entry : appearance.entrySet()) {
            var schema = POLICY.getAsJsonObject("fields").get(entry.getKey());
            if (schema == null || entry.getValue() == null || entry.getValue().length() > 4096) {
                continue;
            }
            try {
                var compound = TagParser.parseCompoundFully("{value:" + entry.getValue() + "}");
                Tag clean = sanitizeTag(compound.get("value"), schema.getAsJsonObject(), 0);
                if (clean != null) {
                    result.put(entry.getKey(), clean.toString());
                }
            } catch (Exception invalidAppearance) {
                // Invalid visual data is omitted without losing the complete local snapshot.
            }
        }
        return result;
    }

    private static Tag sanitizeTag(Tag value, JsonObject schema, int depth) {
        if (value == null || depth > 16) {
            return null;
        }
        if (schema.has("ref")) {
            schema = POLICY.getAsJsonObject("definitions").getAsJsonObject(schema.get("ref").getAsString());
        }
        return switch (schema.get("kind").getAsString()) {
            case "number" -> numeric(value, schema);
            case "string" -> string(value, schema);
            case "compound" -> compound(value, schema, depth);
            case "list" -> list(value, schema, depth);
            case "properties" -> properties(value, schema);
            case "choice" -> choice(value, schema, depth);
            default -> null;
        };
    }

    private static Tag numeric(Tag value, JsonObject schema) {
        if (!(value instanceof NumericTag number)) {
            return null;
        }
        double scalar = number.doubleValue();
        if (!Double.isFinite(scalar) || scalar < schema.get("min").getAsDouble() || scalar > schema.get("max")
            .getAsDouble()) {
            return null;
        }
        return value.copy();
    }

    private static Tag string(Tag value, JsonObject schema) {
        if (!(value instanceof StringTag)) {
            return null;
        }
        String text = value.asString().orElse("");
        if (text.length() > schema.get("max").getAsInt() || !text.matches(schema.get("pattern").getAsString())) {
            return null;
        }
        return value.copy();
    }

    private static Tag compound(Tag value, JsonObject schema, int depth) {
        if (!(value instanceof CompoundTag compound)) {
            return null;
        }
        var result = new CompoundTag();
        var fields = schema.getAsJsonObject("fields");
        for (String key : new TreeSet<>(fields.keySet())) {
            Tag clean = sanitizeTag(compound.get(key), fields.getAsJsonObject(key), depth + 1);
            if (clean != null) {
                result.put(key, clean);
            }
        }
        if (schema.has("glintFrom") && !result.contains("minecraft:enchantment_glint_override")) {
            Tag enchantments = compound.get(schema.get("glintFrom").getAsString());
            boolean enchanted = (enchantments instanceof CompoundTag data && !data.isEmpty())
                || (enchantments instanceof CollectionTag list && !list.isEmpty());
            if (enchanted) {
                result.putBoolean("minecraft:enchantment_glint_override", true);
            }
        }
        return result;
    }

    private static Tag list(Tag value, JsonObject schema, int depth) {
        if (!(value instanceof CollectionTag list) || list.size() > schema.get("max").getAsInt() || (schema.has("min")
            && list.size() < schema.get("min").getAsInt())) {
            return null;
        }
        var result = new ListTag();
        for (var item : list) {
            Tag clean = sanitizeTag(item, schema.getAsJsonObject("element"), depth + 1);
            if (clean == null) {
                return null;
            }
            result.add(clean);
        }
        return result;
    }

    private static Tag properties(Tag value, JsonObject schema) {
        if (!(value instanceof CompoundTag compound) || compound.size() > schema.get("max").getAsInt()) {
            return null;
        }
        var result = new CompoundTag();
        for (String key : new TreeSet<>(compound.keySet())) {
            Tag item = compound.get(key);
            String text = item.asString().orElse("");
            if (key.matches("[a-z0-9_]{1,32}") && text.matches("[a-z0-9_.-]{1,32}")) {
                result.put(key, item.copy());
            }
        }
        return result;
    }

    private static Tag choice(Tag value, JsonObject schema, int depth) {
        for (var option : schema.getAsJsonArray("options")) {
            Tag result = sanitizeTag(value, option.getAsJsonObject(), depth + 1);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
