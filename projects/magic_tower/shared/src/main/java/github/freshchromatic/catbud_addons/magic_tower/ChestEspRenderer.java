package github.freshchromatic.catbud_addons.magic_tower;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import github.freshchromatic.catbud_addons.catbud_core.HudGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

/** Projects a three-dimensional chest wireframe onto the HUD so walls cannot hide it. */
final class ChestEspRenderer {
    private static final int[][] EDGES = {
        {0, 1}, {1, 3}, {3, 2}, {2, 0},
        {4, 5}, {5, 7}, {7, 6}, {6, 4},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    private ChestEspRenderer() {}

    static void render(HudGraphics graphics, Minecraft client, TowerSession session) {
        if (!session.isActive() || client.level == null || client.player == null) return;
        var settings = MagicTowerSettings.values();
        if (!settings.bool("esp.enabled")) return;
        Camera camera = ClientPlatform.camera(client);
        Vec3 eye = ClientPlatform.position(camera);
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        double yaw = Math.toRadians(ClientPlatform.yRot(camera));
        double pitch = Math.toRadians(ClientPlatform.xRot(camera));
        Set<BlockPos> chests = session.targets().chests();
        Set<BlockPos> mushrooms = session.targets().mushrooms();
        Set<BlockPos> targets = new HashSet<>();
        if (settings.bool("esp.chest")) targets.addAll(chests);
        if (settings.bool("esp.mushroom")) targets.addAll(mushrooms);
        for (BlockPos pos : targets) {
            if (pos.distToCenterSqr(eye) > Math.pow(settings.number("esp.distance"), 2)) continue;
            BlockState state = client.level.getBlockState(pos);
            double minX = pos.getX() + 0.05;
            double minY = pos.getY() + 0.05;
            double minZ = pos.getZ() + 0.05;
            double maxX = pos.getX() + 0.95;
            double maxY = pos.getY() + 0.95;
            double maxZ = pos.getZ() + 0.95;
            if (chests.contains(pos) && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos neighbor = pos.relative(ChestBlock.getConnectedDirection(state));
                if (chests.contains(neighbor) && state.getValue(ChestBlock.TYPE) == ChestType.LEFT) continue;
                if (chests.contains(neighbor)) {
                    minX = Math.min(minX, neighbor.getX() + 0.05);
                    minZ = Math.min(minZ, neighbor.getZ() + 0.05);
                    maxX = Math.max(maxX, neighbor.getX() + 0.95);
                    maxZ = Math.max(maxZ, neighbor.getZ() + 0.95);
                }
            }
            int[][] points = new int[8][2];
            boolean visible = true;
            for (int corner = 0; corner < 8; corner++) {
                Vec3 vertex = new Vec3(
                    (corner & 1) == 0 ? minX : maxX,
                    (corner & 4) == 0 ? minY : maxY,
                    (corner & 2) == 0 ? minZ : maxZ);
                Vec3 delta = vertex.subtract(eye);
                double horizontalForward = -delta.x * Math.sin(yaw) + delta.z * Math.cos(yaw);
                double forward = horizontalForward * Math.cos(pitch) - delta.y * Math.sin(pitch);
                if (forward <= 0.05) { visible = false; break; }
                Vec3 screen = ClientPlatform.project(client, vertex);
                points[corner][0] = (int) Math.round((screen.x + 1) * width / 2);
                points[corner][1] = (int) Math.round((1 - screen.y) * height / 2);
            }
            if (!visible) continue;
            for (int[] edge : EDGES) {
                int[] a = points[edge[0]];
                int[] b = points[edge[1]];
                drawLine(graphics, a[0], a[1], b[0], b[1],
                    settings.color(mushrooms.contains(pos) ? "esp.color.mushroom" : "esp.color.chest", "esp.opacity"));
            }
        }
    }

    private static void drawLine(HudGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        int length = (int) Math.round(Math.hypot(dx, dy));
        if (length < 1) return;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x1, y1);
        graphics.pose().rotate((float) Math.atan2(dy, dx));
        int thickness = (int) Math.ceil(MagicTowerSettings.values().number("esp.width"));
        int alpha = color >>> 24;
        graphics.fill(0, -thickness / 2 - 1, length, (thickness + 1) / 2 + 1, (alpha * 2 / 3) << 24);
        graphics.fill(0, -thickness / 2, length, (thickness + 1) / 2, color);
        graphics.pose().popMatrix();
    }
}
