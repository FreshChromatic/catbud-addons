package github.freshchromatic.catbud_addons.monster_wave.mixin;

import github.freshchromatic.catbud_addons.monster_wave.MonsterWaveClient;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Mixin(SectionOcclusionGraph.class)
public class SectionOcclusionGraphMixin {

    @Redirect(method = "waitAndReset", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/Future;get()Ljava/lang/Object;"))
    private Object monsterWave$awaitCleanup(Future<?> task, ViewArea viewArea) throws InterruptedException,
        ExecutionException {
        return MonsterWaveClient.awaitWorldCleanup(task, viewArea == null);
    }
}
