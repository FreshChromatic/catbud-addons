package github.freshchromatic.catbud_addons.public_warps;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class RetainedGeometry implements AutoCloseable {

    private static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder(
        RenderPipelines.ENTITY_SNIPPET).withLocation(Identifier.fromNamespaceAndPath("catbud_public_warps",
            "pipeline/geometry")).withVertexShader(Identifier.fromNamespaceAndPath("catbud_public_warps",
                "core/warp_geometry")).withShaderDefine("ALPHA_CUTOUT", .1f).withShaderDefine("PER_FACE_LIGHTING")
        .withSampler("Sampler1").withoutBlend().withColorWrite(RenderPipelines.ENTITY_CUTOUT.isWriteColor(),
            RenderPipelines.ENTITY_CUTOUT.isWriteAlpha()).withDepthWrite(RenderPipelines.ENTITY_CUTOUT.isWriteDepth())
        .withDepthTestFunction(RenderPipelines.ENTITY_CUTOUT.getDepthTestFunction()).withCull(false).build());

    private final RenderType  type;
    private final RenderSetup setup;
    private final GpuBuffer   vertices;
    private final int         indexCount;
    private ByteBufferBuilder pendingAllocation;
    private BufferBuilder     pendingVertices;
    private int               firstPendingFace;
    private long              uploadedBytes;

    public static RenderPipeline pipeline() {
        return PIPELINE;
    }

    private RetainedGeometry(Identifier atlas, int faceCount) {
        type = RenderTypes.entityCutout(atlas);
        setup = RenderSetup.builder(PIPELINE).withTexture("Sampler0", atlas).useLightmap().useOverlay()
            .createRenderSetup();
        indexCount = Math.multiplyExact(faceCount, 6);
        vertices = RenderSystem.getDevice().createBuffer(() -> "Catbud retained geometry", GpuBuffer.USAGE_VERTEX
            | GpuBuffer.USAGE_COPY_DST, (long) faceCount * 4 * type.format().getVertexSize());
    }

    private int upload(List<WarpSceneMesh.Face> faces, int cursor) {
        if (pendingVertices == null) {
            pendingAllocation = new ByteBufferBuilder(2048 * 4 * type.format().getVertexSize());
            pendingVertices = new BufferBuilder(pendingAllocation, type.mode(), type.format());
            firstPendingFace = cursor;
        }
        int limit = Math.min(faces.size(), cursor + 256);
        for (int index = cursor; index < limit; index++) {
            var face = faces.get(index);
            float[] vertex = face.vertex();
            for (int corner = 0; corner < 4; corner++) {
                pendingVertices.addVertex(vertex[corner * 5], vertex[corner * 5 + 1], vertex[corner * 5 + 2]).setColor(
                    face.color()).setUv(vertex[corner * 5 + 3], vertex[corner * 5 + 4]).setOverlay(
                        OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(face.normalX(), face.normalY(), face
                            .normalZ());
            }
        }
        if (limit - firstPendingFace >= 2048 || limit == faces.size()) {
            flushUpload();
        }
        return limit;
    }

    private void flushUpload() {
        try (var mesh = pendingVertices.buildOrThrow()) {
            long offset = (long) firstPendingFace * 4 * type.format().getVertexSize();
            if (offset != uploadedBytes) {
                throw new IllegalStateException("Geometry upload is not contiguous");
            }
            int length = mesh.vertexBuffer().remaining();
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToBuffer(vertices.slice(offset, length), mesh.vertexBuffer());

            uploadedBytes += length;
        } finally {
            discardPendingUpload();
        }
    }

    private void discardPendingUpload() {
        if (pendingAllocation != null) {
            pendingAllocation.close();
            pendingAllocation = null;
            pendingVertices = null;
        }
    }

    public static final class Builder implements PreviewBuildQueue.Task<List<RetainedGeometry>> {

        private final Iterator<Map.Entry<Identifier, List<WarpSceneMesh.Face>>> materials;
        private final List<RetainedGeometry>                                    groups = new ArrayList<>();
        private List<WarpSceneMesh.Face>                                        faces;
        private RetainedGeometry                                                current;
        private int                                                             cursor;
        private boolean                                                         complete;

        public Builder(WarpSceneMesh mesh) {
            materials = mesh.opaque().entrySet().iterator();
        }

        @Override
        public boolean advance() {
            try {
                if (current != null) {
                    cursor = current.upload(faces, cursor);
                    if (cursor == faces.size()) {
                        current = null;
                        faces = null;
                    }
                    return false;
                }
                if (!materials.hasNext()) {
                    complete = true;
                    return true;
                }
                var material = materials.next();
                faces = material.getValue();
                current = new RetainedGeometry(material.getKey(), faces.size());
                groups.add(current);
                cursor = 0;
                return false;
            } catch (RuntimeException | Error failure) {
                cancel();
                throw failure;
            }
        }

        @Override
        public List<RetainedGeometry> result() {
            if (!complete) {
                throw new IllegalStateException("Geometry upload is incomplete");
            }
            for (var group : groups) {
                if (group.uploadedBytes != group.bytes()) {
                    throw new IllegalStateException("Geometry upload has missing vertices");
                }
            }
            return List.copyOf(groups);
        }

        @Override
        public void cancel() {
            groups.forEach(RetainedGeometry::close);
            groups.clear();
            current = null;
            faces = null;
        }
    }

    public long bytes() {
        return vertices.size();
    }

    public void draw(PoseStack pose) {
        var matrices = RenderSystem.getModelViewStack();
        matrices.pushMatrix();
        try {
            matrices.mul(pose.last().pose());
            var indices = RenderSystem.getSequentialBuffer(type.mode());
            var indexBuffer = indices.getBuffer(indexCount);
            var transform = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrix(),
                new Vector4f(1), new Vector3f(), new Matrix4f());
            try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Catbud retained geometry", RenderSystem.outputColorTextureOverride, OptionalInt.empty(),
                RenderSystem.outputDepthTextureOverride, OptionalDouble.empty())) {
                pass.setPipeline(PIPELINE);
                var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
                if (scissor.enabled()) {
                    pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
                }
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transform);
                for (var entry : setup.getTextures().entrySet()) {
                    pass.bindTexture(entry.getKey(), entry.getValue().textureView(), entry.getValue().sampler());
                }
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(0, 0, indexCount, 1);
            }
        } finally {
            matrices.popMatrix();
        }
    }

    @Override
    public void close() {
        try {
            vertices.close();
        } finally {
            discardPendingUpload();
        }
    }
}
