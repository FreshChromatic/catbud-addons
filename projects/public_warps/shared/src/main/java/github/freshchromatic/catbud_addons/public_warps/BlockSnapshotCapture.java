package github.freshchromatic.catbud_addons.public_warps;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class BlockSnapshotCapture {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Catbud Public Warps");

    private BlockSnapshotCapture() {
    }

    public static boolean areaLoaded(Minecraft mc, BlockPos anchor) {
        return SnapshotCaptureJob.areaLoaded(mc, anchor);
    }

    public static BlockSnapshot capture(Minecraft mc, BlockPos anchor) {
        var job = new SnapshotCaptureJob(mc, anchor);
        while (!job.advance(mc)) {
            // Synchronous capture is reserved for the native verification harness.
        }
        return job.result();
    }

    static void captureEntities(Minecraft mc, BlockPos minimum, BlockSnapshot snapshot) {
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || entity.isRemoved()) {
                continue;
            }
            double x = entity.getX() - minimum.getX(), y = entity.getY() - minimum.getY(), z = entity.getZ() - minimum
                .getZ();
            if (x < 0 || x >= snapshot.width || y < 0 || y >= snapshot.height || z < 0 || z >= snapshot.depth) {
                continue;
            }
            boolean player = entity instanceof net.minecraft.world.entity.player.Player;
            try {
                snapshot.entities.add(new BlockSnapshot.SnapshotEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity
                    .getType()).toString(), x, y, z, entity.getYRot(), entity.getXRot(), player ? entity.getUUID()
                        .toString() : null, player ? entity.getName().getString() : null, EntityVisual.capture(
                            entity)));
            } catch (RuntimeException unsupported) {
                LOG.debug("Cannot capture preview entity {}", entity.getType(), unsupported);
            }
            if (snapshot.entities.size() == 256) {
                break;
            }
        }
    }

    public static String encodeState(BlockState state) {
        return net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state);
    }
}
