package github.freshchromatic.catbud_addons.magic_tower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

class WorldTargetsTest {

    @Test
    @SuppressWarnings("unchecked")
    void selectedPortalSurvivesChunkUnloadUntilExplicitlyUnmarked() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        WorldTargets targets = new WorldTargets();
        BlockPos portal = new BlockPos(17, 42, 5);
        Field portalsField = WorldTargets.class.getDeclaredField("portals");
        portalsField.setAccessible(true);
        ((Map<BlockPos, WorldTargets.PortalType>) portalsField.get(targets)).put(portal,
            WorldTargets.PortalType.NETHER);
        assertTrue(targets.toggleLine(portal, Blocks.NETHER_PORTAL.defaultBlockState()));

        Method releaseChunk = WorldTargets.class.getDeclaredMethod("releaseChunk", int.class, int.class);
        releaseChunk.setAccessible(true);
        releaseChunk.invoke(targets, portal.getX() >> 4, portal.getZ() >> 4);

        assertTrue(targets.lineTargets().contains(portal));
        assertEquals(portal, targets.portalMarkers().getFirst().anchor());
        assertEquals("target.catbud_addons.nether_portal", targets.targetNameKey(portal));
        assertFalse(targets.toggleLine(portal, Blocks.NETHER_PORTAL.defaultBlockState()));
        assertFalse(targets.lineTargets().contains(portal));
    }

    @Test
    @SuppressWarnings("unchecked")
    void discoveredTargetsAndSelectionsSurviveChunkUnloadUntilFloorReset() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        WorldTargets targets = new WorldTargets();
        BlockPos portal = new BlockPos(161, 42, 5);
        BlockPos special = new BlockPos(162, 42, 5);
        BlockPos chest = new BlockPos(163, 42, 5);
        BlockPos mushroom = new BlockPos(164, 42, 5);
        ((Map<BlockPos, WorldTargets.PortalType>) field(targets, "portals")).put(portal, WorldTargets.PortalType.END);
        ((Map<BlockPos, WorldTargets.SpecialType>) field(targets, "specials")).put(special,
            WorldTargets.SpecialType.COIN_BANK);
        ((Set<BlockPos>) field(targets, "chests")).add(chest);
        ((Set<BlockPos>) field(targets, "mushrooms")).addAll(Set.of(special, mushroom));
        ((Map<BlockPos, BlockState>) field(targets, "lineTargets")).put(mushroom, Blocks.RED_MUSHROOM_BLOCK
            .defaultBlockState());
        long chunkKey = ((long) (portal.getX() >> 4) << 32) | ((portal.getZ() >> 4) & 0xffffffffL);
        ((Map<Long, ?>) field(targets, "scanned")).put(chunkKey, null);
        ((Map<Long, Long>) field(targets, "lastScannedTick")).put(chunkKey, 1L);

        Method releaseChunk = WorldTargets.class.getDeclaredMethod("releaseChunk", int.class, int.class);
        releaseChunk.setAccessible(true);
        releaseChunk.invoke(targets, portal.getX() >> 4, portal.getZ() >> 4);
        Method validateTracked = WorldTargets.class.getDeclaredMethod("validateTracked",
            net.minecraft.client.multiplayer.ClientLevel.class);
        validateTracked.setAccessible(true);
        validateTracked.invoke(targets, new Object[] { null });

        assertTrue(((Map<?, ?>) field(targets, "scanned")).isEmpty());
        assertTrue(((Map<?, ?>) field(targets, "lastScannedTick")).isEmpty());
        assertEquals(portal, targets.portalMarkers().getFirst().anchor());
        assertEquals(special, targets.specialMarkers().getFirst().anchor());
        assertEquals(Set.of(chest), targets.chests());
        assertEquals(Set.of(special, mushroom), targets.mushrooms());
        assertEquals(Set.of(mushroom), targets.lineTargets());
        assertTrue(((Set<?>) field(targets, "usedChests")).isEmpty());

        targets.clear();

        assertTrue(targets.portalMarkers().isEmpty());
        assertTrue(targets.specialMarkers().isEmpty());
        assertTrue(targets.chests().isEmpty());
        assertTrue(targets.mushrooms().isEmpty());
        assertTrue(targets.lineTargets().isEmpty());
    }

    private Object field(WorldTargets targets, String name) throws Exception {
        Field field = WorldTargets.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(targets);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingPortalIsUnmarkedWhenItsChunkIsScannedAgain() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        WorldTargets targets = new WorldTargets();
        BlockPos portal = new BlockPos(17, 42, 5);
        Field portalsField = WorldTargets.class.getDeclaredField("portals");
        portalsField.setAccessible(true);
        Map<BlockPos, WorldTargets.PortalType> portals = (Map<BlockPos, WorldTargets.PortalType>) portalsField.get(
            targets);
        portals.put(portal, WorldTargets.PortalType.NETHER);
        targets.toggleLine(portal, Blocks.NETHER_PORTAL.defaultBlockState());
        portals.clear();

        Field scannedField = WorldTargets.class.getDeclaredField("scanned");
        scannedField.setAccessible(true);
        long chunkKey = ((long) (portal.getX() >> 4) << 32) | ((portal.getZ() >> 4) & 0xffffffffL);
        ((Map<Long, ?>) scannedField.get(targets)).put(chunkKey, null);
        Method validateTracked = WorldTargets.class.getDeclaredMethod("validateTracked",
            net.minecraft.client.multiplayer.ClientLevel.class);
        validateTracked.setAccessible(true);
        validateTracked.invoke(targets, new Object[] { null });

        assertFalse(targets.lineTargets().contains(portal));
    }
}
