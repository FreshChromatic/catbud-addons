package github.freshchromatic.catbud_addons.public_warps;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

final class SegmentedFaceList extends AbstractList<WarpSceneMesh.Face> implements RandomAccess {

    private static final int                 SEGMENT_SIZE = 256;
    private final List<WarpSceneMesh.Face[]> segments     = new ArrayList<>();
    private int                              size;

    @Override
    public int size() {
        return size;
    }

    @Override
    public WarpSceneMesh.Face get(int index) {
        Objects.checkIndex(index, size);
        return segments.get(index / SEGMENT_SIZE)[index % SEGMENT_SIZE];
    }

    @Override
    public WarpSceneMesh.Face set(int index, WarpSceneMesh.Face face) {
        Objects.checkIndex(index, size);
        WarpSceneMesh.Face[] segment = segments.get(index / SEGMENT_SIZE);
        int offset = index % SEGMENT_SIZE;
        WarpSceneMesh.Face previous = segment[offset];
        segment[offset] = face;
        return previous;
    }

    @Override
    public boolean add(WarpSceneMesh.Face face) {
        if (size % SEGMENT_SIZE == 0) {
            segments.add(new WarpSceneMesh.Face[SEGMENT_SIZE]);
        }
        segments.get(size / SEGMENT_SIZE)[size % SEGMENT_SIZE] = face;
        size++;
        modCount++;
        return true;
    }
}
