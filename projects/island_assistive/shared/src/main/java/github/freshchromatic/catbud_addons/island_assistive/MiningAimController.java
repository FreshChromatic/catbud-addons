package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.AimTargetTransfer.Box;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Context;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Rotation;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Vector;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class MiningAimController {

    private final Minecraft           client;
    private final MiningMotionLibrary library = MiningMotionLibrary.instance();

    private BlockPos        target;
    private BlockState      block;
    private VariedAimMotion motion;
    private Vec3            reference;
    private long            seed;
    private long            revision;
    private long            lastFrame;
    private double          speed = 180;

    MiningAimController(Minecraft client) {
        this.client = client;
    }

    void begin(BlockPos target) {
        reset();
        this.target = target.immutable();
        seed = UUID.randomUUID().getMostSignificantBits();
    }

    void look(Vec3 reference, double speed) {
        this.reference = reference;
        this.speed = speed;
    }

    void clearAim() {
        reference = null;
    }

    void reset() {
        target = null;
        block = null;
        motion = null;
        reference = null;
        revision = lastFrame = 0;
        speed = 180;
    }

    MiningLookMotion.Rotation frame(long now) {
        if (speed != 180) {
            motion = null;
            lastFrame = 0;
            return null;
        }
        var currentBlock = client.level.getBlockState(target);
        if (currentBlock != block) {
            block = currentBlock;
            revision++;
        }
        var shape = block.getShape(client.level, target).toAabbs();
        var boxes = shape.stream().map(box -> new Box(new Vector(box.minX + target.getX(), box.minY + target.getY(),
            box.minZ + target.getZ()), new Vector(box.maxX + target.getX(), box.maxY + target.getY(), box.maxZ + target
                .getZ()))).toList();
        String family = "air";
        if (shape.size() > 1) {
            family = "multi";
        } else if (!shape.isEmpty()) {
            var box = shape.getFirst();
            if (Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) >= .5) {
                family = "solid";
            } else {
                family = box.getYsize() < .1 ? "horizontal_thin" : "side_thin";
            }
        }
        if (motion == null) {
            motion = new VariedAimMotion(new Rotation(client.player.getYRot(), client.player.getXRot()), seed, library
                .profiles(), library.targets(), this::visible);
        }
        var eye = client.player.getEyePosition();
        var origin = new Vector(eye.x, eye.y, eye.z);
        var aimReference = reference == null ? null : new Vector(reference.x, reference.y, reference.z);
        if (aimReference != null && (!visible(aimReference) || !VariedAimMotion.intersects(origin, Rotation.from(
            aimReference.subtract(origin), motion.tracker().rotation().yaw()), boxes, client.player
                .blockInteractionRange()))) {
            aimReference = null;
        }
        var context = new Context("mining", family, origin, motion.tracker().rotation(), aimReference);
        double seconds = lastFrame == 0 ? 0 : Math.clamp((now - lastFrame) / 1e9, 0, .1);
        lastFrame = now;
        var rotation = motion.advance(context, boxes, client.player.blockInteractionRange(), revision, seconds);
        return new MiningLookMotion.Rotation((float) rotation.yaw(), (float) rotation.pitch());
    }

    private boolean visible(Vector point) {
        var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), new Vec3(point.x(), point.y(), point
            .z()), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }
}
