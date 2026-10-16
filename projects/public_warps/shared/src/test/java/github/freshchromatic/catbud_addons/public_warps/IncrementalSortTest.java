package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

class IncrementalSortTest {

    private record Value(int key, int sequence) {}

    @Test
    void boundedStableMergeMatchesStandardSortingForDenseAndUnevenLists() {
        var random = new Random(7251);
        for (int length : List.of(0, 1, 2, 3, 17, 256, 257, 10001, 375000)) {
            var values = new ArrayList<Value>();
            for (int index = 0; index < length; index++) {
                values.add(new Value(random.nextInt(100), index));
            }
            var expected = new ArrayList<>(values);
            var comparator = Comparator.comparingInt(Value::key);
            expected.sort(comparator);
            var sort = new IncrementalSort<>(values, comparator);
            int slices = 1;
            while (!sort.advance()) {
                slices++;
                assertTrue(slices < 100000);
            }
            assertEquals(expected, values);
            assertTrue(sort.advance());
            if (length > 256) {
                assertTrue(slices > 1);
            }
        }
    }
}
