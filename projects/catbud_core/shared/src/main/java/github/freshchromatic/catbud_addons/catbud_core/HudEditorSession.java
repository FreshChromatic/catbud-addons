package github.freshchromatic.catbud_addons.catbud_core;

import java.util.*;
import java.util.function.UnaryOperator;

public final class HudEditorSession {

    private final HudRegistry                          registry;
    private Map<String, HudElementConfig>              entry;
    private final LinkedHashSet<String>                selection = new LinkedHashSet<>();
    private final Deque<Map<String, HudElementConfig>> undo      = new ArrayDeque<>();
    private final Deque<Map<String, HudElementConfig>> redo      = new ArrayDeque<>();

    private Map<String, HudElementConfig> draft;
    private Map<String, HudElementConfig> gesture;
    private HudStyle                      copiedStyle;

    public HudEditorSession(HudRegistry registry, Map<String, HudElementConfig> initial) {
        this.registry = registry;
        draft = new LinkedHashMap<>(initial);
        for (HudElement element : registry.elements()) {
            draft.putIfAbsent(element.id(), element.defaults());
        }
        entry = Map.copyOf(draft);
    }

    public Map<String, HudElementConfig> snapshot() {
        return Map.copyOf(draft);
    }

    public Map<String, HudElementConfig> entry() {
        return entry;
    }

    public void markSaved() {
        entry = snapshot();
    }

    public HudElementConfig config(String id) {
        return draft.get(id);
    }

    public Set<String> selection() {
        return Collections.unmodifiableSet(selection);
    }

    public String primary() {
        return selection.isEmpty() ? null : selection.iterator().next();
    }

    public void select(String id, boolean multiple) {
        if (!multiple) {
            selection.clear();
        }
        if (multiple && selection.contains(id)) {
            selection.remove(id);
        } else {
            selection.add(id);
        }
    }

    public void selectAll(Collection<String> ids) {
        selection.clear();
        selection.addAll(ids);
    }

    public void update(String id, UnaryOperator<HudElementConfig> action) {
        Map<String, HudElementConfig> before = snapshot();
        draft.put(id, action.apply(draft.get(id)));
        record(before);
    }

    public void updateSelected(UnaryOperator<HudElementConfig> action) {
        Map<String, HudElementConfig> before = snapshot();
        for (String id : selection) {
            var original = draft.get(id);
            var next = action.apply(original);
            draft.put(id, next);
            String group = original.style().backgroundGroup();
            var background = HudBackground.from(next.style());
            if (!group.isEmpty() && !background.equals(HudBackground.from(original.style()))) {
                for (String member : new ArrayList<>(draft.keySet())) {
                    var config = draft.get(member);
                    if (config.style().backgroundGroup().equals(group)) {
                        draft.put(member, config.withStyle(background.apply(config.style())));
                    }
                }
            }
        }
        record(before);
    }

    public void selectMergedGroup(String id) {
        String group = config(id).style().backgroundGroup();
        if (!group.isEmpty()) {
            selectAll(registry.elements(draft).stream().filter(element -> config(element.id()).style().backgroundGroup()
                .equals(group)).map(HudElement::id).toList());
        }
    }

    public void mergeSelectedBackgrounds() {
        if (selection.size() < 2) {
            return;
        }
        Map<String, HudElementConfig> before = snapshot();
        String group = UUID.randomUUID().toString();
        var background = HudBackground.from(config(primary()).style().edit(b -> b.background = true));
        for (String id : selection) {
            var config = config(id);
            draft.put(id, config.withStyle(background.apply(config.style()).edit(b -> b.backgroundGroup = group)));
        }
        record(before);
    }

    public void unmergeSelectedBackgrounds() {
        updateSelected(c -> c.withStyle(c.style().edit(b -> b.backgroundGroup = "")));
    }

    public void duplicateSelected() {
        Map<String, HudElementConfig> before = snapshot();
        Map<String, String> copies = new LinkedHashMap<>();
        Map<String, String> groups = new HashMap<>();
        for (String id : selection) {
            String source = config(id).sourceId() == null ? id : config(id).sourceId();
            copies.put(id, source + "/copy_" + UUID.randomUUID().toString().replace("-", ""));
        }
        for (String id : selection) {
            var original = config(id);
            String sourceId = original.sourceId() == null ? id : original.sourceId();
            var placement = original.placement();
            if (copies.containsKey(placement.relativeTo())) {
                placement = placement.withRelative(copies.get(placement.relativeTo()), placement.relativeAnchor());
            } else {
                placement = placement.withOffset(placement.x() + 12, placement.y() + 12);
            }
            String originalGroup = original.style().backgroundGroup();
            String copyGroup = "";
            long members = selection.stream().filter(member -> config(member).style().backgroundGroup().equals(
                originalGroup)).count();
            if (!originalGroup.isEmpty() && members > 1) {
                copyGroup = groups.computeIfAbsent(originalGroup, ignored -> UUID.randomUUID().toString());
            }
            String group = copyGroup;
            draft.put(copies.get(id), original.withSource(sourceId).withLocked(false).withPlacement(placement)
                .withStyle(original.style().edit(b -> b.backgroundGroup = group)));
        }
        selectAll(copies.values());
        record(before);
    }

    public boolean canDeleteSelected() {
        return selection.stream().anyMatch(id -> config(id).sourceId() != null);
    }

    public void deleteSelectedCopies() {
        Map<String, HudElementConfig> before = snapshot();
        for (String id : selection) {
            if (config(id).sourceId() != null) {
                draft.put(id, config(id).asDeleted());
            }
        }
        selection.removeIf(id -> config(id).deleted());
        record(before);
    }

    private void reconcileSelection() {
        selection.removeIf(id -> !draft.containsKey(id) || draft.get(id).deleted());
    }

    public void copyStyle() {
        if (primary() != null) {
            copiedStyle = config(primary()).style();
        }
    }

    public boolean canPasteStyle() {
        return copiedStyle != null && !selection.isEmpty();
    }

    public void pasteStyle() {
        if (canPasteStyle()) {
            updateSelected(c -> c.withStyle(copiedStyle.edit(b -> b.backgroundGroup = c.style().backgroundGroup())));
        }
    }

    public void resetSelected() {
        Map<String, HudElementConfig> before = snapshot();
        for (String id : selection) {
            draft.put(id, registry.get(id, draft).defaults());
        }
        record(before);
    }

    public void beginGesture() {
        gesture = snapshot();
    }

    public void endGesture() {
        Map<String, HudElementConfig> before = gesture;
        gesture = null;
        if (before != null) {
            record(before);
        }
    }

    private void record(Map<String, HudElementConfig> before) {
        if (gesture == null && !before.equals(draft)) {
            undo.push(before);
            while (undo.size() > 100) {
                undo.removeLast();
            }
            redo.clear();
        }
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void undo() {
        if (canUndo()) {
            redo.push(snapshot());
            draft = new LinkedHashMap<>(undo.pop());
            reconcileSelection();
        }
    }

    public void redo() {
        if (canRedo()) {
            undo.push(snapshot());
            draft = new LinkedHashMap<>(redo.pop());
            reconcileSelection();
        }
    }
}
