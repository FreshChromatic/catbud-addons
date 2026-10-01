package github.freshchromatic.catbud_addons.catbud_core;

import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;

/** A declarative option: the core owns validation, storage and its native widget. */
public record Setting(String id, String category, Kind kind, JsonPrimitive defaultValue,
                      double min, double max, double step, List<String> choices,
                      Predicate<SettingsValues> available) {
    public enum Kind { BOOLEAN, NUMBER, COLOR, CHOICE }
    public Setting {
        if (!id.matches("[a-zA-Z][a-zA-Z0-9_.]*")) throw new IllegalArgumentException("Invalid setting id");
        choices = List.copyOf(choices);
    }
    public Component label(String addon) { return Component.translatable("setting." + addon + "." + id); }
    public Component description(String addon) { return Component.translatable("setting." + addon + "." + id + ".description"); }
    public JsonPrimitive validate(JsonPrimitive input) {
        try {
            return switch (kind) {
                case BOOLEAN -> input.isBoolean() ? input : defaultValue;
                case COLOR -> input.isString() && input.getAsString().matches("#[0-9a-fA-F]{6}")
                    ? new JsonPrimitive(input.getAsString().toUpperCase(java.util.Locale.ROOT)) : defaultValue;
                case CHOICE -> input.isString() && choices.contains(input.getAsString()) ? input : defaultValue;
                case NUMBER -> {
                    if (!input.isNumber() || !Double.isFinite(input.getAsDouble())) yield defaultValue;
                    double n = Math.clamp(input.getAsDouble(), min, max);
                    yield new JsonPrimitive(Math.clamp(min + Math.round((n - min) / step) * step, min, max));
                }
            };
        } catch (RuntimeException e) { return defaultValue; }
    }
    public static Setting toggle(String id, String category, boolean value, Predicate<SettingsValues> dependency) {
        return new Setting(id, category, Kind.BOOLEAN, new JsonPrimitive(value), 0, 1, 1, List.of(), dependency);
    }
    public static Setting number(String id, String category, double value, double min, double max, double step,
                                 Predicate<SettingsValues> dependency) {
        return new Setting(id, category, Kind.NUMBER, new JsonPrimitive(value), min, max, step, List.of(), dependency);
    }
    public static Setting color(String id, String category, String value, Predicate<SettingsValues> dependency) {
        return new Setting(id, category, Kind.COLOR, new JsonPrimitive(value), 0, 0, 1, List.of(), dependency);
    }
    public static Setting choice(String id, String category, String value, List<String> choices, Predicate<SettingsValues> dependency) {
        return new Setting(id, category, Kind.CHOICE, new JsonPrimitive(value), 0, 0, 1, choices, dependency);
    }
}
