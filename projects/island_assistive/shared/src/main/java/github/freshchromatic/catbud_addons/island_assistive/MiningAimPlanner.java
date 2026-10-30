package github.freshchromatic.catbud_addons.island_assistive;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.ProjectileUtil;

public final class MiningAimPlanner {

    public Vec3 find(Minecraft client, BlockPos target, Vec3 eye, Vec3 previous) {
        if (previous != null && visible(client, target, eye, previous)) {
            return previous;
        }
        if (eye.distanceToSqr(client.player.getEyePosition()) < 1e-8) {
            var hit = client.player.pick(client.player.blockInteractionRange(), 1, false);
            if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && block.getBlockPos()
                .equals(target)) {
                return block.getLocation().lerp(Vec3.atCenterOf(target), 0.001);
            }
        }
        var shape = client.level.getBlockState(target).getShape(client.level, target);
        for (var box : shape.toAabbs()) {
            var center = box.getCenter().add(Vec3.atLowerCornerOf(target));
            if (visible(client, target, eye, center)) {
                return center;
            }
        }
        for (var box : shape.toAabbs()) {
            for (int axis = 0; axis < 3; axis++) {
                for (double face : new double[] { 0.02, 0.98 }) {
                    for (double first : new double[] { 0.5, 0.25, 0.75, 0.05, 0.95 }) {
                        for (double second : new double[] { 0.5, 0.25, 0.75, 0.05, 0.95 }) {
                            double x = axis == 0 ? face : first;
                            double y = switch (axis) {
                                case 0 -> first;
                                case 1 -> face;
                                default -> second;
                            };
                            double z = axis == 2 ? face : second;
                            var point = new Vec3(target.getX() + box.minX + box.getXsize() * x, target.getY() + box.minY
                                + box.getYsize() * y, target.getZ() + box.minZ + box.getZsize() * z);
                            if (!visible(client, target, eye, point)) {
                                continue;
                            }
                            return point;
                        }
                    }
                }
            }
        }
        return null;
    }

    public HitResult bodyHit(Minecraft client) {
        var player = client.player;
        double reach = player.blockInteractionRange();
        var block = player.pick(reach, 1, false);
        var eye = player.getEyePosition();
        var end = eye.add(player.getViewVector(1).scale(reach));
        double distance = block.getType() == HitResult.Type.MISS ? reach * reach
            : eye.distanceToSqr(block.getLocation());
        var entity = ProjectileUtil.getEntityHitResult(player, eye, end, player.getBoundingBox().expandTowards(end
            .subtract(eye)).inflate(1), candidate -> !candidate.isSpectator() && candidate.isPickable(), distance);
        return entity == null ? block : entity;
    }

    public boolean visible(Minecraft client, BlockPos target, Vec3 eye, Vec3 point) {
        double reach = client.player.blockInteractionRange();
        if (eye.distanceToSqr(point) > reach * reach) {
            return false;
        }
        var ray = client.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
            client.player));
        return ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(target);
    }

    public BlockHitResult crosshair(Minecraft client, BlockPos target) {
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || !hit
            .getBlockPos().equals(target)) {
            return null;
        }
        var ray = client.player.pick(client.player.blockInteractionRange(), 1, false);
        return ray instanceof BlockHitResult actual && actual.getType() == HitResult.Type.BLOCK && actual.getBlockPos()
            .equals(target) ? hit : null;
    }
}
