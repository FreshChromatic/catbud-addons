package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class WarpSceneMeshTest {

    @Test
    void incrementalGroupingPreservesFacesAndPublishesImmutableCompleteMesh() {
        var atlas = Identifier.withDefaultNamespace("textures/atlas/blocks.png");
        var faces = new ArrayList<WarpSceneMesh.Face>();
        for (int index = 0; index < 1001; index++) {
            faces.add(new WarpSceneMesh.Face(new float[20], index % 2 == 0 ? 0xFFFFFFFF : 0xFEFFFFFF, atlas, index));
        }
        var builder = new WarpSceneMesh.Builder(faces, List.of());
        assertFalse(builder.advance());
        assertThrows(IllegalStateException.class, builder::result);
        while (!builder.advance()) {
        }
        var mesh = builder.result();
        assertEquals(faces, mesh.faces());
        assertEquals(501, mesh.opaque().get(atlas).size());
        assertEquals(500, mesh.alpha().get(atlas).size());
        assertEquals(256 + 1001L * 188, mesh.estimatedBytes());
        assertThrows(UnsupportedOperationException.class, () -> mesh.faces().clear());
        assertThrows(UnsupportedOperationException.class, () -> mesh.opaque().clear());
        assertThrows(UnsupportedOperationException.class, () -> mesh.alpha().get(atlas).clear());
        faces.clear();
        assertTrue(builder.advance());
        assertSame(mesh, builder.result());
        assertEquals(1001, mesh.faces().size());
    }

    @Test
    void faceNormalsPreserveWindingAndNormalizeSlopesWithoutInvalidDegenerateValues() {
        var atlas = Identifier.withDefaultNamespace("textures/atlas/blocks.png");
        float[] vertices = new float[20];
        vertices[5] = 1;
        vertices[11] = 1;
        vertices[12] = 1;
        var slope = new WarpSceneMesh.Face(vertices, -1, atlas, 0);
        assertEquals(0, slope.normalX());
        assertEquals(-1 / Math.sqrt(2), slope.normalY(), 0.000001);
        assertEquals(1 / Math.sqrt(2), slope.normalZ(), 0.000001);
        var empty = new WarpSceneMesh.Face(new float[20], -1, atlas, 0);
        assertEquals(0, empty.normalX());
        assertEquals(0, empty.normalY());
        assertEquals(0, empty.normalZ());
    }
}
