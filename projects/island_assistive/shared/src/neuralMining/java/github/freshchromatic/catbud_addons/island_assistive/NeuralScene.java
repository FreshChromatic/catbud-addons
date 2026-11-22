package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.island_assistive.AimTargetTransfer.Box;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Rotation;
import github.freshchromatic.catbud_addons.island_assistive.AimTrajectory.Vector;
import github.freshchromatic.catbud_addons.island_assistive.NeuralObservation.AngularVelocity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

final class NeuralScene {

    record Continuity(List<Box> boxes, BlockState block, long shapeChanged) {}

    private List<Box>  previousBoxes;
    private BlockState previousBlock;
    private Vector     previousEye;
    private long       shapeChanged;
    private long       previousTime;
    private Box        region;

    Box region() {
        return region;
    }

    Continuity continuity() {
        return new Continuity(previousBoxes, previousBlock, shapeChanged);
    }

    void resume(Continuity continuity) {
        reset();
        previousBoxes = continuity.boxes();
        previousBlock = continuity.block();
        shapeChanged = continuity.shapeChanged();
    }

    void reset() {
        previousBoxes = null;
        previousBlock = null;
        previousEye = null;
        shapeChanged = previousTime = 0;
    }

    NeuralObservation.State observe(Minecraft client, BlockPos target, AngularVelocity velocity, long now) {
        var eyePosition = client.player.getEyePosition();
        var eye = new Vector(eyePosition.x, eyePosition.y, eyePosition.z);
        var block = client.level.getBlockState(target);
        var boxes = block.getShape(client.level, target).toAabbs().stream().map(box -> absolute(box, target)).toList();
        if (block != previousBlock || !boxes.equals(previousBoxes)) {
            shapeChanged = now;
            previousBoxes = boxes;
            previousBlock = block;
        }
        var eyeVelocity = previousTime == 0 ? new Vector(0, 0, 0)
            : eye.subtract(previousEye).scale(1e9 / (now - previousTime));
        previousEye = eye;
        previousTime = now;
        var rotation = new Rotation(client.player.getYRot(), client.player.getXRot());
        return new NeuralObservation.State(eye, rotation, boxes, client.player.blockInteractionRange(), velocity,
            eyeVelocity, (now - shapeChanged) / 1e9, occluders(client, target, eye));
    }

    private List<Box> occluders(Minecraft client, BlockPos target, Vector eye) {
        var minimum = BlockPos.containing(Math.min(eye.x(), target.getX()) - 1, Math.min(eye.y(), target.getY()) - 1,
            Math.min(eye.z(), target.getZ()) - 1);
        var maximum = BlockPos.containing(Math.max(eye.x(), target.getX() + 1) + 1, Math.max(eye.y(), target.getY() + 1)
            + 1, Math.max(eye.z(), target.getZ() + 1) + 1);
        region = new Box(new Vector(minimum.getX(), minimum.getY(), minimum.getZ()), new Vector(maximum.getX() + 1,
            maximum.getY() + 1, maximum.getZ() + 1));
        if (maximum.getX() - minimum.getX() > 12 || maximum.getY() - minimum.getY() > 12 || maximum.getZ() - minimum
            .getZ() > 12) {
            return null;
        }
        var boxes = new ArrayList<Box>();
        for (var position : BlockPos.betweenClosed(minimum, maximum)) {
            if (!client.level.hasChunkAt(position)) {
                return null;
            }
            if (position.equals(target)) {
                continue;
            }
            for (var box : client.level.getBlockState(position).getShape(client.level, position).toAabbs()) {
                boxes.add(absolute(box, position));
            }
        }
        return List.copyOf(boxes);
    }

    private static Box absolute(AABB box, BlockPos position) {
        return new Box(new Vector(box.minX + position.getX(), box.minY + position.getY(), box.minZ + position.getZ()),
            new Vector(box.maxX + position.getX(), box.maxY + position.getY(), box.maxZ + position.getZ()));
    }
}
