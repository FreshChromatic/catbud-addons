package github.freshchromatic.catbud_addons.catbud_core;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Function;
import net.minecraft.network.chat.Component;

public record SettingsAddon(String id, Component name, String version, List<String> categories,
                            List<Setting> settings, SettingsStore store, Supplier<List<StatusRow>> status,
                            Function<String, List<StatusRow>> extraRows) {
    public SettingsAddon(String id, Component name, String version, List<String> categories,
                         List<Setting> settings, SettingsStore store, Supplier<List<StatusRow>> status) {
        this(id, name, version, categories, settings, store, status, category -> List.of());
    }
    public SettingsAddon {
        categories = List.copyOf(categories);
        settings = List.copyOf(settings);
        if (settings.stream().map(Setting::id).distinct().count() != settings.size())
            throw new IllegalArgumentException("Duplicate setting ids");
    }
    public record StatusRow(Component label, Supplier<Component> value, Runnable action,
                            BooleanSupplier available, Supplier<Component> explanation, BooleanSupplier checked) {
        public StatusRow(Component label, Supplier<Component> value, Runnable action,
                         BooleanSupplier available, Supplier<Component> explanation) {
            this(label, value, action, available, explanation, null);
        }
        public static StatusRow info(Component label, Supplier<Component> value) {
            return new StatusRow(label, value, null, () -> true, () -> Component.empty());
        }
    }
}
