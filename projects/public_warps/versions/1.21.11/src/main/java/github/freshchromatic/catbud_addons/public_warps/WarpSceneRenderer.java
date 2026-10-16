package github.freshchromatic.catbud_addons.public_warps;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.*;

/** Per-scene color/depth targets: static thumbnails reuse their texture instead of redrawing models. */
public final class WarpSceneRenderer extends PictureInPictureRenderer<WarpSceneState> {

    private record Key(WarpSceneMesh mesh, int width, int height, int scale) {

        long estimatedBytes() {
            return 16L * width * height * scale * scale;
        }
    }

    private final Map<Key, Cached>                           slots    = new LinkedHashMap<>(64, .75f, true);
    private final Map<WarpSceneMesh, List<RetainedGeometry>> geometry = new LinkedHashMap<>(64, .75f, true);
    private static WarpSceneRenderer                         instance;
    public static long                                       draws, cacheHits;

    public WarpSceneRenderer() {
        super(net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource());
        RetainedGeometry.pipeline();
        instance = this;
    }

    public static boolean hasGeometry(WarpSceneMesh mesh) {
        return instance != null && instance.geometry.get(mesh) != null;
    }

    public static void retainGeometry(WarpSceneMesh mesh, List<RetainedGeometry> groups) {
        if (instance == null) {
            groups.forEach(RetainedGeometry::close);
            throw new IllegalStateException("Preview renderer is unavailable");
        }
        var previous = instance.geometry.put(mesh, groups);
        if (previous != null) {
            previous.forEach(RetainedGeometry::close);
        }
    }

    public static void clearScenes() {
        if (instance != null) {
            for (var group : instance.geometry.values()) {
                group.forEach(RetainedGeometry::close);
            }
            instance.geometry.clear();
            for (var r : instance.slots.values()) {
                r.close();
            }
            instance.slots.clear();
        }
    }

    public static void trimScenes() {
        if (instance != null) {
            long geometryBytes = instance.geometry.values().stream().flatMap(List::stream).mapToLong(
                RetainedGeometry::bytes).sum();
            while (instance.geometry.size() > 64 || (geometryBytes > 128L * 1024 * 1024 && instance.geometry
                .size() > 1)) {
                var oldest = instance.geometry.entrySet().iterator();
                var entry = oldest.next();
                oldest.remove();
                for (var group : entry.getValue()) {
                    geometryBytes -= group.bytes();
                    group.close();
                }
            }
            long bytes = instance.slots.keySet().stream().mapToLong(Key::estimatedBytes).sum();
            while (instance.slots.size() > 64 || (bytes > 96L * 1024 * 1024 && instance.slots.size() > 1)) {
                var i = instance.slots.entrySet().iterator();
                var old = i.next();
                i.remove();
                bytes -= old.getKey().estimatedBytes();
                old.getValue().close();
            }
        }
    }

    public static void removeScene(WarpSceneMesh mesh) {
        if (instance == null) {
            return;
        }
        var retained = instance.geometry.remove(mesh);
        if (retained != null) {
            retained.forEach(RetainedGeometry::close);
        }
        var iterator = instance.slots.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().mesh() == mesh) {
                iterator.remove();
                entry.getValue().close();
            }
        }
    }

    public Class<WarpSceneState> getRenderStateClass() {
        return WarpSceneState.class;
    }

    @Override
    public void prepare(WarpSceneState state, net.minecraft.client.gui.render.state.GuiRenderState gui, int scale) {
        Key key = new Key(state.mesh(), state.x1() - state.x0(), state.y1() - state.y0(), scale);
        var slot = slots.computeIfAbsent(key, k -> new Cached());
        slot.prepare(state, gui, scale);
    }

    protected void renderToTexture(WarpSceneState s, PoseStack pose) {
        throw new UnsupportedOperationException();
    }

    protected String getTextureLabel() {
        return "catbud-warp-dispatcher";
    }

    @Override
    public void close() {
        clearScenes();
        super.close();
    }

    private static final class Cached extends PictureInPictureRenderer<WarpSceneState> {

        private final PerspectiveProjectionMatrixBuffer projection = new PerspectiveProjectionMatrixBuffer(
            "Catbud warp perspective");
        private WarpSceneState                          last;
        private long                                    renderedAt;

        Cached() {
            super(net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource());
        }

        public Class<WarpSceneState> getRenderStateClass() {
            return WarpSceneState.class;
        }

        protected boolean textureIsReadyToBlit(WarpSceneState s) {
            if (last == null) {
                return false;
            }
            boolean same = last.angle() == s.angle() && last.pitch() == s.pitch() && last.zoom() == s.zoom() && last
                .inside() == s.inside();
            boolean reuse = same || System.nanoTime() - renderedAt < 33_333_333L;
            if (reuse) {
                cacheHits++;
            }
            return reuse;
        }

        protected String getTextureLabel() {
            return "catbud-warp-scene";
        }

        protected void renderToTexture(WarpSceneState s, PoseStack pose) {

            var features = Minecraft.getInstance().gameRenderer.getFeatureRenderDispatcher();
            var collector = features.getSubmitNodeStorage();
            var camera = new SceneCamera(s.snapshot(), s.x1() - s.x0(), s.y1() - s.y0(), s.angle(), s.pitch(), s.zoom(),
                s.inside(), s.offsetX(), s.offsetY(), s.offsetZ());
            RenderSystem.setProjectionMatrix(projection.getBuffer(camera.projection), ProjectionType.PERSPECTIVE);
            pose.setIdentity();
            pose.mulPose(camera.view);
            Minecraft.getInstance().gameRenderer.getLighting().setupFor(
                com.mojang.blaze3d.platform.Lighting.Entry.ENTITY_IN_UI);
            var retained = instance.geometry.get(s.mesh());
            if (retained == null) {
                for (var group : s.mesh().opaque().entrySet()) {
                    submitFaces(pose, collector, group.getKey(), group.getValue(), false);
                }
            } else {
                for (var group : retained) {
                    group.draw(pose);
                }
            }
            var cam = new CameraRenderState();
            cam.orientation = camera.orientation;
            for (var e : s.mesh().entities()) {
                Minecraft.getInstance().getEntityRenderDispatcher().submit(e, cam, e.x, e.y, e.z, pose, collector);
            }
            for (var group : s.mesh().alpha().entrySet()) {
                submitFaces(pose, collector, group.getKey(), group.getValue(), true);
            }

            features.renderAllFeatures();
            last = s;
            renderedAt = System.nanoTime();
            draws++;
        }

        private void submitFaces(PoseStack pose, SubmitNodeCollector collector, net.minecraft.resources.Identifier atlas, List<WarpSceneMesh.Face> faces, boolean translucent) {
            collector.submitCustomGeometry(pose, translucent ? RenderTypes.entityTranslucent(atlas)
                : RenderTypes.entityCutout(atlas), (p, out) -> {
                    for (var f : faces) {
                        float[] v = f.vertex();

                        for (int i = 0; i < 4; i++) {
                            out.addVertex(p, v[i * 5], v[i * 5 + 1], v[i * 5 + 2]).setColor(f.color()).setUv(v[i * 5
                                + 3], v[i * 5 + 4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(
                                    p, f.normalX(), f.normalY(), f.normalZ());
                        }
                    }
                });
        }

        @Override
        public void close() {
            projection.close();
            super.close();
        }
    }
}
