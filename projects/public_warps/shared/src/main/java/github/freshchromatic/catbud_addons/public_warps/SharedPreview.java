package github.freshchromatic.catbud_addons.public_warps;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public record SharedPreview(byte[] bytes, String digest) {

    public static final class LimitException extends IOException {

        public LimitException(String message) {
            super(message);
        }
    }

    public static SharedPreview from(BlockSnapshot local) throws Exception {
        local.validate();
        BlockSnapshot shared = new BlockSnapshot(local.width, local.height, local.depth, local.dimension);
        shared.version = local.version;
        shared.palette = new java.util.ArrayList<>(local.palette);
        shared.blocks = local.blocks.clone();
        shared.eyeX = local.eyeX;
        shared.eyeY = local.eyeY;
        shared.eyeZ = local.eyeZ;
        shared.viewYaw = 0;
        shared.viewPitch = 0;
        shared.entities = local.entities.stream().filter(entity -> !entity.type().equals("minecraft:player") && !entity
            .type().equals("minecraft:text_display")).sorted(Comparator.comparingDouble(entity -> distance(entity,
                local))).limit(64).map(SharedPreview::normalizedEntity).toList();
        shared.validate();
        byte[] json = shared.jsonBytes();
        if (json.length > 1048576) {
            throw new LimitException("Shared preview exceeds expanded limit");
        }
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(json);
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length > 65536) {
            throw new LimitException("Shared preview exceeds compressed limit");
        }
        return new SharedPreview(bytes, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private static double distance(BlockSnapshot.SnapshotEntity entity, BlockSnapshot snapshot) {
        double x = entity.x() - snapshot.eyeX;
        double y = entity.y() - snapshot.eyeY;
        double z = entity.z() - snapshot.eyeZ;
        return x * x + y * y + z * z;
    }

    private static BlockSnapshot.SnapshotEntity normalizedEntity(BlockSnapshot.SnapshotEntity entity) {
        Map<String, String> appearance = SharedAppearance.sanitize(entity.appearance());
        return new BlockSnapshot.SnapshotEntity(entity.type(), Math.round(entity.x() * 16) / 16d, Math.round(entity.y()
            * 16) / 16d, Math.round(entity.z() * 16) / 16d, Math.round(entity.yaw() / 5) * 5, Math.round(entity.pitch()
                / 5) * 5, null, null, appearance);
    }
}
