package github.freshchromatic.catbud_addons.catbud_core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.UnaryOperator;

final class HudInspector {

    record Control(Component label, AbstractWidget widget) {}

    private record NumberInput(List<String> selection, HudNumberProperty property) {}

    private final Screen                             parent;
    private final HudEditorSession                   session;
    private final Runnable                           rebuild;
    private final java.util.Map<NumberInput, String> invalidInputs = new java.util.LinkedHashMap<>();
    private boolean                                  linkedPadding = true;

    HudInspector(Screen parent, HudEditorSession session, Runnable rebuild) {
        this.parent = parent;
        this.session = session;
        this.rebuild = rebuild;
    }

    List<Component> invalidChanges() {
        valid();
        return invalidInputs.entrySet().stream().map(entry -> {
            String elements = entry.getKey().selection().stream().map(id -> CatbudCore.huds().get(id, session
                .snapshot()).name().getString()).collect(java.util.stream.Collectors.joining("、"));
            Component label = Component.literal(elements + " · ").append(Component.translatable("hud.core." + entry
                .getKey().property().name().toLowerCase(Locale.ROOT)));
            return (Component) Component.translatable("hud.core.invalid_change", label, entry.getValue());
        }).toList();
    }

    boolean valid() {
        invalidInputs.keySet().removeIf(key -> key.selection().stream().anyMatch(id -> session.config(id) == null
            || session.config(id).deleted()));
        return invalidInputs.isEmpty();
    }

    void resetSelectedInputs() {
        invalidInputs.keySet().removeIf(key -> key.selection().stream().anyMatch(session.selection()::contains));
    }

    List<Control> controls(String section, int width) {
        List<Control> controls = new ArrayList<>();
        if (session.primary() == null) {
            return controls;
        }
        HudElementConfig config = session.config(session.primary());
        switch (section) {
            case "actions" -> {
                controls.add(new Control(null, new PanelButton(0, 0, width, 20, Component.translatable(
                    "hud.core.copy_style"), b -> {
                        session.copyStyle();
                        rebuild.run();
                    })));
                var paste = new PanelButton(0, 0, width, 20, Component.translatable("hud.core.paste_style"), b -> {
                    session.pasteStyle();
                    rebuild.run();
                });
                controls.add(new Control(null, new PanelButton(0, 0, width, 20, Component.translatable(
                    "hud.core.duplicate"), b -> {
                        session.duplicateSelected();
                        rebuild.run();
                    })));
                var merge = new PanelButton(0, 0, width, 20, Component.translatable("hud.core.merge_background"), b -> {
                    session.mergeSelectedBackgrounds();
                    rebuild.run();
                });
                merge.active = session.selection().size() > 1;
                controls.add(new Control(null, merge));
                controls.add(new Control(null, new PanelButton(0, 0, width, 20, Component.translatable(
                    "hud.core.unmerge_background"), b -> {
                        session.unmergeSelectedBackgrounds();
                        rebuild.run();
                    })));
                var delete = new PanelButton(0, 0, width, 20, Component.translatable("hud.core.delete_copy"), b -> {
                    session.deleteSelectedCopies();
                    rebuild.run();
                });
                delete.active = session.canDeleteSelected();
                controls.add(new Control(null, delete));
                paste.active = session.canPasteStyle();
                controls.add(new Control(null, paste));
            }
            case "position" -> {
                button(controls, "enabled", c -> c.enabled(), c -> c.withEnabled(!config.enabled()), width);
                button(controls, "locked", c -> c.locked(), c -> c.withLocked(!config.locked()), width);
                Object anchor = config.placement().anchor();
                boolean mixed = session.selection().stream().anyMatch(id -> session.config(id).placement()
                    .anchor() != anchor);
                controls.add(new Control(null, new PanelButton(0, 0, width, 20, Component.translatable(
                    "hud.core.property_value", Component.translatable("hud.core.anchor"), mixed ? Component
                        .translatable("hud.core.mixed") : label(anchor)), b -> {
                            List<HudChoiceScreen.Choice> choices = java.util.Arrays.stream(HudPlacement.Anchor.values())
                                .map(value -> new HudChoiceScreen.Choice(label(value), () -> ((HudEditorScreen) parent)
                                    .anchorSelected(value))).toList();
                            ClientPlatform.show(Minecraft.getInstance(), new HudChoiceScreen(parent, Component
                                .translatable("hud.core.anchor"), choices));
                            rebuild.run();
                        })));
            }
            case "attachment" -> {
                var placement = config.placement();
                Component targetName = placement.relativeTo().isEmpty() ? Component.translatable(
                    "hud.core.screen_anchor") : Component.literal(placement.relativeTo());
                for (var element : CatbudCore.huds().elements(session.snapshot())) {
                    if (element.id().equals(placement.relativeTo())) {
                        targetName = element.name();
                    }
                }
                controls.add(new Control(null, new PanelButton(0, 0, width, 20, Component.translatable(
                    "hud.core.property_value", Component.translatable("hud.core.relative_to"), targetName), b -> {
                        List<HudChoiceScreen.Choice> choices = new ArrayList<>();
                        choices.add(new HudChoiceScreen.Choice(Component.translatable("hud.core.screen_anchor"),
                            () -> ((HudEditorScreen) parent).relativeSelected("")));
                        for (var element : CatbudCore.huds().elements(session.snapshot())) {
                            boolean invalid = session.selection().stream().anyMatch(id -> HudLayoutResolver.wouldCycle(
                                session.snapshot(), id, element.id()));
                            if (!invalid) {
                                choices.add(new HudChoiceScreen.Choice(element.name(), () -> ((HudEditorScreen) parent)
                                    .relativeSelected(element.id())));
                            }
                        }
                        ClientPlatform.show(Minecraft.getInstance(), new HudChoiceScreen(parent, Component.translatable(
                            "hud.core.relative_to"), choices));
                    })));
                choice(controls, "relative_anchor", c -> c.placement().relativeAnchor(), (c, value) -> c.locked() ? c
                    : c.withPlacement(c.placement().withRelative(c.placement().relativeTo(), value)),
                    HudPlacement.Anchor.values(), width);
                button(controls, "independent_scale", c -> c.placement().guiScale() > 0, c -> {
                    if (c.locked()) {
                        return c;
                    }
                    var current = c.placement();
                    var next = current.withGuiScale(HudRenderer.guiScale());
                    if (placement.guiScale() > 0) {
                        double factor = current.factor(HudRenderer.guiScale());
                        next = current.withGuiScale(0).withScale(current.scale() * factor).withOffset(current.x()
                            * factor, current.y() * factor);
                    }
                    return c.withPlacement(next);
                }, width);
            }
            case "text" -> {
                choice(controls, "font", c -> c.style().font(), (c, value) -> HudNumberProperty.style(c,
                    b -> b.font = value), HudStyle.FontFace.values(), width);
                button(controls, "bold", c -> c.style().bold(), c -> HudNumberProperty.style(c, b -> b.bold = !config
                    .style().bold()), width);
                button(controls, "italic", c -> c.style().italic(), c -> HudNumberProperty.style(c,
                    b -> b.italic = !config.style().italic()), width);
                choice(controls, "alignment", c -> c.style().alignment(), (c, value) -> HudNumberProperty.style(c,
                    b -> b.alignment = value), HudStyle.Alignment.values(), width);
                color(controls, "text_color", config.style().textColor(), c -> c.style().textColor(),
                    (c, color) -> HudNumberProperty.style(c, b -> b.textColor = color), width);
            }
            case "formatting" -> {
                var format = config.style().formatting();
                choice(controls, "weight", c -> c.style().formatting().weight(), (c, value) -> HudNumberProperty.style(
                    c, b -> b.formatting = new HudTextFormat(b.formatting.underline(), b.formatting.letterCase(),
                        b.formatting.prefix(), b.formatting.suffix(), b.formatting.brackets(), value)),
                    HudTextFormat.Weight.values(), width);
                button(controls, "underline", c -> c.style().formatting().underline(), c -> HudNumberProperty.style(c,
                    b -> b.formatting = new HudTextFormat(!format.underline(), b.formatting.letterCase(), b.formatting
                        .prefix(), b.formatting.suffix(), b.formatting.brackets(), b.formatting.weight())), width);
                choice(controls, "letter_case", c -> c.style().formatting().letterCase(),
                    (c, value) -> HudNumberProperty.style(c, b -> b.formatting = new HudTextFormat(b.formatting
                        .underline(), value, b.formatting.prefix(), b.formatting.suffix(), b.formatting.brackets(),
                        b.formatting.weight())), HudTextFormat.LetterCase.values(), width);
                button(controls, "brackets", c -> c.style().formatting().brackets(), c -> HudNumberProperty.style(c,
                    b -> b.formatting = new HudTextFormat(b.formatting.underline(), b.formatting.letterCase(),
                        b.formatting.prefix(), b.formatting.suffix(), !format.brackets(), b.formatting.weight())),
                    width);
                text(controls, "prefix", c -> c.style().formatting().prefix(), (c, value) -> HudNumberProperty.style(c,
                    b -> b.formatting = new HudTextFormat(b.formatting.underline(), b.formatting.letterCase(), value,
                        b.formatting.suffix(), b.formatting.brackets(), b.formatting.weight())), width);
                text(controls, "suffix", c -> c.style().formatting().suffix(), (c, value) -> HudNumberProperty.style(c,
                    b -> b.formatting = new HudTextFormat(b.formatting.underline(), b.formatting.letterCase(),
                        b.formatting.prefix(), value, b.formatting.brackets(), b.formatting.weight())), width);
            }
            case "background" -> {
                button(controls, "background", c -> c.style().background(), c -> HudNumberProperty.style(c,
                    b -> b.background = !config.style().background()), width);
                color(controls, "background_color", config.style().backgroundColor(), c -> c.style().backgroundColor(),
                    (c, color) -> HudNumberProperty.style(c, b -> b.backgroundColor = color), width);
            }
            case "text_shadow", "box_shadow" -> {
                boolean text = section.equals("text_shadow");
                HudStyle.Shadow shadow = text ? config.style().textShadow() : config.style().boxShadow();
                button(controls, "shadow", c -> (text ? c.style().textShadow() : c.style().boxShadow()).enabled(),
                    c -> HudNumberProperty.style(c, b -> {
                        var s = text ? b.textShadow : b.boxShadow;
                        var next = new HudStyle.Shadow(!shadow.enabled(), s.color(), s.x(), s.y(), s.blur());
                        if (text) {
                            b.textShadow = next;
                        } else {
                            b.boxShadow = next;
                        }
                    }), width);
                color(controls, "shadow_color", shadow.color(), c -> (text ? c.style().textShadow()
                    : c.style().boxShadow()).color(), (c, color) -> HudNumberProperty.style(c, b -> {
                        var s = text ? b.textShadow : b.boxShadow;
                        var next = new HudStyle.Shadow(s.enabled(), color, s.x(), s.y(), s.blur());
                        if (text) {
                            b.textShadow = next;
                        } else {
                            b.boxShadow = next;
                        }
                    }), width);
            }
            case "padding" -> controls.add(new Control(null, new PanelButton(0, 0, width, 20, Component.translatable(
                "hud.core.linked_padding", label(linkedPadding)), b -> {
                    linkedPadding = !linkedPadding;
                    rebuild.run();
                })));
            default -> {
            }
        }
        for (HudNumberProperty property : HudNumberProperty.values()) {
            if (property.section.equals(section)) {
                if (linkedPadding && section.equals("padding") && property != HudNumberProperty.PADDING_TOP) {
                    continue;
                }
                number(controls, property, width);
            }
        }
        return controls;
    }

    private void text(List<Control> controls, String key, Function<HudElementConfig, String> read, java.util.function.BiFunction<HudElementConfig, String, HudElementConfig> edit, int width) {
        String initial = read.apply(session.config(session.primary()));
        boolean mixed = session.selection().stream().anyMatch(id -> !initial.equals(read.apply(session.config(id))));
        EditBox input = new EditBox(Minecraft.getInstance().font, 0, 0, Math.max(40, width / 2), 20, Component
            .translatable("hud.core." + key));
        input.setMaxLength(64);
        input.setHint(Component.translatable(mixed ? "hud.core.mixed" : "hud.core.empty"));
        input.setValue(mixed ? "" : initial);
        input.setResponder(value -> session.updateSelected(c -> edit.apply(c, value)));
        controls.add(new Control(Component.translatable("hud.core." + key), input));
    }

    private <T extends Enum<T>> void choice(List<Control> controls, String key, Function<HudElementConfig, T> read, java.util.function.BiFunction<HudElementConfig, T, HudElementConfig> edit, T[] options, int width) {
        T initial = read.apply(session.config(session.primary()));
        boolean mixed = session.selection().stream().anyMatch(id -> read.apply(session.config(id)) != initial);
        controls.add(new Control(null, new PanelButton(0, 0, width, 20, Component.translatable(
            "hud.core.property_value", Component.translatable("hud.core." + key), mixed ? Component.translatable(
                "hud.core.mixed") : label(initial)), b -> {
                    List<HudChoiceScreen.Choice> choices = java.util.Arrays.stream(options).map(
                        value -> new HudChoiceScreen.Choice(label(value), () -> session.updateSelected(c -> edit.apply(
                            c, value)))).toList();
                    ClientPlatform.show(Minecraft.getInstance(), new HudChoiceScreen(parent, Component.translatable(
                        "hud.core." + key), choices));
                })));
    }

    private void button(List<Control> controls, String key, Function<HudElementConfig, ?> read, UnaryOperator<HudElementConfig> edit, int width) {
        Object initial = read.apply(session.config(session.primary()));
        boolean mixed = session.selection().stream().anyMatch(id -> !initial.equals(read.apply(session.config(id))));
        Component message = Component.translatable("hud.core.property_value", Component.translatable("hud.core." + key),
            mixed ? Component.translatable("hud.core.mixed") : label(initial));
        controls.add(new Control(null, new PanelButton(0, 0, width, 20, message, button -> {
            session.updateSelected(edit);
            rebuild.run();
        })));
    }

    private void color(List<Control> controls, String key, int initial, Function<HudElementConfig, Integer> read, java.util.function.BiFunction<HudElementConfig, Integer, HudElementConfig> edit, int width) {
        String hex = String.format(Locale.ROOT, "#%06X", initial & 0xFFFFFF);
        boolean mixed = session.selection().stream().anyMatch(id -> (read.apply(session.config(id))
            & 0xFFFFFF) != (initial & 0xFFFFFF));
        controls.add(new Control(null, new PanelButton(0, 0, width, 20, Component.translatable(
            "hud.core.property_value", Component.translatable("hud.core." + key), mixed ? Component.translatable(
                "hud.core.mixed") : Component.literal(hex)), b -> ClientPlatform.show(Minecraft.getInstance(),
                    new ColorScreen(parent, Component.translatable("hud.core." + key), hex, chosen -> {
                        int rgb = Integer.parseInt(chosen.substring(1), 16);
                        session.updateSelected(c -> edit.apply(c, read.apply(c) & 0xFF000000 | rgb));
                    })))));
    }

    private void number(List<Control> controls, HudNumberProperty property, int width) {
        NumberInput key = new NumberInput(session.selection().stream().sorted().toList(), property);
        double value = property.get.applyAsDouble(session.config(session.primary()));
        boolean mixed = session.selection().stream().anyMatch(id -> property.get.applyAsDouble(session.config(
            id)) != value);
        EditBox input = new EditBox(Minecraft.getInstance().font, 0, 0, Math.min(68, width / 2), 20, Component
            .translatable("hud.core." + property.name().toLowerCase(Locale.ROOT)));
        input.setMaxLength(12);
        input.setHint(Component.translatable("hud.core.mixed"));
        input.setValue(mixed ? "" : format(value));
        input.setTooltip(Tooltip.create(Component.literal(format(property.min) + " … " + format(property.max))));
        input.setResponder(text -> {
            try {
                double next = Double.parseDouble(text);
                if (!Double.isFinite(next) || next < property.min || next > property.max) {
                    throw new NumberFormatException();
                }
                invalidInputs.remove(key);
                input.setTextColor(0xFFFFFFFF);
                if (linkedPadding && property == HudNumberProperty.PADDING_TOP) {
                    session.updateSelected(c -> HudNumberProperty.style(c, b -> b.padding = new HudStyle.Insets(next,
                        next, next, next)));
                } else {
                    session.updateSelected(c -> property.section.equals("position") && c.locked() ? c
                        : property.set.apply(c, next));
                }
            } catch (NumberFormatException error) {
                invalidInputs.put(key, text);
                input.setTextColor(0xFFFF7777);
            }
        });
        if (invalidInputs.containsKey(key)) {
            input.setValue(invalidInputs.get(key));
        }
        String labelKey = linkedPadding && property == HudNumberProperty.PADDING_TOP ? "padding_all"
            : property.name().toLowerCase(Locale.ROOT);
        controls.add(new Control(Component.translatable("hud.core." + labelKey), input));
    }

    private static Component label(Object value) {
        if (value instanceof Boolean enabled) {
            return Component.translatable(enabled ? "option.catbud_core.on" : "option.catbud_core.off");
        }
        return Component.translatable("hud.core.value." + value.toString().toLowerCase(Locale.ROOT));
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : String.format(Locale.ROOT, "%.2f", value);
    }
}
