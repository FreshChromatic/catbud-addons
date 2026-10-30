package github.freshchromatic.catbud_addons.island_assistive;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

public final class MiningPositionPlanner {

    private final MiningAimPlanner aim;

    public MiningPositionPlanner(MiningAimPlanner aim) {
        this.aim = aim;
    }

    public Vec3 find(Minecraft client, BlockPos target, Vec3 anchor, double radius) {
        Vec3 current = client.player.position();
        Vec3 best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (double distance = 0.125; distance <= radius + 0.001; distance += 0.125) {
            for (int direction = 0; direction < 16; direction++) {
                double angle = direction * Math.PI / 8;
                var candidate = anchor.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                if (!safePath(client, target, current, candidate)) {
                    continue;
                }
                var eye = client.player.getEyePosition().add(candidate.subtract(current));
                if (aim.find(client, target, eye, null) == null) {
                    continue;
                }
                double cost = current.distanceToSqr(candidate);
                if (cost < bestDistance) {
                    bestDistance = cost;
                    best = candidate;
                }
            }
        }
        return best;
    }

    public boolean safePath(Minecraft client, BlockPos target, Vec3 start, Vec3 end) {
        if (Math.abs(end.y - start.y) > 0.1) {
            return false;
        }
        int steps = Math.max(1, (int) Math.ceil(start.distanceTo(end) / 0.05));
        for (int step = 0; step <= steps; step++) {
            var point = start.lerp(end, step / (double) steps);
            var box = client.player.getBoundingBox().move(point.subtract(client.player.position())).deflate(0.001);
            if (!client.level.noCollision(client.player, box)) {
                return false;
            }
            var feet = client.player.getBoundingBox().move(point.subtract(client.player.position()));
            var supportArea = new AABB(feet.minX, feet.minY - 0.05, feet.minZ, feet.maxX, feet.minY, feet.maxZ);
            boolean supported = false;
            for (var pos : BlockPos.betweenClosed(BlockPos.containing(supportArea.minX, supportArea.minY,
                supportArea.minZ), BlockPos.containing(supportArea.maxX, supportArea.maxY, supportArea.maxZ))) {
                if (pos.equals(target)) {
                    continue;
                }
                var shape = client.level.getBlockState(pos).getCollisionShape(client.level, pos);
                if (shape.toAabbs().stream().anyMatch(support -> support.move(pos).intersects(supportArea))) {
                    supported = true;
                    break;
                }
            }
            if (!supported) {
                return false;
            }
        }
        return true;
    }
}
