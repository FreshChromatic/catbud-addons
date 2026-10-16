package github.freshchromatic.catbud_addons.public_warps;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.*;

import java.util.*;

/** Frozen vanilla entities used only for rendering, never added to the live client world. */
public final class PreviewEntities {

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID = new java.util.concurrent.atomic.AtomicInteger(
        -1);

    public Entity create(BlockSnapshot.SnapshotEntity saved) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        try {
            Entity entity = saved.type().equals("minecraft:player") ? new RemotePlayer(mc.level, new GameProfile(UUID
                .fromString(saved.playerUUID()), saved.playerName()))
                : BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(saved.type())).create(mc.level,
                    EntitySpawnReason.LOAD);
            if (entity == null) {
                return null;
            }
            // Modern renderers require an assigned ID even for detached preview
            // entities.
            entity.setId(NEXT_ID.getAndDecrement());
            EntityVisual.apply(entity, saved.appearance());
            entity.setPos(saved.x(), saved.y(), saved.z());
            entity.setYRot(saved.yaw());
            entity.setXRot(saved.pitch());
            entity.setOldPosAndRot();
            entity.yRotO = saved.yaw();
            entity.xRotO = saved.pitch();
            if (entity instanceof LivingEntity living) {
                living.yBodyRot = living.yBodyRotO = saved.yaw();
                living.yHeadRot = living.yHeadRotO = saved.yaw();
            }

            return entity;
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger("Catbud Public Warps").debug("Unsupported preview entity {}", saved
                .type(), failure);
            return null;
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public EntityRenderState state(Entity entity) {
        EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        EntityRenderState state = renderer.createRenderState(entity, 0);
        state.nameTag = null;
        state.lightCoords = 0xF000F0;
        state.shadowPieces.clear();
        state.shadowRadius = 0;
        state.leashStates = null;
        return state;
    }
}
