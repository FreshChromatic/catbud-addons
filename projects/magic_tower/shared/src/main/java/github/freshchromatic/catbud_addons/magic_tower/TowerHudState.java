package github.freshchromatic.catbud_addons.magic_tower;

import net.minecraft.client.Minecraft;

final class TowerHudState {

    private final TowerSession       session;
    private final TowerCoinInventory coins = new TowerCoinInventory();

    TowerHudState(TowerSession session) {
        this.session = session;
    }

    TowerSession session() {
        return session;
    }

    TowerCoinInventory.Snapshot coins() {
        return coins.snapshot();
    }

    boolean visible() {
        Minecraft client = Minecraft.getInstance();
        return session.isActive() && client.player != null && client.level != null;
    }

    void tick(Minecraft client) {
        if (visible()) {
            coins.tick(client.player.getInventory());
        } else {
            coins.reset();
        }
    }
}
