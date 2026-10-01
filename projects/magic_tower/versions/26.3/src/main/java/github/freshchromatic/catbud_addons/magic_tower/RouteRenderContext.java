package github.freshchromatic.catbud_addons.magic_tower;
import java.util.List;
import java.util.function.Consumer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
final class RouteRenderContext {
    private final LevelRenderContext context;
    private RouteRenderContext(LevelRenderContext context) { this.context = context; }
    static void register(Consumer<RouteRenderContext> renderer) { LevelRenderEvents.COLLECT_SUBMITS.register(c -> renderer.accept(new RouteRenderContext(c))); }
    Vec3 camera() { return context.levelState().cameraRenderState.pos; }
    void draw(List<Vec3> points, int color, float width) {
        PoseStack pose = context.poseStack();
        Vec3 camera = camera();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.lines(), (matrix, vertices) -> {
            for (int i = 1; i < points.size(); i++) {
                Vec3 from = points.get(i - 1), to = points.get(i), normal = to.subtract(from).normalize();
                vertices.addVertex(matrix, (float) from.x, (float) from.y, (float) from.z).setColor(color)
                    .setNormal(matrix, (float) normal.x, (float) normal.y, (float) normal.z).setLineWidth(width);
                vertices.addVertex(matrix, (float) to.x, (float) to.y, (float) to.z).setColor(color)
                    .setNormal(matrix, (float) normal.x, (float) normal.y, (float) normal.z).setLineWidth(width);
            }
        });
        pose.popPose();
    }
}
