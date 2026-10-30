package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public abstract class MobWatchlistEditorScreen extends Screen {

    private final Screen             parent;
    private final MobWatchlistStore  store;
    private final Runnable           changed;
    private final List<MobWatchRule> draft;

    private EditBox                typeInput;
    private EditBox                nameInput;
    private MobWatchRule.NameMatch mode       = MobWatchRule.NameMatch.ANY;
    private String                 typeText   = "";
    private String                 nameText   = "";
    private String                 messageKey = "island.watchlist.help";
    private int                    selected   = -1;
    private int                    offset;

    protected int left;
    protected int panelWidth;

    protected MobWatchlistEditorScreen(Screen parent, MobWatchlistStore store, Runnable changed) {
        super(Component.translatable("island.watchlist.title"));
        this.parent = parent;
        this.store = store;
        this.changed = changed;
        draft = new ArrayList<>(store.rules());
        if (store.loadFailed()) {
            messageKey = "island.watchlist.load_failed";
        }
    }

    @Override
    protected void init() {
        panelWidth = Math.min(580, width - 24);
        left = (width - panelWidth) / 2;
        int count = visibleRows();
        offset = Math.clamp(offset, 0, Math.max(0, draft.size() - count));
        for (int i = offset; i < Math.min(draft.size(), offset + count); i++) {
            final int index = i;
            var rule = draft.get(i);
            String text = (i == selected ? "▶ " : "") + rule.entityType();
            if (rule.nameMatch() != MobWatchRule.NameMatch.ANY) {
                text += " · " + Component.translatable(modeKey(rule.nameMatch())).getString() + ": " + rule.name();
            }
            var row = Button.builder(Component.literal(font.plainSubstrByWidth(text, panelWidth - 16)),
                button -> select(index)).bounds(left, 54 + (i - offset) * 22, panelWidth, 20).build();
            row.setTooltip(Tooltip.create(Component.literal(text)));
            addRenderableWidget(row);
        }
        int navigationWidth = Math.min(76, (panelWidth - 12) / 4);
        addButton("island.watchlist.previous", left, height - 150, navigationWidth, () -> page(
            -count)).active = offset > 0;
        addButton("island.watchlist.next", left + navigationWidth + 4, height - 150, navigationWidth, () -> page(
            count)).active = offset + count < draft.size();
        addButton("island.watchlist.new", left + panelWidth - navigationWidth * 2 - 4, height - 150, navigationWidth,
            () -> select(-1));
        addButton("island.watchlist.remove", left + panelWidth - navigationWidth, height - 150, navigationWidth, () -> {
            draft.remove(selected);
            select(-1);
        }).active = selected >= 0;

        int half = (panelWidth - 8) / 2;
        typeInput = addRenderableWidget(new EditBox(font, left, height - 112, half, 20, Component.translatable(
            "island.watchlist.type")));
        typeInput.setMaxLength(256);
        typeInput.setValue(typeText);
        typeInput.setHint(Component.literal("minecraft:zombie_villager"));
        typeInput.setResponder(value -> typeText = value);
        nameInput = addRenderableWidget(new EditBox(font, left + half + 8, height - 112, half, 20, Component
            .translatable("island.watchlist.name")));
        nameInput.setMaxLength(256);
        nameInput.setValue(nameText);
        nameInput.setResponder(value -> nameText = value);
        nameInput.active = mode != MobWatchRule.NameMatch.ANY;
        addButton(modeKey(mode), left, height - 86, half, () -> {
            mode = MobWatchRule.NameMatch.values()[(mode.ordinal() + 1) % MobWatchRule.NameMatch.values().length];
            rebuildWidgets();
        });
        addButton(selected < 0 ? "island.watchlist.add" : "island.watchlist.update", left + half + 8, height - 86, half,
            this::applyRule);
        addButton("gui.cancel", left, height - 28, half, this::onClose);
        addButton("island.watchlist.save", left + half + 8, height - 28, half, this::save);
    }

    private Button addButton(String key, int x, int y, int size, Runnable action) {
        return addRenderableWidget(Button.builder(Component.translatable(key), button -> action.run()).bounds(x, y,
            size, 20).build());
    }

    private int visibleRows() {
        return Math.max(1, (height - 210) / 22);
    }

    private void page(int amount) {
        offset += amount;
        rebuildWidgets();
    }

    private void select(int index) {
        selected = index;
        if (index < 0) {
            typeText = "";
            nameText = "";
            mode = MobWatchRule.NameMatch.ANY;
        } else {
            var rule = draft.get(index);
            typeText = rule.entityType();
            nameText = rule.name();
            mode = rule.nameMatch();
        }
        rebuildWidgets();
    }

    private void applyRule() {
        try {
            var rule = new MobWatchRule(typeText, mode, nameText);
            store.validate(rule);
            if (selected < 0) {
                draft.add(rule);
                offset = Math.max(0, draft.size() - visibleRows());
            } else {
                draft.set(selected, rule);
            }
            messageKey = "island.watchlist.draft";
            select(-1);
        } catch (IllegalArgumentException error) {
            messageKey = error.getMessage();
        }
    }

    private void save() {
        try {
            store.save(draft);
            changed.run();
            onClose();
        } catch (IOException error) {
            LoggerFactory.getLogger(MobWatchlistEditorScreen.class).warn("Cannot save mob watchlist", error);
            messageKey = "island.watchlist.save_failed";
        }
    }

    protected Component message() {
        return Component.translatable(messageKey);
    }

    protected Component summary() {
        return Component.translatable(draft.isEmpty() ? "island.status.empty" : "island.status.count", draft.size());
    }

    private static String modeKey(MobWatchRule.NameMatch mode) {
        return "island.watchlist.mode." + mode.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public void onClose() {
        ClientPlatform.show(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
