package github.freshchromatic.catbud_addons.island_assistive.study.mixin;

import github.freshchromatic.catbud_addons.island_assistive.BlockMiningSession;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = BlockMiningSession.class, remap = false)
public interface StudySessionAccess {

    @Invoker("targetPos")
    BlockPos study$target();

    @Invoker("pause")
    void study$pause(String reason);
}
