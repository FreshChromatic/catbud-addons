package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SceneLightDirectionsTest {

    @Test
    void preservesFrontAndBackIlluminationAcrossCameraViews() {
        var light0 = new Vector3f(.2f, -1, 1).normalize();
        var light1 = new Vector3f(-.2f, -1, 0).normalize();
        var normals = new Vector3f[] { new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1),
            new Vector3f(1, 2, -3).normalize(), new Vector3f() };
        for (float yaw : new float[] { 0, .8f, 2.4f, 4.9f }) {
            for (float pitch : new float[] { -1.48f, 0, .6f, 1.48f }) {
                var view = new Matrix4f().rotateX(pitch).rotateY(yaw).translate(-17, 8, -25);
                var lights = SceneLightDirections.forView(view);
                for (var normal : normals) {
                    var transformed = view.transformDirection(normal, new Vector3f());
                    for (int side : new int[] { -1, 1 }) {
                        assertEquals(side * light0.dot(transformed), side * lights.first().dot(normal), 1e-6);
                        assertEquals(side * light1.dot(transformed), side * lights.second().dot(normal), 1e-6);
                    }
                }
            }
        }
    }
}
