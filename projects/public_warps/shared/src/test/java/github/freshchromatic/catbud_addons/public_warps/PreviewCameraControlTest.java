package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PreviewCameraControlTest {

    private BlockSnapshot scene() {
        var s = new BlockSnapshot(50, 50, 50, "minecraft:overworld");
        s.palette.add("minecraft:air");
        s.eyeX = s.eyeY = s.eyeZ = 25;
        return s;
    }

    private SceneCamera pose(PreviewCameraControl control, BlockSnapshot scene) {
        return new SceneCamera(scene, 200, 200, control.yaw(), control.pitch(), control.zoom(), 1, control.offsetX(),
            control.offsetY(), control.offsetZ());
    }

    private double distance(SceneCamera a, SceneCamera b) {
        return Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.y - b.y, 2) + Math.pow(a.z - b.z, 2));
    }

    @Test
    void scrollContinuouslyEntersAndLeavesSnapshotWithoutMovingToRecordedEye() {
        var s = scene();
        var c = new PreviewCameraControl();
        c.configure(s, 200, 200);
        c.advance(0, true, true);
        assertFalse(c.inside());
        for (int i = 1; i <= 100 && !c.inside(); i++) {
            var before = pose(c, s);
            c.scroll(1);
            c.advance(i / 60d, true, true);
            assertTrue(distance(before, pose(c, s)) <= 8.0001);
        }
        assertTrue(c.inside());
        assertNotEquals(s.eyeX, pose(c, s).x);
        var beforeDrag = pose(c, s);
        c.drag(20, 10);
        c.advance(2, true, true);
        assertEquals(0, distance(beforeDrag, pose(c, s)), .00001);
        assertNotEquals(beforeDrag.orientation, pose(c, s).orientation);
        for (int i = 1; i <= 100 && c.inside(); i++) {
            var before = pose(c, s);
            c.scroll(-1);
            c.advance(2 + i / 60d, true, true);
            assertTrue(distance(before, pose(c, s)) <= 8.0001);
        }
        assertFalse(c.inside());
    }

    @Test
    void focusedMovementIsNormalizedFrameIndependentAndStopsOnBlur() {
        var s = scene();
        var straight = new PreviewCameraControl();
        var diagonal = new PreviewCameraControl();
        straight.configure(s, 200, 200);
        diagonal.configure(s, 200, 200);
        var initial = pose(straight, s);
        straight.advance(0, true, true);
        diagonal.advance(0, true, true);
        straight.focus(true);
        diagonal.focus(true);
        straight.hold(PreviewCameraControl.Movement.FORWARD, true);
        diagonal.hold(PreviewCameraControl.Movement.FORWARD, true);
        diagonal.hold(PreviewCameraControl.Movement.RIGHT, true);
        for (int i = 1; i <= 60; i++) {
            straight.advance(i / 60d, true, true);
        }
        for (int i = 1; i <= 30; i++) {
            diagonal.advance(i / 30d, true, true);
        }
        assertEquals(distance(initial, pose(straight, s)), distance(initial, pose(diagonal, s)), .0001);
        assertTrue(distance(initial, pose(straight, s)) > 4);
        straight.advance(2, false, true);
        var stopped = pose(straight, s);
        straight.advance(3, true, true);
        assertEquals(0, distance(stopped, pose(straight, s)), .00001);
        assertFalse(straight.focused());
        straight.resetView(true);
        assertEquals(0, distance(initial, pose(straight, s)), .00001);
    }

    @Test
    void narrowPreviewKeepsInitialSceneVisibleAndFirstScrollContinuous() {
        var s = scene();
        var c = new PreviewCameraControl();
        c.configure(s, 100, 400);
        c.advance(0, true, true);
        var before = pose(c, s);
        var point = new org.joml.Vector4f(25, 25, 25, 1).mul(before.view).mul(before.projection);
        assertTrue(point.z / point.w < 1);
        c.scroll(1);
        c.advance(.016, true, true);
        assertTrue(distance(before, pose(c, s)) <= 8.0001);
    }

    @Test
    void outsideOrbitAndInsideLookUseSameBlockEntityCameraMatrix() {
        var s = scene();
        var c = new PreviewCameraControl();
        c.configure(s, 200, 200);
        c.advance(0, true, true);
        var before = pose(c, s);
        c.drag(30, 10);
        c.advance(.016, true, true);
        var after = pose(c, s);
        assertTrue(distance(before, after) > 1);
        assertEquals(before.fovDegrees, after.fovDegrees);
        var eye = new SceneCamera(s, 200, 200, 0, 0, 1, 1);
        var point = new org.joml.Vector4f(25, 25, 30, 1).mul(eye.view).mul(eye.projection);
        assertEquals(0, point.x / point.w, .001);
        assertEquals(0, point.y / point.w, .001);
        assertTrue(point.w > 0);
    }
}
