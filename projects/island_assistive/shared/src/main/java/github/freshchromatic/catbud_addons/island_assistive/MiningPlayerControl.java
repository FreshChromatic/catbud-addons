package github.freshchromatic.catbud_addons.island_assistive;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import github.freshchromatic.catbud_addons.island_assistive.mixin.MiningMovementAccessor;
import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;

public final class MiningPlayerControl {

    private final Minecraft        client;
    private final Set<KeyMapping>  held   = new HashSet<>();
    private final MiningLookMotion motion = new MiningLookMotion();

    private float   expectedYaw;
    private float   expectedPitch;
    private boolean looking;
    private Vec3    aimPoint;
    private double  turnSpeed;
    private long    lastFrame;
    private Input   movement = Input.EMPTY;

    public MiningPlayerControl(Minecraft client) {
        this.client = client;
    }

    public void begin() {
        expectedYaw = client.player.getYRot();
        expectedPitch = client.player.getXRot();
        looking = true;
        lastFrame = 0;
    }

    public void look(Vec3 point, double speed) {
        aimPoint = point;
        turnSpeed = speed;
    }

    public void clearAim() {
        aimPoint = null;
        lastFrame = 0;
        motion.reset();
    }

    public void frame(long now) {
        double seconds = lastFrame == 0 ? 0 : Math.clamp((now - lastFrame) / 1_000_000_000.0, 0, 0.1);
        lastFrame = now;
        if (aimPoint == null || seconds == 0) {
            return;
        }
        var delta = aimPoint.subtract(client.player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        var rotation = motion.advance(client.player.getYRot(), client.player.getXRot(), yaw, pitch, turnSpeed, seconds);
        expectedYaw = rotation.yaw();
        expectedPitch = rotation.pitch();
        client.player.setYRot(expectedYaw);
        client.player.setXRot(expectedPitch);
        client.player.yRotO = expectedYaw;
        client.player.xRotO = expectedPitch;
        looking = true;
    }

    public boolean manualInput() {
        if (ClientPlatform.screen(client) != null || !client.isWindowActive() || !client.mouseHandler
            .isMouseGrabbed()) {
            return false;
        }
        var options = client.options;
        for (var key : new KeyMapping[] { options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
            options.keyJump, options.keyShift }) {
            if (client.getCameraEntity() == client.player && MiningPlatform.physical(key)) {
                return true;
            }
        }
        for (var key : options.keyHotbarSlots) {
            if (MiningPlatform.physical(key)) {
                return true;
            }
        }
        return client.mouseHandler.isLeftPressed() || looking && (Math.abs(Mth.wrapDegrees(client.player.getYRot()
            - expectedYaw)) > 0.1f || Math.abs(client.player.getXRot() - expectedPitch) > 0.1f);
    }

    public void move(Vec3 destination) {
        releaseMovement();
        var delta = destination.subtract(client.player.position());
        double angle = Math.toRadians(client.player.getYRot());
        double forward = -Math.sin(angle) * delta.x + Math.cos(angle) * delta.z;
        double left = Math.cos(angle) * delta.x + Math.sin(angle) * delta.z;
        double threshold = 0.015;
        movement = new Input(forward > threshold, forward < -threshold, left > threshold, left < -threshold, false,
            true, false);
        if (client.getCameraEntity() != client.player || ClientPlatform.screen(client) != null || !client
            .isWindowActive() || !client.mouseHandler.isMouseGrabbed()) {
            return;
        }
        set(client.options.keyUp, forward > threshold);
        set(client.options.keyDown, forward < -threshold);
        set(client.options.keyLeft, left > threshold);
        set(client.options.keyRight, left < -threshold);
        set(client.options.keyShift, true);
    }

    public void set(KeyMapping key, boolean down) {
        if (down) {
            held.add(key);
            key.setDown(true);
        } else if (held.remove(key)) {
            key.setDown(MiningPlatform.physical(key));
        }
    }

    public void releaseMovement() {
        movement = Input.EMPTY;
        var options = client.options;
        for (var key : new KeyMapping[] { options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
            options.keyShift }) {
            set(key, false);
        }
    }

    public void release() {
        for (var key : Set.copyOf(held)) {
            set(key, false);
        }
        looking = false;
        movement = Input.EMPTY;
        clearAim();
    }

    public void applyMovementInput() {
        var input = client.player.input;
        input.keyPresses = movement;
        var vector = new Vec2((movement.left() ? 1 : 0) - (movement.right() ? 1 : 0), (movement.forward() ? 1 : 0)
            - (movement.backward() ? 1 : 0)).normalized();
        ((MiningMovementAccessor) input).islandAssistive$moveVector(vector);
    }
}
