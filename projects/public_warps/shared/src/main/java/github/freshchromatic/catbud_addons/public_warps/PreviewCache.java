package github.freshchromatic.catbud_addons.public_warps;

import java.util.LinkedHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class PreviewCache<K, V> {

    private record Entry<V>(V value, long bytes) {}

    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(64, .75f, true);
    private final int                        limit;
    private final long                       budget;
    private final BiFunction<K, V, Long>     weigh;
    private final BiConsumer<K, V>           release;
    private long                             bytes;

    public PreviewCache(int limit, long budget, BiFunction<K, V, Long> weigh, BiConsumer<K, V> release) {
        if (limit < 1 || budget < 1) {
            throw new IllegalArgumentException("Invalid preview cache limits");
        }
        this.limit = limit;
        this.budget = budget;
        this.weigh = weigh;
        this.release = release;
    }

    public V get(K key) {
        Entry<V> entry = entries.get(key);
        return entry == null ? null : entry.value();
    }

    public V computeIfAbsent(K key, Function<K, V> create) {
        V existing = get(key);
        if (existing != null) {
            return existing;
        }
        V value = create.apply(key);
        put(key, value);
        return value;
    }

    public void put(K key, V value) {
        if (value == null) {
            throw new IllegalArgumentException("Invalid preview cache entry");
        }
        long weight = weigh.apply(key, value);
        if (weight < 0) {
            throw new IllegalArgumentException("Invalid preview cache entry");
        }
        remove(key);
        entries.put(key, new Entry<>(value, weight));
        bytes += weight;
        // Retain one oversized scene so full previews do not rebuild every frame.
        while (entries.size() > limit || (bytes > budget && entries.size() > 1)) {
            remove(entries.keySet().iterator().next());
        }
    }

    public void remove(K key) {
        Entry<V> entry = entries.remove(key);
        if (entry != null) {
            bytes -= entry.bytes();
            release.accept(key, entry.value());
        }
    }

    public void clear() {
        for (var entry : entries.entrySet()) {
            release.accept(entry.getKey(), entry.getValue().value());
        }
        entries.clear();
        bytes = 0;
    }

    public int size() {
        return entries.size();
    }

    public long bytes() {
        return bytes;
    }
}
