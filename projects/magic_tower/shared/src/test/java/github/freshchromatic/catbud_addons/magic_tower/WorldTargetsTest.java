package github.freshchromatic.catbud_addons.magic_tower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

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
        ((Map<BlockPos, WorldTargets.PortalType>) portalsField.get(targets))
            .put(portal, WorldTargets.PortalType.NETHER);
        assertTrue(targets.toggleLine(portal, Blocks.NETHER_PORTAL.defaultBlockState()));

        Method removeChunk = WorldTargets.class.getDeclaredMethod("removeChunk", int.class, int.class);
        removeChunk.setAccessible(true);
        removeChunk.invoke(targets, portal.getX() >> 4, portal.getZ() >> 4);

        assertTrue(targets.lineTargets().contains(portal));
        assertEquals(portal, targets.portalMarkers().getFirst().anchor());
        assertEquals("target.catbud_addons.nether_portal", targets.targetNameKey(portal));
        assertFalse(targets.toggleLine(portal, Blocks.NETHER_PORTAL.defaultBlockState()));
        assertFalse(targets.lineTargets().contains(portal));
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
        Map<BlockPos, WorldTargets.PortalType> portals =
            (Map<BlockPos, WorldTargets.PortalType>) portalsField.get(targets);
        portals.put(portal, WorldTargets.PortalType.NETHER);
        targets.toggleLine(portal, Blocks.NETHER_PORTAL.defaultBlockState());
        portals.clear();

        Field scannedField = WorldTargets.class.getDeclaredField("scanned");
        scannedField.setAccessible(true);
        long chunkKey = ((long) (portal.getX() >> 4) << 32) | ((portal.getZ() >> 4) & 0xffffffffL);
        ((Map<Long, ?>) scannedField.get(targets)).put(chunkKey, null);
        Method validateTracked = WorldTargets.class.getDeclaredMethod(
            "validateTracked", net.minecraft.client.multiplayer.ClientLevel.class);
        validateTracked.setAccessible(true);
        validateTracked.invoke(targets, new Object[] {null});

        assertFalse(targets.lineTargets().contains(portal));
    }
}
