package github.freshchromatic.catbud_addons.island_assistive;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class AlertSoundPlayer implements AlertSound {

    public static final Identifier SOUND_ID = Identifier.fromNamespaceAndPath("catbud_island_assistive", "alert");

    private final Minecraft client;
    private SoundInstance   current;
    private float           volume = 1;

    public AlertSoundPlayer(Minecraft client) {
        this.client = client;
    }

    public void volume(float volume) {
        this.volume = volume;
    }

    SoundInstance current() {
        return current;
    }

    @Override
    public void play() {
        stop();
        current = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(SOUND_ID), 1, volume);
        client.getSoundManager().play(current);
    }

    @Override
    public void stop() {
        if (current != null) {
            client.getSoundManager().stop(current);
            current = null;
        }
    }
}
