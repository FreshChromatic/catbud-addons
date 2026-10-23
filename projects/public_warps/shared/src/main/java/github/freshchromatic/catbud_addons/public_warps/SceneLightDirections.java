package github.freshchromatic.catbud_addons.public_warps;

import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

public record SceneLightDirections(Vector3f first, Vector3f second) {

    public static SceneLightDirections forView(Matrix4fc view) {
        // Camera views are rigid transforms: rotating lights back preserves view-space lighting on cached normals.
        var inverseRotation = new Matrix3f(view).transpose();
        return new SceneLightDirections(inverseRotation.transform(new Vector3f(.2f, -1, 1).normalize()), inverseRotation
            .transform(new Vector3f(-.2f, -1, 0).normalize()));
    }
}
