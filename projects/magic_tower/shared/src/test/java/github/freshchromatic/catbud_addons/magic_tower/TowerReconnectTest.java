package github.freshchromatic.catbud_addons.magic_tower;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

class TowerReconnectTest {

    private static final Identifier CUSTOM = Identifier.fromNamespaceAndPath("minecraft", "custom");
    private static final UUID       PLAYER = UUID.randomUUID();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void restoresFloorEvacuationAndSelectedTargetsAfterRepeatedOfflineTicks() throws Exception {
        TowerSession session = activeSession(false);
        BlockPos chest = new BlockPos(12, 64, 8);
        addChest(session.targets(), chest);
        assertTrue(session.targets().toggleLine(chest, Blocks.CHEST.defaultBlockState()));
        long epoch = session.floorEpoch();

        session.disconnect();
        session.disconnect();
        session.joinConnection();
        assertFalse(session.isActive());
        session.onMessage(Component.translatable(TowerAnnouncementParser.FLOOR, 99), "Title");
        session.updateConnection("tower.example", PLAYER, CUSTOM);

        assertTrue(session.isActive());
        assertEquals("auto", session.modeKey());
        assertEquals("active", session.phaseKey());
        assertEquals(OptionalInt.of(12), session.floor());
        assertEquals(TowerSession.EvacuationStatus.PRESENT, session.evacuationStatus());
        assertEquals(epoch, session.floorEpoch());
        assertTrue(session.targets().chests().contains(chest));
        assertTrue(session.targets().lineTargets().contains(chest));
    }

    @Test
    void restoresForcedMode() throws Exception {
        TowerSession session = activeSession(true);
        session.disconnect();
        session.joinConnection();
        session.updateConnection("tower.example", PLAYER, CUSTOM);
        assertTrue(session.isActive());
        assertEquals("forced", session.modeKey());
        assertEquals(OptionalInt.of(12), session.floor());
    }

    @Test
    void discardsStateWhenLoginIsOutsideCustom() throws Exception {
        assertDiscarded("tower.example", PLAYER, Identifier.fromNamespaceAndPath("minecraft", "overworld"));
    }

    @Test
    void discardsStateForAnotherServerOrPlayer() throws Exception {
        assertDiscarded("other.example", PLAYER, CUSTOM);
        assertDiscarded("tower.example", UUID.randomUUID(), CUSTOM);
        assertDiscarded(null, PLAYER, CUSTOM);
    }

    @Test
    void queuedSessionsAreNotResumed() throws Exception {
        TowerSession session = new TowerSession();
        setEnum(session, "phase", "QUEUED");
        session.updateConnection("tower.example", PLAYER, CUSTOM);
        setField(session, "lastDimension", CUSTOM);
        session.disconnect();
        session.joinConnection();
        session.updateConnection("tower.example", PLAYER, CUSTOM);
        assertEquals("idle", session.phaseKey());
        assertFalse(session.isActive());
    }

    @Test
    @SuppressWarnings("unchecked")
    void disconnectReleasesLoadedChunksAndWorldReferences() throws Exception {
        TowerSession session = activeSession(false);
        WorldTargets targets = session.targets();
        ((Map<Long, Object>) field(targets, "scanned")).put(1L, null);
        ((Map<Long, Long>) field(targets, "lastScannedTick")).put(1L, 2L);
        setField(session, "lastLevel", new Object());

        session.disconnect();

        assertTrue(((Map<?, ?>) field(targets, "scanned")).isEmpty());
        assertTrue(((Map<?, ?>) field(targets, "lastScannedTick")).isEmpty());
        assertNull(field(session, "lastLevel"));
        assertNull(field(session, "lastDimension"));
        assertNull(field(session, "lastPosition"));
    }

    @Test
    void manualStopDiscardsSuspendedState() throws Exception {
        TowerSession session = activeSession(false);
        session.disconnect();
        session.forceStop();
        session.joinConnection();
        session.updateConnection("tower.example", PLAYER, CUSTOM);
        assertFalse(session.isActive());
        assertTrue(session.floor().isEmpty());
    }

    private static void assertDiscarded(String server, UUID player, Identifier dimension) throws Exception {
        TowerSession session = activeSession(false);
        addChest(session.targets(), new BlockPos(12, 64, 8));
        session.disconnect();
        session.joinConnection();
        session.updateConnection(server, player, dimension);
        assertFalse(session.isActive());
        assertEquals("idle", session.phaseKey());
        assertTrue(session.floor().isEmpty());
        assertEquals(TowerSession.EvacuationStatus.UNKNOWN, session.evacuationStatus());
        assertTrue(session.targets().chests().isEmpty());
        session.updateConnection("tower.example", PLAYER, CUSTOM);
        assertFalse(session.isActive());
    }

    private static TowerSession activeSession(boolean forced) throws Exception {
        TowerSession session = new TowerSession();
        setEnum(session, forced ? "manualMode" : "phase", forced ? "FORCED" : "ACTIVE");
        session.updateConnection("tower.example", PLAYER, CUSTOM);
        setField(session, "lastDimension", CUSTOM);
        session.onMessage(Component.translatable(TowerAnnouncementParser.FLOOR, 12), "Title");
        session.onMessage(Component.translatable("plugins.magic_tower.evacuation_room_appeared"), "Subtitle");
        return session;
    }

    @SuppressWarnings("unchecked")
    private static void addChest(WorldTargets targets, BlockPos pos) throws Exception {
        ((Set<BlockPos>) field(targets, "chests")).add(pos);
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void setEnum(TowerSession session, String name, String value) throws Exception {
        var field = TowerSession.class.getDeclaredField(name);
        field.setAccessible(true);
        Object constant = java.util.Arrays.stream(field.getType().getEnumConstants()).filter(candidate -> candidate
            .toString().equals(value)).findFirst().orElseThrow();
        field.set(session, constant);
    }
}
