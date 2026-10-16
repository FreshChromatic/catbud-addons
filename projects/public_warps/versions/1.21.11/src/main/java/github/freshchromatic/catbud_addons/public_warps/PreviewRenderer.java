package github.freshchromatic.catbud_addons.public_warps;

import github.freshchromatic.catbud_addons.public_warps.WarpSceneMesh.Face;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class PreviewRenderer {

    private static final Direction[] FLUID_DIRECTIONS = { Direction.UP, Direction.EAST, Direction.SOUTH, Direction.WEST,
        Direction.NORTH, Direction.DOWN };

    private final PreviewCache<BlockSnapshot, WarpSceneMesh> meshes = new PreviewCache<>(64, 192L * 1024 * 1024,
        (snapshot, mesh) -> snapshot.estimatedBytes() + mesh.estimatedBytes(), this::releaseMesh);

    private final PreviewBuildQueue<BlockSnapshot, WarpSceneMesh> builds = new PreviewBuildQueue<>(System::nanoTime,
        meshes::put);

    public void beginFrame() {
        builds.beginFrame();
    }

    public void endFrame() {
        builds.endFrame();
    }

    private Object                modelSet;
    private final Set<String>     failedEntityTypes = new HashSet<>();
    private final PreviewEntities entities          = new PreviewEntities();

    private void releaseMesh(BlockSnapshot snapshot, WarpSceneMesh mesh) {
        WarpSceneRenderer.removeScene(mesh);
    }

    public void clear() {
        builds.clear();
        meshes.clear();
        WarpSceneRenderer.clearScenes();
        modelSet = null;
    }

    private static float faceBrightness(Direction direction) {
        if (direction == Direction.UP) {
            return 1f;
        }
        return direction == Direction.EAST ? .8f : .65f;
    }

    private static List<BlockModelPart> modelParts(BlockState state) {
        var parts = new ArrayList<BlockModelPart>();
        Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state).collectParts(RandomSource
            .create(0), parts);
        return parts;
    }

    private static float[][][] fluidPlanes(float fluidHeight) {
        return new float[][][] { { { 0, fluidHeight, 0 }, { 0, fluidHeight, 1 }, { 1, fluidHeight, 1 }, { 1,
            fluidHeight, 0 } }, { { 1, 0, 0 }, { 1, fluidHeight, 0 }, { 1, fluidHeight, 1 }, { 1, 0, 1 } }, { { 0, 0,
                1 }, { 1, 0, 1 }, { 1, fluidHeight, 1 }, { 0, fluidHeight, 1 } }, { { 0, 0, 0 }, { 0, 0, 1 }, { 0,
                    fluidHeight, 1 }, { 0, fluidHeight, 0 } }, { { 0, 0, 0 }, { 0, fluidHeight, 0 }, { 1, fluidHeight,
                        0 }, { 1, 0, 0 } }, { { 0, 0, 0 }, { 1, 0, 0 }, { 1, 0, 1 }, { 0, 0, 1 } } };
    }

    private final class SceneBuild implements PreviewBuildQueue.Task<WarpSceneMesh> {

        private final BlockSnapshot                                                      snapshot;
        private final MeshBuild                                                          build;
        private final List<net.minecraft.client.renderer.entity.state.EntityRenderState> states = new ArrayList<>();
        private int                                                                      entityCursor;
        private WarpSceneMesh.Builder                                                    scene;
        private WarpSceneMesh                                                            mesh;
        private RetainedGeometry.Builder                                                 geometry;
        private boolean                                                                  published;

        private SceneBuild(BlockSnapshot snapshot) {
            this.snapshot = snapshot;
            build = new MeshBuild(snapshot);
        }

        @Override
        public boolean advance() {
            if (!build.advance()) {
                return false;
            }
            if (entityCursor < snapshot.entities.size()) {
                var saved = snapshot.entities.get(entityCursor++);
                try {
                    var entity = entities.create(saved);
                    if (entity != null) {
                        states.add(entities.state(entity));
                    }
                } catch (RuntimeException failure) {
                    if (failedEntityTypes.add(saved.type())) {
                        org.slf4j.LoggerFactory.getLogger("Catbud Public Warps").warn(
                            "Cannot extract preview entity {}", saved.type(), failure);
                    }
                }
                return false;
            }
            if (scene == null) {
                scene = new WarpSceneMesh.Builder(build.finish(), states);
            }
            if (mesh == null) {
                if (!scene.advance()) {
                    return false;
                }
                mesh = scene.result();
                geometry = new RetainedGeometry.Builder(mesh);
                return false;
            }
            return geometry.advance();
        }

        @Override
        public WarpSceneMesh result() {
            if (!published) {
                WarpSceneRenderer.retainGeometry(mesh, geometry.result());
                published = true;
            }
            return mesh;
        }

        @Override
        public void cancel() {
            if (geometry != null && !published) {
                geometry.cancel();
            }
        }
    }

    private List<Face> build(BlockSnapshot snapshot) {
        var build = new MeshBuild(snapshot);
        while (!build.advance()) {
        }
        return build.finish();
    }

    private final class MeshBuild {

        private final BlockSnapshot                         s;
        private final Minecraft                             mc                 = Minecraft.getInstance();
        private final List<BlockState>                      palette            = new ArrayList<>();
        private final Map<BlockState, List<BlockModelPart>> modelParts         = new HashMap<>();
        private final float[][][]                           fullFluidPlanes    = fluidPlanes(1f);
        private final float[][][]                           surfaceFluidPlanes = fluidPlanes(0.875f);
        private final List<Face>                            faces              = new SegmentedFaceList();
        private int                                         paletteCursor;
        private int                                         blockCursor;
        private IncrementalSort<Face>                       sorter;

        private MeshBuild(BlockSnapshot snapshot) {
            s = snapshot;
        }

        private boolean advance() {
            if (paletteCursor < s.palette.size()) {
                try {
                    palette.add(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s.palette.get(paletteCursor),
                        false).blockState());
                } catch (Exception failure) {
                    palette.add(Blocks.BARRIER.defaultBlockState());
                }
                paletteCursor++;
                return false;
            }
            for (int count = 0; count < 32 && blockCursor < s.blocks.length; count++) {
                int x = blockCursor % s.width;
                int z = (blockCursor / s.width) % s.depth;
                int y = blockCursor / (s.width * s.depth);
                appendBlock(x, y, z);
                blockCursor++;
            }
            if (blockCursor != s.blocks.length) {
                return false;
            }
            if (sorter == null) {
                sorter = new IncrementalSort<>(faces, Comparator.comparingDouble(Face::depth));
                return false;
            }
            return sorter.advance();
        }

        private List<Face> finish() {

            return faces;
        }

        private void appendBlock(int x, int y, int z) {
            BlockState state = palette.get(s.blocks[s.index(x, y, z)]);
            if (state.isAir()) {
                return;
            }
            if (state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.LAVA) {
                var sprite = ((net.minecraft.client.renderer.texture.TextureAtlas) mc.getTextureManager().getTexture(
                    Identifier.withDefaultNamespace("textures/atlas/blocks.png"))).getSprite(Identifier
                        .withDefaultNamespace(state.getBlock() == Blocks.WATER ? "block/water_still"
                            : "block/lava_still"));
                boolean fluidAbove = y + 1 < s.height && palette.get(s.blocks[s.index(x, y + 1, z)]).getBlock() == state
                    .getBlock();
                float fluidHeight = fluidAbove ? 1f : 0.875f;
                float[][][] planes = fluidHeight == 1f ? fullFluidPlanes : surfaceFluidPlanes;
                for (int planeIndex = 0; planeIndex < planes.length; planeIndex++) {
                    Direction fluidDirection = FLUID_DIRECTIONS[planeIndex];
                    int nx = x + fluidDirection.getStepX();
                    int ny = y + fluidDirection.getStepY();
                    int nz = z + fluidDirection.getStepZ();
                    if (nx >= 0 && nx < s.width && ny >= 0 && ny < s.height && nz >= 0 && nz < s.depth && palette.get(
                        s.blocks[s.index(nx, ny, nz)]).getBlock() == state.getBlock()) {
                        boolean neighborFullHeight = ny + 1 < s.height && palette.get(s.blocks[s.index(nx, ny + 1, nz)])
                            .getBlock() == state.getBlock();
                        if (fluidDirection.getStepY() != 0 || !fluidAbove || neighborFullHeight) {
                            continue;
                        }
                    }
                    float[][] plane = planes[planeIndex];
                    float[] v = new float[20];
                    float depth = 0;
                    for (int i = 0; i < 4; i++) {
                        float px = x + plane[i][0], py = y + plane[i][1], pz = z + plane[i][2];
                        v[i * 5] = px;
                        v[i * 5 + 1] = py;
                        v[i * 5 + 2] = pz;
                        v[i * 5 + 3] = (i == 0 || i == 1) ? sprite.getU0() : sprite.getU1();
                        v[i * 5 + 4] = (i == 0 || i == 3) ? sprite.getV0() : sprite.getV1();
                        depth += px + py + pz;
                    }
                    faces.add(new Face(v, state.getBlock() == Blocks.WATER ? 0xCC3F76E4 : 0xFFFFDD99, sprite
                        .atlasLocation(), depth / 4));
                }
                return;
            }
            var parts = modelParts.computeIfAbsent(state, PreviewRenderer::modelParts);
            for (var part : parts) {
                for (Direction direction : new Direction[] { null, Direction.UP, Direction.DOWN, Direction.EAST,
                    Direction.WEST, Direction.SOUTH, Direction.NORTH }) {
                    if (direction != null) {
                        int nx = x + direction.getStepX(), ny = y + direction.getStepY(), nz = z + direction.getStepZ();
                        if (nx >= 0 && nx < s.width && ny >= 0 && ny < s.height && nz >= 0 && nz < s.depth && palette
                            .get(s.blocks[s.index(nx, ny, nz)]).isSolidRender()) {
                            continue;
                        }
                    }
                    for (BakedQuad q : part.getQuads(direction)) {
                        float[] v = new float[20];
                        float depth = 0;
                        for (int i = 0; i < 4; i++) {
                            var p = q.position(i);
                            float px = x + p.x(), py = y + p.y(), pz = z + p.z();
                            v[i * 5] = px;
                            v[i * 5 + 1] = py;
                            v[i * 5 + 2] = pz;
                            v[i * 5 + 3] = UVPair.unpackU(q.packedUV(i));
                            v[i * 5 + 4] = UVPair.unpackV(q.packedUV(i));
                            depth += px + py + pz;
                        }
                        int tint = q.isTinted() ? 0x91BD59 : 0xFFFFFF;
                        float light = faceBrightness(q.direction());
                        int r = (int) (((tint >> 16) & 255) * light), g = (int) (((tint >> 8) & 255) * light),
                            b = (int) ((tint & 255) * light);
                        faces.add(new Face(v, (net.minecraft.client.renderer.ItemBlockRenderTypes.getChunkRenderType(
                            state) == net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT ? 0xFE000000
                                : 0xFF000000) | (r << 16) | (g << 8) | b, q.sprite().atlasLocation(), depth / 4));
                    }
                }
            }
        }
    }

    public void draw(GuiGraphics g, BlockSnapshot s, int x, int y, int w, int h) {
        draw(g, s, x, y, w, h, 0, 0);
    }

    public void draw(GuiGraphics g, BlockSnapshot s, int x, int y, int w, int h, double angle, double inside) {
        draw(g, s, x, y, w, h, angle, inside, 0, 1);
    }

    public void draw(GuiGraphics g, BlockSnapshot s, int x, int y, int w, int h, double angle, double inside, double pitch, double zoom) {
        draw(g, s, x, y, w, h, angle, inside, pitch, zoom, 0, 0, 0);
    }

    public void draw(GuiGraphics g, BlockSnapshot s, int x, int y, int w, int h, double angle, double inside, double pitch, double zoom, double offsetX, double offsetY, double offsetZ) {
        if (w <= 0 || h <= 0) {
            return;
        }
        var mc = Minecraft.getInstance();
        Object current = mc.getModelManager().getBlockModelShaper();
        if (current != modelSet) {
            clear();
            modelSet = current;
        }
        WarpSceneMesh mesh = meshes.get(s);
        if (mesh != null && !WarpSceneRenderer.hasGeometry(mesh)) {
            meshes.remove(s);
            mesh = null;
        }
        if (mesh == null) {
            builds.request(s, () -> new SceneBuild(s));
            String message = net.minecraft.network.chat.Component.translatable("warps.preview_loading").getString();
            g.drawString(mc.font, message, x + Math.max(0, (w - mc.font.width(message)) / 2), y + h / 2, 0xFFAAAAAA);
            return;
        }
        var bounds = new ScreenRectangle(x, y, w, h);
        var clip = g.scissorStack.peek();
        if (clip != null) {
            bounds = bounds.intersection(clip);
            if (bounds == null) {
                return;
            }
        }
        g.nextStratum();
        g.guiRenderState.submitPicturesInPictureState(new WarpSceneState(mesh, s, x, y, x + w, y + h, angle, pitch,
            zoom, inside, offsetX, offsetY, offsetZ, bounds));
        g.nextStratum();
    }
}
