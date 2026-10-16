package github.freshchromatic.catbud_addons.public_warps;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

public final class SnapshotCaptureJob {

    public static final int                SIZE            = 50;
    private static final int               BLOCKS_PER_TICK = 4096;
    private final ClientLevel              level;
    private final BlockPos                 minimum;
    private final BlockSnapshot            snapshot;
    private final Map<BlockState, Integer> palette         = new HashMap<>();
    private int                            cursor;

    public SnapshotCaptureJob(Minecraft client, BlockPos anchor) {
        if (client.level == null || client.player == null || !areaLoaded(client, anchor)) {
            throw new IllegalStateException("Snapshot area is not loaded");
        }
        level = client.level;
        minimum = anchor.offset(-25, -25, -25);
        snapshot = new BlockSnapshot(SIZE, SIZE, SIZE, ClientPlatform.dimension(client).toString());
        snapshot.eyeX = client.player.getX() - minimum.getX();
        snapshot.eyeY = client.player.getEyeY() - minimum.getY();
        snapshot.eyeZ = client.player.getZ() - minimum.getZ();
        snapshot.viewYaw = client.player.getYRot();
        snapshot.viewPitch = client.player.getXRot();
        BlockSnapshotCapture.captureEntities(client, minimum, snapshot);
    }

    public static boolean areaLoaded(Minecraft client, BlockPos anchor) {
        if (client.level == null) {
            return false;
        }
        int firstX = Math.floorDiv(anchor.getX() - 25, 16);
        int lastX = Math.floorDiv(anchor.getX() + 24, 16);
        int firstZ = Math.floorDiv(anchor.getZ() - 25, 16);
        int lastZ = Math.floorDiv(anchor.getZ() + 24, 16);
        for (int x = firstX; x <= lastX; x++) {
            for (int z = firstZ; z <= lastZ; z++) {
                if (!client.level.hasChunkAt(new BlockPos(x * 16, anchor.getY(), z * 16))) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean advance(Minecraft client) {
        if (client.level != level || client.player == null || !client.player.isAlive()) {
            throw new IllegalStateException("Snapshot world changed");
        }
        double x = client.player.getX() - minimum.getX();
        double y = client.player.getY() - minimum.getY();
        double z = client.player.getZ() - minimum.getZ();
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || z < 0 || z >= SIZE) {
            throw new IllegalStateException("Player left snapshot area");
        }
        int end = Math.min(snapshot.blocks.length, cursor + BLOCKS_PER_TICK);
        var position = new BlockPos.MutableBlockPos();
        while (cursor < end) {
            int blockX = cursor % SIZE;
            int blockZ = cursor / SIZE % SIZE;
            int blockY = cursor / (SIZE * SIZE);
            position.set(minimum.getX() + blockX, minimum.getY() + blockY, minimum.getZ() + blockZ);
            if (!level.hasChunkAt(position)) {
                throw new IllegalStateException("Snapshot chunk unloaded");
            }
            BlockState state = level.getBlockState(position);
            Integer index = palette.get(state);
            if (index == null) {
                index = snapshot.palette.size();
                palette.put(state, index);
                snapshot.palette.add(BlockSnapshotCapture.encodeState(state));
            }
            snapshot.blocks[cursor++] = index;
        }
        return cursor == snapshot.blocks.length;
    }

    public BlockSnapshot result() {
        if (cursor != snapshot.blocks.length) {
            throw new IllegalStateException("Snapshot is incomplete");
        }
        return snapshot;
    }
}
