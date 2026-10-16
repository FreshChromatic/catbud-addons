package github.freshchromatic.catbud_addons.catbud_core;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

public record SettingsAddon(String id, Component name, String version, List<String> categories, List<Setting> settings,
    SettingsStore store, Supplier<List<StatusRow>> status, Function<String, List<StatusRow>> extraRows) {

    public SettingsAddon(String id, Component name, String version, List<String> categories, List<Setting> settings, SettingsStore store, Supplier<List<StatusRow>> status) {
        this(id, name, version, categories, settings, store, status, category -> List.of());
    }

    public SettingsAddon {
        categories = List.copyOf(categories);
        settings = List.copyOf(settings);
        if (settings.stream().map(Setting::id).distinct().count() != settings.size()) {
            throw new IllegalArgumentException("Duplicate setting ids");
        }
    }

    public record StatusRow(Component label, Supplier<Component> value, Runnable action, BooleanSupplier available,
        Supplier<Component> explanation, BooleanSupplier checked, ActionConfirmation confirmation,
        StatusSection section) {

        public StatusRow(Component label, Supplier<Component> value, Runnable action, BooleanSupplier available, Supplier<Component> explanation, BooleanSupplier checked) {
            this(label, value, action, available, explanation, checked, null, null);
        }

        public StatusRow(Component label, Supplier<Component> value, Runnable action, BooleanSupplier available, Supplier<Component> explanation) {
            this(label, value, action, available, explanation, null);
        }

        public static StatusRow info(Component label, Supplier<Component> value) {
            return new StatusRow(label, value, null, () -> true, () -> Component.empty());
        }

        public StatusRow withConfirmation(ActionConfirmation confirmation) {
            return new StatusRow(label, value, action, available, explanation, checked, confirmation, section);
        }

        public static StatusRow section(String id, Component label, Supplier<Component> explanation, List<StatusRow> rows) {
            return new StatusRow(label, () -> label, null, () -> true, explanation, null, null, new StatusSection(id,
                rows));
        }
    }

    public record ActionConfirmation(Component title, Component message, Component confirmLabel) {}

    public record StatusSection(String id, List<StatusRow> rows) {

        public StatusSection {
            rows = List.copyOf(rows);
        }
    }
}
