package github.freshchromatic.catbud_addons.island_assistive;

import com.mojang.blaze3d.platform.InputConstants;
import github.freshchromatic.catbud_addons.catbud_core.CatbudCore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.client.KeyMapping;

public final class IslandAssistiveClient implements ClientModInitializer {

    private final MobObservationSource observations = new MobObservationSource();

    private MobAlertSession    session;
    private AlertSoundPlayer   sound;
    private ClientLevel        world;
    private int                scanTicks;
    private BlockMiningSession mining;
    private KeyMapping         miningKey;

    @Override
    public void onInitializeClient() {
        var client = Minecraft.getInstance();
        sound = new AlertSoundPlayer(client);
        session = new MobAlertSession(() -> System.nanoTime() / 1_000_000, new MobAlertChat(client), sound);
        var watchlist = new MobWatchlistStore(CatbudCore.configPath("island_assistive_watchlist"),
            type -> BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(type)));
        String version = FabricLoader.getInstance().getModContainer("catbud_island_assistive").orElseThrow()
            .getMetadata().getVersion().getFriendlyString();
        mining = new BlockMiningSession(client, new BlockMiningTargetStore(CatbudCore.configPath(
            "island_assistive_mining_target")));
        ((MiningClientAccess) client).islandAssistive$mining(mining);
        miningKey = ClientPlatform.registerKey("island.mining.key", InputConstants.KEY_DELETE, Identifier.parse(
            "catbud_island_assistive:mining"));
        new IslandAssistiveSettings(version, session, watchlist, sound, mining);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> player == client.player ? mining.select(hit)
            : InteractionResult.PASS);
        ClientTickEvents.START_CLIENT_TICK.register(minecraft -> {
            while (miningKey.consumeClick()) {
                mining.toggle();
            }
            mining.tick();
        });
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (session.enabled()) {
                observations.loaded(entity, level);
            }
        });
        ClientEntityEvents.ENTITY_UNLOAD.register(observations::unloaded);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> reset());
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> mining.backgroundTick());
        MobAlertCommands.register(session);
    }

    private void reset() {
        mining.stop();
        session.reset();
        observations.world(null);
        world = null;
        scanTicks = 0;
    }

    private void tick(Minecraft client) {
        if (!session.enabled()) {
            if (world != null) {
                observations.world(null);
                world = null;
            }
            return;
        }
        session.tick();
        if (client.level == null || client.player == null) {
            return;
        }
        if (world != client.level || !session.hasWorld()) {
            world = client.level;
            observations.world(world);
            session.worldChanged(world.dimension().identifier().toString());
            scanTicks = 0;
        }
        if (scanTicks-- <= 0) {
            session.observe(observations.nearby(client, session.radius()));
            scanTicks = 4;
        }
    }

    MobAlertSession session() {
        return session;
    }

    MobObservationSource observations() {
        return observations;
    }

    AlertSoundPlayer sound() {
        return sound;
    }

    BlockMiningSession mining() {
        return mining;
    }
}
