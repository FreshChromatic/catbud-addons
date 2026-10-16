package github.freshchromatic.catbud_addons.public_warps;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.Iterator;

final class MeasuredSceneBuild implements PreviewBuildQueue.Task<WarpSceneMesh> {

    private enum Stage {
        PALETTE, BLOCKS, SORT_ALLOCATION, SORT, ENTITIES, GROUP_ALLOCATION, GROUPS, GPU_ALLOCATION, GPU_UPLOAD,
        GPU_FINISH
    }

    private static final class Samples {

        private long count;
        private long total;
        private long maximum;
        private long overBudget;

        void add(long elapsed) {
            count++;
            total += elapsed;
            maximum = Math.max(maximum, elapsed);
            if (elapsed > 2_000_000) {
                overBudget++;
            }
        }
    }

    private final PreviewBuildQueue.Task<WarpSceneMesh> work;
    private final Field                                 geometry;
    private final Object                                build;
    private final BlockSnapshot                         snapshot;
    private final Field                                 paletteCursor;
    private final Field                                 blockCursor;
    private final Field                                 sorter;
    private final Field                                 sortCopied;
    private final Field                                 buildFaces;
    private final Field                                 entityCursor;
    private final Field                                 scene;
    private final Field                                 mesh;
    private final EnumMap<Stage, Samples>               samples = new EnumMap<>(Stage.class);
    private Field                                       current;
    private Field                                       faces;
    private Field                                       materials;

    MeasuredSceneBuild(PreviewBuildQueue.Task<WarpSceneMesh> work) throws Exception {
        this.work = work;
        geometry = field(work.getClass(), "geometry");
        build = field(work.getClass(), "build").get(work);
        snapshot = (BlockSnapshot) field(work.getClass(), "snapshot").get(work);
        paletteCursor = field(build.getClass(), "paletteCursor");
        blockCursor = field(build.getClass(), "blockCursor");
        sorter = field(build.getClass(), "sorter");
        buildFaces = field(build.getClass(), "faces");
        sortCopied = field(IncrementalSort.class, "copied");
        entityCursor = field(work.getClass(), "entityCursor");
        scene = field(work.getClass(), "scene");
        mesh = field(work.getClass(), "mesh");
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        var value = owner.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private Stage stage() {
        try {
            var builder = geometry.get(work);
            if (builder == null) {
                return cpuStage();
            }
            if (current == null) {
                current = field(builder.getClass(), "current");
                faces = field(builder.getClass(), "faces");
                materials = field(builder.getClass(), "materials");
            }
            if (current.get(builder) != null) {
                return Stage.GPU_UPLOAD;
            }
            boolean more = faces.get(builder) != null || ((Iterator<?>) materials.get(builder)).hasNext();
            return more ? Stage.GPU_ALLOCATION : Stage.GPU_FINISH;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot classify preview preparation", failure);
        }
    }

    private Stage cpuStage() throws Exception {
        if (paletteCursor.getInt(build) < snapshot.palette.size()) {
            return Stage.PALETTE;
        }
        if (blockCursor.getInt(build) < snapshot.blocks.length) {
            return Stage.BLOCKS;
        }
        var sorting = sorter.get(build);
        if (sorting == null) {
            return Stage.SORT_ALLOCATION;
        }
        if (sortCopied.getInt(sorting) < ((java.util.List<?>) buildFaces.get(build)).size()) {
            return Stage.SORT;
        }
        if (entityCursor.getInt(work) < snapshot.entities.size()) {
            return Stage.ENTITIES;
        }
        if (scene.get(work) == null) {
            return Stage.GROUP_ALLOCATION;
        }
        return mesh.get(work) == null ? Stage.GROUPS : Stage.GPU_FINISH;
    }

    @Override
    public boolean advance() {
        var stage = stage();
        long start = System.nanoTime();
        boolean complete = work.advance();
        long elapsed = System.nanoTime() - start;
        samples.computeIfAbsent(stage, key -> new Samples()).add(elapsed);
        return complete;
    }

    @Override
    public WarpSceneMesh result() {
        return work.result();
    }

    @Override
    public void cancel() {
        work.cancel();
    }

    String report() {
        var text = new StringBuilder("Step wall times classified by starting state; transition steps may include"
            + " subsequent work. Classification overhead excluded, driver/GC"
            + " interruptions included; not GPU timers.\n");
        for (var entry : samples.entrySet()) {
            var value = entry.getValue();
            text.append(entry.getKey()).append(" steps=").append(value.count).append(" totalMs=").append(value.total
                / 1_000_000.0).append(" maximumMs=").append(value.maximum / 1_000_000.0).append(" over2ms=").append(
                    value.overBudget).append('\n');
        }
        return text.toString();
    }
}
