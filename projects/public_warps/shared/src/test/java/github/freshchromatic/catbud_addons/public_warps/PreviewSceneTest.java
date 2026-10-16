package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class PreviewSceneTest {

    private BlockSnapshot scene() {
        var s = new BlockSnapshot(15, 15, 15, "minecraft:overworld");
        s.palette.add("minecraft:air");
        s.eyeX = 7.5;
        s.eyeY = 8.62;
        s.eyeZ = 7.5;
        return s;
    }

    @Test
    void cameraAndEntityAppearanceSurviveCompressionAndRejectUnsafeFields() throws Exception {
        var s = scene();
        s.viewYaw = 123;
        s.entities.add(new BlockSnapshot.SnapshotEntity("minecraft:sheep", 8, 7, 8, 90, 0, null, null, Map.of("Color",
            "14b")));
        var decoded = BlockSnapshot.decode(s.encode());
        assertEquals(3375, decoded.blocks.length);
        assertEquals(123, decoded.viewYaw);
        assertEquals(s.entities, decoded.entities);
        s.entities.set(0, new BlockSnapshot.SnapshotEntity("minecraft:sheep", 8, 7, 8, 90, 0, null, null, Map.of(
            "Passengers", "[]")));
        assertThrows(java.io.IOException.class, s::encode);
        s.entities.clear();
        s.eyeX = 16;
        assertThrows(java.io.IOException.class, s::encode);
    }

    @Test
    void thirtyCubeRoundTripAndLegacyCompatibility() throws Exception {
        var s = new BlockSnapshot(30, 30, 30, "minecraft:overworld");
        s.palette.add("minecraft:air");
        s.eyeX = 15.5;
        s.eyeY = 16.62;
        s.eyeZ = 15.5;
        s.entities.add(new BlockSnapshot.SnapshotEntity("minecraft:sheep", 29.5, 29, 29, 0, 0, null, null, Map.of()));
        var copy = BlockSnapshot.decode(s.encode());
        assertEquals(3, copy.version);
        assertEquals(27000, copy.blocks.length);
        assertEquals(s.entities, copy.entities);
        assertEquals(2, BlockSnapshot.decode(scene().encode()).version);
        s.version = 2;
        assertThrows(java.io.IOException.class, s::encode);
        s.version = 3;
        s.eyeX = 31;
        assertThrows(java.io.IOException.class, s::encode);
    }

    @Test
    void interruptedTransitionIsContinuousAndReducedMotionDoesNotOrbit() {
        var m = new PreviewMotion();
        m.update(1, true, true, false);
        double middle = m.update(1.05, true, true, false);
        assertTrue(middle > 0 && middle < 1);
        assertEquals(middle, m.update(1.05, false, false, false), 1e-6);
        assertEquals(0, m.update(1.4, false, false, false), 1e-6);
        double angle = m.angle();
        assertEquals(1, m.update(2, true, true, true));
        assertEquals(angle, m.angle());
        assertEquals(0, m.update(3, false, false, true));
        for (int i = 0; i < 80; i++) {
            m.update(4 + i * .1, true, false, false);
        }
        assertTrue(m.angle() - angle > 6.2); // Full 360-degree orbit at the requested hover cadence.
    }

    @Test
    void cameraMakesOneFullOrbitAndClipsFacesBehindTheInsideView() {
        var s = scene();
        var a = new SceneProjection(s, 0, 0, 200, 200, 0, 0);
        var b = new SceneProjection(s, 0, 0, 200, 200, Math.PI * 2, 0);
        assertEquals(a.point(2, 3, 4).x(), b.point(2, 3, 4).x(), .001);
        var inside = new SceneProjection(s, 0, 0, 200, 200, 0, 1);
        assertNotNull(inside.point(7.5, 8.62, 10));
        assertNull(inside.point(7.5, 8.62, 4));
        float[] crossing = { 6, 8, 7, 0, 0, 6, 8, 9, 0, 1, 9, 8, 9, 1, 1, 9, 8, 7, 1, 0 };
        var faces = inside.face(crossing);
        assertFalse(faces.isEmpty());
        for (var face : faces) {
            for (float v : face.vertices()) {
                assertTrue(Float.isFinite(v));
            }
        }
    }

    @Test
    void hoverAlternatesTwoAndHalfSecondViewsAndResetsOnExit() {
        var motion = new PreviewMotion();
        assertEquals(0, motion.updateHover(1, true, false));
        assertEquals(0, motion.updateHover(3.49, true, false));
        assertTrue(motion.angle() > Math.PI * 1.9);
        motion.updateHover(3.5, true, false);
        assertEquals(1, motion.updateHover(3.8, true, false), 1e-6);
        motion.updateHover(6, true, false);
        assertEquals(0, motion.updateHover(6.3, true, false), 1e-6);
        motion.updateHover(8.5, true, false);
        assertEquals(1, motion.updateHover(8.8, true, false), 1e-6);
        motion.updateHover(8.9, false, false);
        assertEquals(0, motion.updateHover(9.2, false, false), 1e-6);
        assertEquals(0, motion.angle());
        assertEquals(0, motion.updateHover(10, true, true));
        assertEquals(0, motion.updateHover(14, true, true));
        assertEquals(0, motion.angle());
    }
}
