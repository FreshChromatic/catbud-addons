package github.freshchromatic.catbud_addons.public_warps;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;

import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;

public final class SceneLighting implements AutoCloseable {

    private final GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Catbud scene lighting",
        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, Lighting.UBO_SIZE);

    public GpuBufferSlice forView(Matrix4fc view) {
        var directions = SceneLightDirections.forView(view);
        try (var stack = MemoryStack.stackPush()) {
            var data = Std140Builder.onStack(stack, Lighting.UBO_SIZE).putVec3(directions.first()).putVec3(directions
                .second()).get();
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToBuffer(buffer.slice(), data);
            encoder.submit();
        }
        return buffer.slice();
    }

    @Override
    public void close() {
        buffer.close();
    }
}
