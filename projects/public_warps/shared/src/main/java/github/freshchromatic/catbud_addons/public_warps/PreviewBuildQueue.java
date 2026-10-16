package github.freshchromatic.catbud_addons.public_warps;

import java.util.LinkedHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class PreviewBuildQueue<K, V> {

    public interface Task<V> {

        boolean advance();

        V result();

        default void cancel() {
        }
    }

    private final LinkedHashMap<K, Supplier<Task<V>>> visible = new LinkedHashMap<>();
    private final LongSupplier                        clock;
    private final BiConsumer<K, V>                    publish;
    private K                                         activeKey;
    private Task<V>                                   active;

    public PreviewBuildQueue(LongSupplier clock, BiConsumer<K, V> publish) {
        this.clock = clock;
        this.publish = publish;
    }

    public void beginFrame() {
        visible.clear();
    }

    public void request(K key, Supplier<Task<V>> create) {
        visible.remove(key);
        visible.put(key, create);
        if (visible.size() > 64) {
            visible.remove(visible.keySet().iterator().next());
        }
    }

    public void endFrame() {
        if (active != null && !visible.containsKey(activeKey)) {
            active.cancel();
            active = null;
            activeKey = null;
        }
        if (active == null && !visible.isEmpty()) {
            for (K key : visible.keySet()) {
                activeKey = key;
            }
            active = visible.get(activeKey).get();
        }
        long deadline = clock.getAsLong() + 2_000_000;
        for (int steps = 0; active != null && steps < 8192; steps++) {
            if (clock.getAsLong() >= deadline) {
                break;
            }
            if (active.advance()) {
                V result = active.result();
                publish.accept(activeKey, result);
                active = null;
                activeKey = null;
            }
        }
    }

    public void clear() {
        if (active != null) {
            active.cancel();
        }
        active = null;
        activeKey = null;
        visible.clear();
    }

    public boolean hasWork() {
        return active != null || !visible.isEmpty();
    }
}
