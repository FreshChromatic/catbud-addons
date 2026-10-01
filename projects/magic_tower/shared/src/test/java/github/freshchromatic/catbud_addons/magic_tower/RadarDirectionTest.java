package github.freshchromatic.catbud_addons.magic_tower;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RadarDirectionTest {
    @Test
    void targetOnPlayersRightHasPositiveScreenOffset() {
        // At yaw 0 the player faces +Z, so -X is on the player's right.
        assertTrue(RadarHud.cameraRight(new Vec3(-8, 0, 10), 0) > 0);
        assertTrue(RadarHud.cameraRight(new Vec3(8, 0, 10), 0) < 0);
    }

    @Test
    void directionRotatesWithCamera() {
        // At yaw 90 degrees the player faces -X, so -Z is on the right.
        assertTrue(RadarHud.cameraRight(new Vec3(-10, 0, -8), Math.PI / 2) > 0);
        assertTrue(RadarHud.cameraRight(new Vec3(-10, 0, 8), Math.PI / 2) < 0);
    }
}
