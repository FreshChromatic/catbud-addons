package github.freshchromatic.catbud_addons.magic_tower.mixin;

import github.freshchromatic.catbud_addons.magic_tower.CatbudAddonsClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    @Inject(method = "setBlock", at = @At("TAIL"))
    private void catbud$onBlockChanged(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                       CallbackInfoReturnable<Boolean> ci) {
        if (ci.getReturnValue())
            CatbudAddonsClient.onWorldBlockChanged((ClientLevel) (Object) this, pos);
    }
}
