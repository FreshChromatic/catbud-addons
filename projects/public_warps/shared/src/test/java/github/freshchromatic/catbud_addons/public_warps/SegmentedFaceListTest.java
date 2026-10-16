package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;

class SegmentedFaceListTest {

    @Test
    void denseSegmentedStoragePreservesStableSortAndBoundaryAccess() {
        var actual = new SegmentedFaceList();
        var expected = new ArrayList<WarpSceneMesh.Face>();
        var atlas = Identifier.withDefaultNamespace("textures/atlas/blocks.png");
        for (int index = 0; index < 375000; index++) {
            var face = new WarpSceneMesh.Face(new float[20], -1, atlas, index % 19);
            actual.add(face);
            expected.add(face);
        }
        var order = Comparator.comparingDouble(WarpSceneMesh.Face::depth);
        expected.sort(order);
        var sorter = new IncrementalSort<>(actual, order);
        while (!sorter.advance()) {
        }
        assertEquals(expected, actual);
        for (int index : new int[] { 0, 255, 256, 257, 374999 }) {
            assertSame(expected.get(index), actual.get(index));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> actual.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> actual.get(actual.size()));
        assertThrows(IndexOutOfBoundsException.class, () -> actual.set(actual.size(), null));
    }
}
