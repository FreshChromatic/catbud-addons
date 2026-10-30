package github.freshchromatic.catbud_addons.island_assistive.mixin;

import github.freshchromatic.catbud_addons.island_assistive.BlockMiningSession;
import github.freshchromatic.catbud_addons.island_assistive.MiningClientAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MiningInputMixin implements MiningClientAccess {

    @Shadow private boolean pause;
    @Shadow private int     missTime;

    @Unique private BlockMiningSession islandAssistive$session;
    @Unique private HitResult          islandAssistive$cameraHit;
    @Unique private Entity             islandAssistive$cameraEntity;
    @Unique private boolean            islandAssistive$bodyHit;

    @Override
    public void islandAssistive$mining(BlockMiningSession session) {
        islandAssistive$session = session;
    }

    @Override
    public BlockMiningSession islandAssistive$mining() {
        return islandAssistive$session;
    }

    @Override
    public void islandAssistive$resetAttackLock() {
        // Screens set this to 10000; scripted holds do not produce the release that normally clears it.
        missTime = 0;
    }

    @Override
    public boolean islandAssistive$bodyInput() {
        return islandAssistive$session != null && islandAssistive$session.running() && islandAssistive$session
            .state() != BlockMiningSession.State.SUSPENDED && (islandAssistive$bodyHit || islandAssistive$session
                .directInput());
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void islandAssistive$input(CallbackInfo ci) {
        if (islandAssistive$session != null) {
            var hit = islandAssistive$session.inputHit();
            if (hit != null) {
                var client = (Minecraft) (Object) this;
                islandAssistive$cameraHit = client.hitResult;
                islandAssistive$cameraEntity = client.crosshairPickEntity;
                islandAssistive$bodyHit = true;
                client.hitResult = hit;
                client.crosshairPickEntity = hit instanceof EntityHitResult entity ? entity.getEntity() : null;
            }
            islandAssistive$session.inputTick();
            if (!islandAssistive$session.running()) {
                islandAssistive$restoreCameraHit();
            }
        }
    }

    @Inject(method = "handleKeybinds", at = @At("TAIL"))
    private void islandAssistive$afterInput(CallbackInfo ci) {
        if (islandAssistive$session != null) {
            islandAssistive$session.afterInput();
        }
        islandAssistive$restoreCameraHit();
    }

    @Unique
    private void islandAssistive$restoreCameraHit() {
        if (!islandAssistive$bodyHit) {
            return;
        }
        var client = (Minecraft) (Object) this;
        client.hitResult = islandAssistive$cameraHit;
        client.crosshairPickEntity = islandAssistive$cameraEntity;
        islandAssistive$cameraHit = null;
        islandAssistive$cameraEntity = null;
        islandAssistive$bodyHit = false;
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void islandAssistive$frame(boolean renderLevel, CallbackInfo ci) {
        if (islandAssistive$session != null) {
            islandAssistive$session.frame();
        }
    }

    @Inject(method = "runTick", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;pause:Z", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void islandAssistive$backgroundWorld(boolean renderLevel, CallbackInfo ci) {
        if (pause && islandAssistive$session != null && islandAssistive$session.keepWorldRunning()) {
            pause = false;
        }
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void islandAssistive$guardClick(CallbackInfoReturnable<Boolean> ci) {
        if (islandAssistive$session != null && islandAssistive$session.blocksAttack()) {
            ci.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void islandAssistive$guardHold(boolean held, CallbackInfo ci) {
        if (islandAssistive$session != null && islandAssistive$session.blocksAttack()) {
            ci.cancel();
        }
    }
}
