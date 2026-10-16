package github.freshchromatic.catbud_addons.public_warps;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;

import java.util.*;

public final class WarpSceneMesh {

    private final List<Face>                  faces;
    private final List<EntityRenderState>     entities;
    private final Map<Identifier, List<Face>> opaque;
    private final Map<Identifier, List<Face>> alpha;
    private final long                        bytes;

    public WarpSceneMesh(List<Face> faces, List<EntityRenderState> entities) {
        var builder = new Builder(faces, entities);
        while (!builder.advance()) {
        }
        WarpSceneMesh completed = builder.result();
        this.faces = completed.faces;
        this.entities = completed.entities;
        opaque = completed.opaque;
        alpha = completed.alpha;
        bytes = completed.bytes;
    }

    private WarpSceneMesh(Builder builder) {
        faces = Collections.unmodifiableList(builder.faces);
        entities = builder.entities;
        opaque = Collections.unmodifiableMap(builder.opaque);
        alpha = Collections.unmodifiableMap(builder.alpha);
        bytes = builder.bytes;
    }

    public List<Face> faces() {
        return faces;
    }

    public long estimatedBytes() {
        return bytes;
    }

    public List<EntityRenderState> entities() {
        return entities;
    }

    public Map<Identifier, List<Face>> opaque() {
        return opaque;
    }

    public Map<Identifier, List<Face>> alpha() {
        return alpha;
    }

    public record Face(float[] vertex, int color, Identifier atlas, float depth, float normalX, float normalY,
        float normalZ) {

        public Face(float[] vertex, int color, Identifier atlas, float depth) {
            this(vertex, color, atlas, depth, normal(vertex));
        }

        private Face(float[] vertex, int color, Identifier atlas, float depth, Normal normal) {
            this(vertex, color, atlas, depth, normal.x(), normal.y(), normal.z());
        }

        private static Normal normal(float[] vertex) {
            float ax = vertex[5] - vertex[0];
            float ay = vertex[6] - vertex[1];
            float az = vertex[7] - vertex[2];
            float bx = vertex[10] - vertex[0];
            float by = vertex[11] - vertex[1];
            float bz = vertex[12] - vertex[2];
            float x = ay * bz - az * by;
            float y = az * bx - ax * bz;
            float z = ax * by - ay * bx;
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length == 0) {
                return new Normal(0, 0, 0);
            }
            return new Normal(x / length, y / length, z / length);
        }

        private record Normal(float x, float y, float z) {}
    }

    public static final class Builder {

        private final List<Face>                  input;
        private final List<Face>                  faces  = new SegmentedFaceList();
        private final List<EntityRenderState>     entities;
        private final Map<Identifier, List<Face>> opaque = new LinkedHashMap<>();
        private final Map<Identifier, List<Face>> alpha  = new LinkedHashMap<>();
        private long                              bytes;
        private int                               cursor;
        private WarpSceneMesh                     result;

        public Builder(List<Face> input, List<EntityRenderState> entities) {
            this.input = input;
            this.entities = List.copyOf(entities);
            bytes = 256 + 16384L * entities.size();
        }

        public boolean advance() {
            if (result != null) {
                return true;
            }
            for (int count = 0; count < 256 && cursor < input.size(); count++) {
                Face face = input.get(cursor++);
                faces.add(face);
                ((face.color() >>> 24) == 255 ? opaque : alpha).computeIfAbsent(face.atlas(),
                    key -> new SegmentedFaceList()).add(face);
                bytes += 108L + 4L * face.vertex().length;
            }
            return cursor == input.size();
        }

        public WarpSceneMesh result() {
            if (result != null) {
                return result;
            }
            if (cursor != input.size()) {
                throw new IllegalStateException("Preview mesh is unfinished");
            }
            for (var groups : List.of(opaque, alpha)) {
                groups.replaceAll((key, group) -> Collections.unmodifiableList(group));
            }
            result = new WarpSceneMesh(this);
            return result;
        }
    }
}
