package github.freshchromatic.catbud_addons.island_assistive.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientInput.class)
public interface MiningMovementAccessor {

    @Accessor("moveVector")
    void islandAssistive$moveVector(Vec2 vector);
}
