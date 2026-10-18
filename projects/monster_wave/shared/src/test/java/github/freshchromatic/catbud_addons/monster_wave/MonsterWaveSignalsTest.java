package github.freshchromatic.catbud_addons.monster_wave;

import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static github.freshchromatic.catbud_addons.monster_wave.BossbarTracker.Signal;

class MonsterWaveSignalsTest {

    @Test
    void recognizesNestedQueueKeyAndSiblingsWithoutConfusingTower() {
        var queue = Component.translatable(MonsterWaveSignals.QUEUE, Component.empty().append(Component.translatable(
            MonsterWaveSignals.WAVE)), "1");
        assertEquals(Signal.QUEUE, MonsterWaveSignals.signal(queue));
        assertEquals(Signal.NONE, MonsterWaveSignals.signal(Component.translatable(MonsterWaveSignals.QUEUE, Component
            .translatable("plugins.magic_tower.magic_tower"), "1")));
        assertEquals(Signal.NONE, MonsterWaveSignals.signal(Component.literal("怪物浪潮")));
    }

    @Test
    void readyStartAndReadyExitAreDistinctBossbarSignals() {
        assertEquals(Signal.READY_START, MonsterWaveSignals.signal(Component.translatable(MonsterWaveSignals.START)));
        assertEquals(Signal.READY_EXIT, MonsterWaveSignals.signal(Component.translatable(MonsterWaveSignals.EXIT)));
        assertEquals(Signal.READY, MonsterWaveSignals.signal(Component.literal(MonsterWaveSignals.READY)));
        assertEquals(List.of(MonsterWaveSignals.START), MonsterWaveSignals.keys(Component.translatable(
            MonsterWaveSignals.START)));
    }

    @Test
    void bossbarTracksRawProgressStyleFlagsRemovalAndUnchangedUpdates() {
        var changes = new java.util.ArrayList<BossbarTracker.Change>();
        var tracker = new BossbarTracker(changes::add);
        UUID id = UUID.randomUUID();
        tracker.add(id, MonsterWaveSessionTest.value("first"), Signal.NONE);
        tracker.update(id, "progress", v -> new RunEvent.BossValue(v.name(), 0.25F, v.color(), v.overlay(), false,
            false, false));
        tracker.update(id, "style", v -> new RunEvent.BossValue(v.name(), v.progress(), "BLUE", "NOTCHED_10", false,
            false, false));
        tracker.update(id, "properties", v -> new RunEvent.BossValue(v.name(), v.progress(), v.color(), v.overlay(),
            true, true, true));
        tracker.name(id, MonsterWaveSessionTest.value("exit").name(), Signal.READY_EXIT);
        tracker.name(id, MonsterWaveSessionTest.value("exit").name(), Signal.READY_EXIT);
        tracker.update(id, "progress", v -> v);
        assertEquals(5, changes.size());
        var last = tracker.snapshot().getFirst();
        assertEquals(0.25F, last.value().progress());
        assertEquals("BLUE", last.value().color());
        assertTrue(last.value().createWorldFog());
        assertEquals(Signal.READY_EXIT, last.signal());
        tracker.remove(id);
        assertTrue(tracker.snapshot().isEmpty());
        assertEquals("remove", changes.getLast().operation());
    }
}
