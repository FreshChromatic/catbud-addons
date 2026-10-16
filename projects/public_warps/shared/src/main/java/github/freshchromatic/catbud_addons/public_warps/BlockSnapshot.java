package github.freshchromatic.catbud_addons.public_warps;

import com.google.gson.Gson;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Wire format is independent of Minecraft's numeric state IDs and excludes block entity data. */
public final class BlockSnapshot {

    public static final int     MAX_COMPRESSED_BYTES = 4 * 1024 * 1024;
    public static final int     MAX_EXPANDED_BYTES   = 16 * 1024 * 1024;
    public int                  version              = 1, width = 10, height, depth = 10;
    public String               dimension;
    public List<String>         palette              = new ArrayList<>();
    public int[]                blocks;
    public List<SnapshotEntity> entities             = new ArrayList<>();
    public double               eyeX, eyeY, eyeZ;
    public float                viewYaw, viewPitch;

    public record SnapshotEntity(String type, double x, double y, double z, float yaw, float pitch, String playerUUID,
        String playerName, Map<String, String> appearance) {}

    public BlockSnapshot(int height, String dimension) {
        this.height = height;
        this.dimension = dimension;
        blocks = new int[100 * height];
    }

    public BlockSnapshot(int width, int height, int depth, String dimension) {
        version = formatVersion(width, height, depth);
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.dimension = dimension;
        blocks = new int[width * height * depth];
    }

    private static int formatVersion(int width, int height, int depth) {
        if (width == 50 && height == 50 && depth == 50) {
            return 4;
        }
        return width == 30 && height == 30 && depth == 30 ? 3 : 2;
    }

    public void validate() throws IOException {
        boolean legacy = version == 1 && width == 10 && depth == 10 && height >= 1 && height <= 32;
        boolean scene = (version == 2 && width == 15 && depth == 15 && height == 15) || (version == 3 && width == 30
            && depth == 30 && height == 30) || (version == 4 && width == 50 && depth == 50 && height == 50);
        validateDimensions(legacy || scene);
        validatePalette();
        if (entities == null && legacy) {
            entities = new ArrayList<>();
        }
        if (entities == null || entities.size() > 256) {
            throw new IOException("Invalid entities");
        }
        if (scene) {
            boolean validCamera = withinBounds(eyeX, eyeY, eyeZ) && Float.isFinite(viewYaw) && Float.isFinite(
                viewPitch);
            if (!validCamera) {
                throw new IOException("Invalid camera");
            }
        }
        for (var entity : entities) {
            validateEntity(entity);
        }
    }

    private void validateDimensions(boolean supportedFormat) throws IOException {
        boolean validDimension = dimension != null && dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
        boolean validBlockCount = blocks != null && blocks.length == width * depth * height;
        boolean validPaletteSize = palette != null && !palette.isEmpty() && palette.size() <= width * depth * height;
        if (!supportedFormat || !validDimension || !validBlockCount || !validPaletteSize) {
            throw new IOException("Invalid snapshot dimensions");
        }
    }

    private void validatePalette() throws IOException {
        for (String state : palette) {
            boolean validState = state != null && state.length() <= 512 && state.matches(
                "[a-z0-9_.-]+:[a-z0-9_./-]+(?:\\[[a-z0-9_=,.-]+\\])?");
            if (!validState) {
                throw new IOException("Invalid palette");
            }
        }
        for (int index : blocks) {
            if (index < 0 || index >= palette.size()) {
                throw new IOException("Invalid index");
            }
        }
    }

    private boolean withinBounds(double x, double y, double z) {
        boolean finite = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        return finite && x >= 0 && x <= width && y >= 0 && y <= height && z >= 0 && z <= depth;
    }

    private void validateEntity(SnapshotEntity entity) throws IOException {
        if (entity == null) {
            throw new IOException("Invalid entity");
        }
        boolean validType = entity.type() != null && entity.type().matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
        boolean validPosition = withinBounds(entity.x(), entity.y(), entity.z());
        boolean validRotation = Float.isFinite(entity.yaw()) && Float.isFinite(entity.pitch());
        if (!validType || !validPosition || !validRotation) {
            throw new IOException("Invalid entity");
        }
        validatePlayerIdentity(entity);
        validateAppearance(entity.appearance());
    }

    private void validatePlayerIdentity(SnapshotEntity entity) throws IOException {
        if (!entity.type().equals("minecraft:player")) {
            if (entity.playerUUID() != null || entity.playerName() != null) {
                throw new IOException("Unexpected player identity");
            }
            return;
        }
        try {
            UUID.fromString(entity.playerUUID());
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid player identity");
        }
        if (entity.playerName() == null || !entity.playerName().matches("[A-Za-z0-9_]{1,16}")) {
            throw new IOException("Invalid player name");
        }
    }

    private void validateAppearance(Map<String, String> appearance) throws IOException {
        if (appearance == null) {
            return;
        }
        if (appearance.size() > 64) {
            throw new IOException("Invalid entity appearance");
        }
        int length = 0;
        for (var field : appearance.entrySet()) {
            boolean allowedField = EntityVisual.FIELDS.contains(field.getKey());
            String value = field.getValue();
            if (!allowedField || value == null || value.length() > 4096) {
                throw new IOException("Invalid entity appearance");
            }
            length += value.length();
        }
        if (length > 16384) {
            throw new IOException("Invalid entity appearance");
        }
    }

    public int index(int x, int y, int z) {
        return (y * depth + z) * width + x;
    }

    public long estimatedBytes() {
        long size = 256L + 4L * blocks.length + textBytes(dimension) + 8L * palette.size();
        for (String state : palette) {
            size += textBytes(state);
        }
        for (SnapshotEntity entity : entities) {
            size += 192 + textBytes(entity.type()) + textBytes(entity.playerUUID()) + textBytes(entity.playerName());
            if (entity.appearance() != null) {
                size += 128;
                for (var field : entity.appearance().entrySet()) {
                    size += 64 + textBytes(field.getKey()) + textBytes(field.getValue());
                }
            }
        }
        return size;
    }

    private static long textBytes(String text) {
        return text == null ? 0 : 64L + 2L * text.length();
    }

    public String state(int x, int y, int z) {
        return palette.get(blocks[index(x, y, z)]);
    }

    public byte[] encode() throws IOException {
        validate();
        byte[] json = jsonBytes();
        if (json.length > MAX_EXPANDED_BYTES) {
            throw new IOException("Expanded snapshot too large");
        }
        var out = new ByteArrayOutputStream();
        try (var zip = new GZIPOutputStream(out)) {
            zip.write(json);
        }
        if (out.size() > MAX_COMPRESSED_BYTES) {
            throw new IOException("Snapshot too large");
        }
        return out.toByteArray();
    }

    byte[] jsonBytes() throws IOException {
        var output = new ByteArrayOutputStream();
        try (var writer = new OutputStreamWriter(output, java.nio.charset.StandardCharsets.UTF_8)) {
            new Gson().toJson(this, writer);
        }
        return output.toByteArray();
    }

    public static BlockSnapshot decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Snapshot too large");
        }
        try (var in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] json = in.readNBytes(MAX_EXPANDED_BYTES + 1);
            if (json.length > MAX_EXPANDED_BYTES) {
                throw new IOException("Expanded snapshot too large");
            }
            try {
                var snapshot = new Gson().fromJson(new String(json, java.nio.charset.StandardCharsets.UTF_8),
                    BlockSnapshot.class);
                if (snapshot == null) {
                    throw new IOException("Empty snapshot");
                }
                snapshot.validate();
                return snapshot;
            } catch (RuntimeException e) {
                throw new IOException("Invalid snapshot", e);
            }
        }
    }
}
