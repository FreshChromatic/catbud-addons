package github.freshchromatic.catbud_addons.island_assistive;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class MiningAttackController {

    private final Minecraft           client;
    private final MiningPlayerControl control;

    private boolean    held;
    private boolean    direct;
    private boolean    cooldownSatisfied;
    private boolean    retried;
    private BlockState lastState;
    private int        stagnantTicks;
    private float      lastProgress;

    public MiningAttackController(Minecraft client, MiningPlayerControl control) {
        this.client = client;
        this.control = control;
    }

    public boolean tick(BlockPos target, boolean cooldown) {
        if (!prepare(target, cooldown, false)) {
            return true;
        }
        if (!held) {
            ((MiningClientAccess) client).islandAssistive$resetAttackLock();
            MiningPlatform.click(client.options.keyAttack);
            held = true;
        }
        control.set(client.options.keyAttack, true);
        return progressing(target);
    }

    public boolean tickDirect(BlockPos target, BlockHitResult hit, boolean cooldown) {
        if (!prepare(target, cooldown, true)) {
            return true;
        }
        boolean swinging = !held || !client.gameMode.isDestroying() ? client.gameMode.startDestroyBlock(target, hit
            .getDirection()) : client.gameMode.continueDestroyBlock(target, hit.getDirection());
        held = true;
        if (swinging) {
            MiningPlatform.swing(client);
        }
        return progressing(target);
    }

    private boolean prepare(BlockPos target, boolean cooldown, boolean direct) {
        if (this.direct != direct) {
            release();
            this.direct = direct;
        }
        var state = client.level.getBlockState(target);
        if (lastState != state) {
            madeProgress();
            lastState = state;
        }
        cooldownSatisfied = cooldownSatisfied || held || !cooldown || client.player.getAttackStrengthScale(0) >= 1;
        return cooldownSatisfied;
    }

    private boolean progressing(BlockPos target) {
        float currentProgress = ((MiningProgressAccess) client.gameMode).islandAssistive$destroyProgress();
        if (currentProgress > lastProgress) {
            madeProgress();
        }
        lastProgress = currentProgress;
        float progress = client.level.getBlockState(target).getDestroyProgress(client.player, client.level, target);
        double expectedTicks = progress > 0 ? 1.0 / progress : 200;
        if (!retried && stagnantTicks >= 20 && !client.gameMode.isDestroying()) {
            release();
            retried = true;
        }
        return ++stagnantTicks < Math.max(200, expectedTicks * 2 + 40);
    }

    public void madeProgress() {
        stagnantTicks = 0;
        lastProgress = 0;
        retried = false;
    }

    public void waitForBlock() {
        release();
        lastState = null;
        madeProgress();
    }

    public void release() {
        if (!held) {
            return;
        }
        control.set(client.options.keyAttack, false);
        if (client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
        held = false;
    }

    public void reset() {
        waitForBlock();
        cooldownSatisfied = false;
    }
}
