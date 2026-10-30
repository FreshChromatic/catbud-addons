package github.freshchromatic.catbud_addons.island_assistive;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MobObservationSource {

    private final Map<Integer, Mob> loaded = new HashMap<>();
    private ClientLevel             world;

    public void loaded(Entity entity, ClientLevel world) {
        if (this.world == world && entity instanceof Mob mob) {
            loaded.put(entity.getId(), mob);
        }
    }

    public void unloaded(Entity entity, ClientLevel world) {
        if (this.world == world) {
            loaded.remove(entity.getId());
        }
    }

    public void world(ClientLevel world) {
        loaded.clear();
        this.world = world;
        if (world != null) {
            for (Entity entity : world.entitiesForRendering()) {
                loaded(entity, world);
            }
        }
    }

    public List<MobSnapshot> nearby(Minecraft client, double radius) {
        var result = new ArrayList<MobSnapshot>();
        double squaredRadius = radius * radius;
        for (Mob mob : loaded.values()) {
            if (!mob.isAlive() || mob.isRemoved()) {
                continue;
            }
            double squaredDistance = mob.distanceToSqr(client.player);
            if (squaredDistance > squaredRadius) {
                continue;
            }
            result.add(new MobSnapshot(mob.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(),
                mob.hasCustomName() ? mob.getCustomName().getString() : "", mob.getName().getString(), mob.getX(), mob
                    .getY(), mob.getZ(), Math.sqrt(squaredDistance)));
        }
        return result;
    }

    public int loadedCount() {
        return loaded.size();
    }
}
