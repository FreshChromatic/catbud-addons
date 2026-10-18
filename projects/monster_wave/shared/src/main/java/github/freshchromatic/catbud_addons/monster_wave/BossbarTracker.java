package github.freshchromatic.catbud_addons.monster_wave;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

final class BossbarTracker {

    enum Signal {
        NONE, QUEUE, READY, READY_START, READY_EXIT
    }

    record Tracked(UUID id, RunEvent.BossValue value, Signal signal) {}

    record Change(String operation, Tracked bar) {}

    private final Map<UUID, Tracked> bars = new LinkedHashMap<>();
    private final Consumer<Change>   listener;

    BossbarTracker(Consumer<Change> listener) {
        this.listener = listener;
    }

    void add(UUID id, RunEvent.BossValue value, Signal signal) {
        var bar = new Tracked(id, value, signal);
        bars.put(id, bar);
        listener.accept(new Change("add", bar));
    }

    void name(UUID id, RunEvent.TextValue name, Signal signal) {
        Tracked old = bars.get(id);
        if (old != null) {
            var v = old.value();
            var bar = new Tracked(id, new RunEvent.BossValue(name, v.progress(), v.color(), v.overlay(), v
                .darkenScreen(), v.playMusic(), v.createWorldFog()), signal);
            if (!bar.equals(old)) {
                bars.put(id, bar);
                listener.accept(new Change("name", bar));
            }
        }
    }

    void update(UUID id, String operation, UnaryOperator<RunEvent.BossValue> update) {
        Tracked old = bars.get(id);
        if (old != null) {
            var bar = new Tracked(id, update.apply(old.value()), old.signal());
            if (!bar.equals(old)) {
                bars.put(id, bar);
                listener.accept(new Change(operation, bar));
            }
        }
    }

    void remove(UUID id) {
        Tracked old = bars.remove(id);
        if (old != null) {
            listener.accept(new Change("remove", old));
        }
    }

    List<Tracked> snapshot() {
        return List.copyOf(bars.values());
    }

    boolean contains(UUID id) {
        return bars.containsKey(id);
    }

    void clear() {
        bars.clear();
    }
}
