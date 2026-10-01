package github.freshchromatic.catbud_addons.magic_tower;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/** Capture route geometry during extraction, then submit it before the entity draw pass. */
final class RouteRenderContext {
    private record Line(List<Vec3> points, int color, float width) {}
    private final Vec3 camera;
    private final List<Line> lines = new ArrayList<>();

    private RouteRenderContext(Vec3 camera) { this.camera = camera; }
    Vec3 camera() { return camera; }
    void draw(List<Vec3> points, int color, float width) {
        lines.add(new Line(List.copyOf(points), color, width));
    }
    static void register(Consumer<RouteRenderContext> renderer) {
        var frame = new AtomicReference<RouteRenderContext>();
        WorldRenderEvents.END_EXTRACTION.register(context -> {
            var routes = new RouteRenderContext(context.worldState().cameraRenderState.pos);
            renderer.accept(routes);
            frame.set(routes);
        });
        WorldRenderEvents.BEFORE_ENTITIES.register(context -> {
            var routes = frame.getAndSet(null);
            if (routes == null) return;
            var pose = context.matrices();
            pose.pushPose();
            pose.translate(-routes.camera.x, -routes.camera.y, -routes.camera.z);
            for (Line line : routes.lines) {
                context.commandQueue().submitCustomGeometry(pose, RenderTypes.lines(), (matrix, vertices) -> {
                    for (int i = 1; i < line.points.size(); i++) {
                        Vec3 from = line.points.get(i - 1), to = line.points.get(i);
                        Vec3 normal = to.subtract(from).normalize();
                        vertices.addVertex(matrix, (float) from.x, (float) from.y, (float) from.z)
                            .setColor(line.color).setNormal(matrix, (float) normal.x, (float) normal.y, (float) normal.z)
                            .setLineWidth(line.width);
                        vertices.addVertex(matrix, (float) to.x, (float) to.y, (float) to.z)
                            .setColor(line.color).setNormal(matrix, (float) normal.x, (float) normal.y, (float) normal.z)
                            .setLineWidth(line.width);
                    }
                });
            }
            pose.popPose();
        });
    }
}
