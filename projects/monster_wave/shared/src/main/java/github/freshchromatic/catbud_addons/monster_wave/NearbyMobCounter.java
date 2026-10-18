package github.freshchromatic.catbud_addons.monster_wave;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

final class NearbyMobCounter {

    static final int RADIUS = 8;

    private Object world;
    private long   sampledAt;
    private int    count = -1;

    void tick(Minecraft client, boolean active) {
        if (!active || client.level == null || client.player == null) {
            world = null;
            count = -1;
            return;
        }
        long now = System.nanoTime();
        if (world == client.level && now - sampledAt < 1_000_000_000L) {
            return;
        }
        world = client.level;
        sampledAt = now;
        count = client.level.getEntitiesOfClass(Mob.class, client.player.getBoundingBox().inflate(RADIUS),
            mob -> mob instanceof Enemy && mob.isAlive() && mob.distanceToSqr(client.player) <= RADIUS * RADIUS).size();
    }

    int count(Minecraft client) {
        return world == client.level ? count : -1;
    }
}
