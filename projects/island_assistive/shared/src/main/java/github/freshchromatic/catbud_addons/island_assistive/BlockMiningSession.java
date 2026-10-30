package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.GameType;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class BlockMiningSession {

    public enum State {
        STOPPED, SELECTING, WAITING, AIMING, MOVING, MINING, SUSPENDED, PAUSED
    }

    private final Minecraft              client;
    private final BlockMiningTargetStore store;
    private final MiningAimPlanner       aim       = new MiningAimPlanner();
    private final MiningPositionPlanner  positions = new MiningPositionPlanner(aim);
    private final MiningPlayerControl    control;
    private final MiningAttackController attack;

    private State                    state         = State.STOPPED;
    private String                   reason        = "stopped";
    private Vec3                     anchor;
    private Vec3                     point;
    private Vec3                     destination;
    private double                   moveRadius    = 0.5;
    private double                   turnSpeed     = 180;
    private boolean                  allowMovement = true;
    private boolean                  cooldown      = true;
    private int                      selectionTicks;
    private int                      movementTicks;
    private int                      replans;
    private int                      noticeTicks;
    private int                      invisibleTicks;
    private BlockState               observedBlock;
    private MiningInterruptionPolicy interruptions = MiningInterruptionPolicy.DEFAULT;
    private boolean                  directInput;

    public BlockMiningSession(Minecraft client, BlockMiningTargetStore store) {
        this.client = client;
        this.store = store;
        control = new MiningPlayerControl(client);
        attack = new MiningAttackController(client, control);
    }

    public void configure(boolean movement, double radius, double speed, boolean respectCooldown) {
        allowMovement = movement;
        moveRadius = radius;
        turnSpeed = speed;
        cooldown = respectCooldown;
        if (!movement && state == State.MOVING) {
            control.releaseMovement();
            destination = null;
        }
    }

    public void interruptions(MiningInterruptionPolicy policy) {
        interruptions = policy;
    }

    public State state() {
        return state;
    }

    public boolean running() {
        return state == State.WAITING || state == State.AIMING || state == State.MOVING || state == State.MINING
            || state == State.SUSPENDED;
    }

    public Component status() {
        return text(reason);
    }

    public Component targetDescription() {
        var target = store.target();
        if (store.loadFailed()) {
            return text("load_failed");
        }
        return target == null ? text("unset") : Component.literal(target.x() + ", " + target.y() + ", " + target.z());
    }

    public Component worldDescription() {
        var target = store.target();
        if (target == null) {
            return text("unset");
        }
        String world = target.world();
        int separator = Math.max(world.lastIndexOf('/'), world.lastIndexOf('\\'));
        return Component.literal(world.startsWith("server:") ? world.substring(7) : world.substring(separator + 1));
    }

    public Component dimensionDescription() {
        return store.target() == null ? text("unset") : Component.literal(store.target().dimension());
    }

    public void select() {
        stop();
        if (client.level == null || client.player == null) {
            pause("no_world");
            return;
        }
        state = State.SELECTING;
        reason = "selecting";
        selectionTicks = 0;
        ClientPlatform.show(client, null);
        ClientPlatform.actionbar(client, status());
    }

    public InteractionResult select(BlockHitResult hit) {
        if (state != State.SELECTING) {
            return InteractionResult.PASS;
        }
        var pos = hit.getBlockPos();
        try {
            store.save(new BlockMiningTarget(world(), ClientPlatform.dimension(client).toString(), pos.getX(), pos
                .getY(), pos.getZ()));
            stop();
            reason = "selected";
        } catch (IOException error) {
            LoggerFactory.getLogger(BlockMiningSession.class).warn("Cannot save mining target", error);
            pause("save_failed");
        }
        ClientPlatform.actionbar(client, status());
        return InteractionResult.FAIL;
    }

    public void toggle() {
        if (running() || state == State.SELECTING) {
            stop();
        } else {
            start();
        }
    }

    public void start() {
        stop();
        if (client.player == null || client.level == null || client.gameMode == null) {
            pause("no_world");
            return;
        }
        if (!matchesWorld()) {
            pause(store.target() == null ? "unset" : "wrong_world");
            return;
        }
        if (unsupportedStance()) {
            pause("standing_required");
            return;
        }
        if (!positions.safePath(client, targetPos(), client.player.position(), client.player.position())) {
            pause("no_safe_position");
            return;
        }
        var target = targetPos();
        double reach = client.player.blockInteractionRange();
        if (Vec3.atCenterOf(target).distanceTo(client.player.getEyePosition()) > reach + 1) {
            pause("out_of_reach");
            return;
        }
        anchor = client.player.position();
        control.begin();
        replans = 0;
        noticeTicks = 0;
        state = State.WAITING;
        reason = "waiting";
        ClientPlatform.show(client, null);
    }

    public void stop() {
        attack.reset();
        control.release();
        state = State.STOPPED;
        reason = "stopped";
        point = null;
        destination = null;
        invisibleTicks = 0;
        observedBlock = null;
    }

    private void pause(String cause) {
        stop();
        state = State.PAUSED;
        reason = cause;
        if (client.player != null) {
            ClientPlatform.actionbar(client, status());
        }
    }

    private void suspend(String cause) {
        attack.reset();
        control.release();
        point = null;
        destination = null;
        state = State.SUSPENDED;
        reason = cause;
    }

    public void tick() {
        if (state == State.SELECTING) {
            if (ClientPlatform.screen(client) != null || client.player == null || ++selectionTicks > 600) {
                stop();
            } else if (selectionTicks % 20 == 0) {
                ClientPlatform.actionbar(client, status());
            }
            return;
        }
        if (!running()) {
            return;
        }
        if (!ready()) {
            return;
        }
        if (control.manualInput()) {
            pause("manual");
            return;
        }
        if (client.player.position().distanceToSqr(anchor) > (moveRadius + 0.1) * (moveRadius + 0.1) || Math.abs(
            client.player.getY() - anchor.y) > 0.15) {
            pause("displaced");
            return;
        }
        var target = targetPos();
        if (!client.level.hasChunkAt(target)) {
            pause("unloaded");
            return;
        }
        var block = client.level.getBlockState(target);
        if (block != observedBlock) {
            observedBlock = block;
            invisibleTicks = 0;
            replans = 0;
            destination = null;
            point = null;
        }
        if (block.isAir()) {
            attack.waitForBlock();
            control.releaseMovement();
            point = null;
            control.clearAim();
            destination = null;
            replans = 0;
            state = State.WAITING;
            reason = "waiting";
            return;
        }
        if (block.getDestroySpeed(client.level, target) < 0) {
            pause("unbreakable");
            return;
        }
        point = aim.find(client, target, client.player.getEyePosition(), point);
        if (point != null) {
            invisibleTicks = 0;
            replans = 0;
            destination = null;
            control.releaseMovement();
            control.look(point, turnSpeed);
            state = State.AIMING;
            reason = "aiming";
        } else {
            control.clearAim();
            attack.release();
            move(target);
        }
        if (++noticeTicks % 20 == 0) {
            ClientPlatform.actionbar(client, status());
        }
    }

    private boolean ready() {
        if (client.player == null || client.level == null || client.gameMode == null) {
            pause("no_world");
            return false;
        }
        if (!client.player.isAlive() || !matchesWorld()) {
            pause("wrong_world");
            return false;
        }
        String suspension = interruptions.suspension(client);
        if (suspension != null) {
            if (state != State.SUSPENDED || !reason.equals(suspension)) {
                suspend(suspension);
            }
            return false;
        }
        if (client.isPaused()) {
            suspend("suspended_world");
            return false;
        }
        if (state == State.SUSPENDED) {
            control.begin();
            state = State.WAITING;
            reason = "waiting";
        }
        if (unsupportedStance()) {
            pause("standing_required");
            return false;
        }
        return true;
    }

    private void move(BlockPos target) {
        control.releaseMovement();
        if (!allowMovement) {
            pause("not_visible");
            return;
        }
        if (destination == null) {
            invisibleTicks++;
            if (invisibleTicks % 5 != 1) {
                state = State.WAITING;
                reason = "reacquiring";
                return;
            }
            if (++replans > 5) {
                pause("not_visible");
                return;
            }
            destination = positions.find(client, target, anchor, moveRadius);
            movementTicks = 0;
        }
        if (destination == null) {
            if (invisibleTicks >= 20) {
                pause("no_safe_position");
            } else {
                state = State.WAITING;
                reason = "reacquiring";
            }
            return;
        }
        if (++movementTicks > 100 || !positions.safePath(client, target, client.player.position(), destination)) {
            pause("movement_blocked");
            return;
        }
        if (client.player.position().distanceToSqr(destination) < 0.0009) {
            control.releaseMovement();
            destination = null;
            return;
        }
        control.move(destination);
        state = State.MOVING;
        reason = "moving";
    }

    public void inputTick() {
        if (!running() || !ready()) {
            return;
        }
        if (backgroundInput()) {
            return;
        }
        if (control.manualInput()) {
            pause("manual");
            return;
        }
        if (state == State.MOVING || state == State.WAITING || aim.crosshair(client, targetPos()) == null) {
            attack.release();
            return;
        }
        if (client.player.isUsingItem()) {
            attack.release();
            reason = "waiting_item";
            return;
        }
        state = State.MINING;
        reason = "mining";
        if (!attack.tick(targetPos(), cooldown)) {
            pause("no_progress");
        }
    }

    public boolean blocksAttack() {
        return running() && (state != State.MINING || client.player == null || client.level == null || backgroundInput()
            || interruptions.suspension(client) != null || aim.crosshair(client, targetPos()) == null);
    }

    public net.minecraft.world.phys.HitResult inputHit() {
        return running() && state != State.SUSPENDED && client.player != null && client.level != null && client
            .getCameraEntity() != client.player ? aim.bodyHit(client) : null;
    }

    public void frame() {
        if (!running() || state == State.SUSPENDED || client.player == null || client.level == null || interruptions
            .suspension(client) != null) {
            return;
        }
        if (control.manualInput()) {
            pause("manual");
            return;
        }
        control.frame(System.nanoTime());
    }

    public void applyMovementInput() {
        if (state == State.MOVING && client.player != null && (client.getCameraEntity() != client.player
            || backgroundInput())) {
            control.applyMovementInput();
        }
    }

    public boolean directInput() {
        return directInput;
    }

    public boolean keepWorldRunning() {
        return running() && client.player != null && client.level != null && (ClientPlatform.screen(client) != null
            || !client.isWindowActive()) && interruptions.suspension(client) == null;
    }

    private boolean backgroundInput() {
        return ClientPlatform.screen(client) != null || !client.isWindowActive() || !client.mouseHandler
            .isMouseGrabbed();
    }

    public void backgroundTick() {
        if (!running() || !backgroundInput() || !ready()) {
            return;
        }
        if (state == State.MOVING || state == State.WAITING || client.player.isUsingItem()) {
            attack.release();
            if (client.player.isUsingItem()) {
                reason = "waiting_item";
            }
            return;
        }
        var hit = aim.bodyHit(client);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
            || !block.getBlockPos().equals(targetPos())) {
            attack.release();
            return;
        }
        state = State.MINING;
        reason = "mining";
        directInput = true;
        try {
            if (!attack.tickDirect(targetPos(), block, cooldown)) {
                pause("no_progress");
            }
        } finally {
            directInput = false;
        }
        afterInput();
    }

    public void manualScroll() {
        if (running() && ClientPlatform.screen(client) == null && client.isWindowActive() && client.mouseHandler
            .isMouseGrabbed() && client.getCameraEntity() == client.player) {
            pause("manual");
        }
    }

    private boolean unsupportedStance() {
        boolean supported = client.player.onGround() || positions.safePath(client, targetPos(), client.player
            .position(), client.player.position());
        // Free-camera rendering can temporarily report the real player as a spectator.
        return client.gameMode.getPlayerMode() == GameType.SPECTATOR || !supported || client.player.isPassenger()
            || client.player.isFallFlying() || client.player.isInWater();
    }

    public void afterInput() {
        if (running() && client.level != null && client.level.getBlockState(targetPos()).isAir()) {
            attack.waitForBlock();
            replans = 0;
        }
    }

    public void blockDestroyed(BlockPos pos) {
        if (running() && pos.equals(targetPos())) {
            attack.madeProgress();
        }
    }

    private BlockPos targetPos() {
        var target = store.target();
        return new BlockPos(target.x(), target.y(), target.z());
    }

    private boolean matchesWorld() {
        var target = store.target();
        return target != null && target.world().equals(world()) && target.dimension().equals(ClientPlatform.dimension(
            client).toString());
    }

    String world() {
        if (client.getCurrentServer() != null) {
            return "server:" + client.getCurrentServer().ip.toLowerCase(java.util.Locale.ROOT);
        }
        var server = client.getSingleplayerServer();
        return server == null ? "unknown"
            : "local:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    private static Component text(String key) {
        return Component.translatable("island.mining." + key);
    }
}
