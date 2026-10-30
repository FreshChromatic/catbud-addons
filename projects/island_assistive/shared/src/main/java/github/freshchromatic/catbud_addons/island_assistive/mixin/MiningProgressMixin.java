package github.freshchromatic.catbud_addons.island_assistive.mixin;

import github.freshchromatic.catbud_addons.island_assistive.MiningClientAccess;
import github.freshchromatic.catbud_addons.island_assistive.MiningProgressAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MiningProgressMixin implements MiningProgressAccess {

    @Shadow private float destroyProgress;

    @Override
    public float islandAssistive$destroyProgress() {
        return destroyProgress;
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void islandAssistive$destroyed(BlockPos pos, CallbackInfoReturnable<Boolean> ci) {
        var session = ((MiningClientAccess) Minecraft.getInstance()).islandAssistive$mining();
        if (ci.getReturnValueZ() && session != null) {
            session.blockDestroyed(pos);
        }
    }
}
