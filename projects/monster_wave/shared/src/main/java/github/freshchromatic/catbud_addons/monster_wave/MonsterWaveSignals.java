package github.freshchromatic.catbud_addons.monster_wave;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;

final class MonsterWaveSignals {

    static final String QUEUE = "plugins.venue_manager.room_queuing_distance";
    static final String WAVE  = "plugins.monster_wave.monster_wave";
    static final String READY = "plugins.venue_manager.venue_waiting_activate";
    static final String START = "plugins.monster_wave.ready_start";
    static final String EXIT  = "plugins.monster_wave.ready_exit";

    private MonsterWaveSignals() {
    }

    static List<String> keys(Component text) {
        var keys = new ArrayList<String>();
        collect(text, keys);
        return List.copyOf(keys);
    }

    private static void collect(Component text, List<String> keys) {
        if (text.getContents() instanceof TranslatableContents translated) {
            keys.add(translated.getKey());
            for (Object argument : translated.getArgs()) {
                if (argument instanceof Component nested) {
                    collect(nested, keys);
                }
            }
        }
        for (Component sibling : text.getSiblings()) {
            collect(sibling, keys);
        }
    }

    static BossbarTracker.Signal signal(Component text) {
        List<String> keys = keys(text);
        if (keys.contains(QUEUE) && keys.contains(WAVE)) {
            return BossbarTracker.Signal.QUEUE;
        }
        if (keys.contains(READY) || renderedReady(text)) {
            return BossbarTracker.Signal.READY;
        }
        if (keys.contains(START)) {
            return BossbarTracker.Signal.READY_START;
        }
        if (keys.contains(EXIT)) {
            return BossbarTracker.Signal.READY_EXIT;
        }
        return BossbarTracker.Signal.NONE;
    }

    private static boolean renderedReady(Component text) {
        String rendered = text.getString();
        String localized = Language.getInstance().getOrDefault(READY);
        return rendered.contains(READY) || !localized.equals(READY) && rendered.contains(localized);
    }
}
