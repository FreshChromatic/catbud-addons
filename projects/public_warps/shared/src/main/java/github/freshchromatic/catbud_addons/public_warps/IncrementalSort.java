package github.freshchromatic.catbud_addons.public_warps;

import java.util.Comparator;
import java.util.List;

public final class IncrementalSort<T> {

    private final List<T>               values;
    private final Comparator<? super T> order;
    private Object[]                    source;
    private Object[]                    target;
    private int                         input;
    private int                         width = 1;
    private int                         left;
    private int                         middle;
    private int                         end;
    private int                         first;
    private int                         second;
    private int                         output;
    private int                         copied;

    public IncrementalSort(List<T> values, Comparator<? super T> order) {
        this.values = values;
        this.order = order;
        source = new Object[values.size()];
        target = new Object[values.size()];
        startRun();
    }

    public boolean advance() {
        for (int steps = 0; steps < 256; steps++) {
            if (input < values.size()) {
                source[input] = values.get(input);
                input++;
                continue;
            }
            if (width >= values.size()) {
                if (copied == values.size()) {
                    return true;
                }
                values.set(copied, value(source[copied]));
                copied++;
                continue;
            }
            if (output == end) {
                left = end;
                if (left == values.size()) {
                    Object[] swap = source;
                    source = target;
                    target = swap;
                    width *= 2;
                    left = 0;
                    if (width >= values.size()) {
                        continue;
                    }
                }
                startRun();
            }
            boolean takeFirst = first < middle && (second == end || order.compare(value(source[first]), value(
                source[second])) <= 0);
            if (takeFirst) {
                target[output++] = source[first++];
            } else {
                target[output++] = source[second++];
            }
        }
        return copied == values.size() && width >= values.size();
    }

    private void startRun() {
        middle = Math.min(left + width, values.size());
        end = Math.min(left + 2 * width, values.size());
        first = left;
        second = middle;
        output = left;
    }

    @SuppressWarnings("unchecked")
    private T value(Object entry) {
        return (T) entry;
    }
}
