package github.freshchromatic.catbud_addons.monster_wave.mixin;

import io.netty.channel.Channel;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ConnectionAccessor {

    @Accessor("channel")
    Channel monsterWave$getChannel();

    @Accessor("disconnectionDetails")
    void monsterWave$setDisconnectionDetails(DisconnectionDetails details);
}
