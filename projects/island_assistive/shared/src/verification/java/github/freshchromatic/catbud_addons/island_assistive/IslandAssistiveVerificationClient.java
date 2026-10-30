package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.JsonPrimitive;
import github.freshchromatic.catbud_addons.catbud_core.CatbudCore;
import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import github.freshchromatic.catbud_addons.catbud_core.SettingsAddon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class IslandAssistiveVerificationClient implements ClientModInitializer {

    private static final String TARGET_NAME = "Island Assistive verification target";

    private final List<String> results = new ArrayList<>();

    private int                     stage;
    private long                    next;
    private long                    started;
    private boolean                 done;
    private IslandAssistiveClient   addon;
    private SettingsAddon           settings;
    private Entity                  target;
    private ClickEvent.RunCommand   firstBatch;
    private ClickEvent.RunCommand   lastAcknowledgement;
    private SoundInstance           firstSound;
    private CompletableFuture<Void> resourceReload;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (done || !client.isGameLoadFinished()) {
                return;
            }
            if (started == 0) {
                started = System.currentTimeMillis();
                client.options.pauseOnLostFocus = false;
            }
            try {
                check(System.currentTimeMillis() - started < 240_000, "Within verification timeout");
                if (VerificationPlatform.loading(client) || resourceReload != null && !resourceReload.isDone() || System
                    .currentTimeMillis() < next || !VerificationPlatform.prepareWorld(client)) {
                    return;
                }
                verify(client);
            } catch (Throwable failure) {
                done = true;
                results.add("FAIL: " + failure);
                if (settings != null) {
                    try {
                        enabled(false);
                    } catch (Exception cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                write(client);
                throw new IllegalStateException("Island Assistive verification failed", failure);
            }
        });
    }

    private void verify(Minecraft client) throws Exception {
        switch (stage++) {
            case 0 -> {
                addon = FabricLoader.getInstance().getEntrypoints("client", ClientModInitializer.class).stream().filter(
                    IslandAssistiveClient.class::isInstance).map(IslandAssistiveClient.class::cast).findFirst()
                    .orElseThrow();
                settings = CatbudCore.addons().stream().filter(value -> value.id().equals("island_assistive"))
                    .findFirst().orElseThrow();
                check(!settings.store().current().bool("enabled"), "Default disabled");
                check(settings.store().current().integer("radius") == 256, "Default radius 256");
                check(new MobWatchlistStore(CatbudCore.configPath("island_assistive_watchlist"), type -> true).rules()
                    .equals(MobWatchlistStore.DEFAULT_RULES), "Default watchlist is zombie villagers");
                check(addon.observations().loadedCount() == 0, "Disabled does not scan or track mobs");
                check(client.getResourceManager().getResource(Identifier.parse(
                    "catbud_island_assistive:sounds/alert.ogg")).isPresent(), "Bundled OGG available");
                check(client.getSoundManager().getSoundEvent(AlertSoundPlayer.SOUND_ID) != null,
                    "Sound event registered by resources");
                check(!Component.translatable("island.chat.ack").getString().equals("island.chat.ack"),
                    "Language resources loaded");
                spawn(client, 1);
                enabled(true);
                addon.session().configure(true, 256, true, List.of(new MobWatchRule("minecraft:zombie_villager",
                    MobWatchRule.NameMatch.EXACT, TARGET_NAME)));
                waitMillis(2_800);
            }
            case 1 -> {
                check(addon.session().pending().isEmpty(), "Initial mob is baseline only");
                check(addon.sound().current() == null, "Baseline does not play alert");
                target = spawn(client, 2);
                waitMillis(600);
            }
            case 2 -> {
                check(addon.session().pending().size() == 1, "Entity load triggers new target alert");
                firstSound = addon.sound().current();
                check(firstSound != null && firstSound.isRelative() && firstSound
                    .getAttenuation() == SoundInstance.Attenuation.NONE, "Alert uses non-positional sound");
                var buttons = chatButtons(client);
                check(buttons.size() == 2, "Chat message includes both acknowledgement buttons");
                firstBatch = buttons.get(1);
                target.discard();
                check(addon.session().pending().size() == 1, "Target removal keeps alert pending");
                spawn(client, 3);
                waitMillis(600);
            }
            case 3 -> {
                check(addon.session().pending().size() == 2, "Multiple targets share active alarm");
                lastAcknowledgement = chatButtons(client).getFirst();
                ClickProbe.click(firstBatch);
                check(addon.session().pending().size() == 1, "Real chat click preserves later target");
                ClientPlatform.show(client, new ChatScreen("", false));
                waitMillis(700);
            }
            case 4 -> {
                VerificationPlatform.capture(client, "island-alert-chat.png");
                resourceReload = client.reloadResourcePacks();
                waitMillis(5_000);
            }
            case 5 -> {
                check(!resourceReload.isCompletedExceptionally(), "Resource reload completed");
                check(addon.sound().current() != firstSound && addon.sound().current() != null,
                    "Repeating alarm replaces sound instance after five seconds");
                check(addon.sound().current().getSound() != null && addon.sound().current()
                    .getSound() != SoundManager.EMPTY_SOUND, "Reloaded alarm resolves to a playable sound");
                ClickProbe.click(lastAcknowledgement);
                check(addon.session().pending().isEmpty() && addon.sound().current() == null,
                    "Real chat click clears final target and stops sound");
                enabled(false);
                VerificationPlatform.resize(client, 1048, 620);
                client.options.guiScale().set(2);
                VerificationPlatform.resizeGui(client);
                settings.status().get().stream().filter(row -> row.label().equals(Component.translatable(
                    "island.watchlist.title"))).findFirst().orElseThrow().action().run();
                check(ClientPlatform.screen(client) instanceof MobWatchlistScreen, "Core action opens watchlist");
                waitMillis(700);
            }
            case 6 -> {
                VerificationPlatform.capture(client, "island-watchlist.png");
                var screen = ClientPlatform.screen(client);
                var inputs = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                    .toList();
                inputs.getFirst().setValue("minecraft:zombie");
                press(screen, "island.watchlist.mode.any");
                screen = ClientPlatform.screen(client);
                inputs = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
                inputs.get(1).setValue("Rare Zombie");
                press(screen, "island.watchlist.add");
                press(screen, "island.watchlist.save");
                var saved = new MobWatchlistStore(CatbudCore.configPath("island_assistive_watchlist"), type -> true);
                check(saved.rules().size() == 2 && saved.rules().get(1).name().equals("Rare Zombie"),
                    "Watchlist UI adds and persists custom-name rule");
                saved.save(MobWatchlistStore.DEFAULT_RULES);
                verifyCompactLayout(client, saved);
                verifyObservationCost(client);
                results.add("PASS: All Island Assistive client checks");
                done = true;
                write(client);
                client.stop();
            }
            default -> throw new IllegalStateException("Unexpected verification stage");
        }
    }

    private void verifyCompactLayout(Minecraft client, MobWatchlistStore store) {
        var screen = new MobWatchlistScreen(null, store, () -> {
        });
        ClientPlatform.show(client, screen);
        screen.resize(320, 240);
        var widgets = screen.children().stream().filter(AbstractWidget.class::isInstance).map(
            AbstractWidget.class::cast).toList();
        for (int first = 0; first < widgets.size(); first++) {
            var a = widgets.get(first);
            for (int second = first + 1; second < widgets.size(); second++) {
                var b = widgets.get(second);
                boolean overlapping = a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth() && a
                    .getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
                if (overlapping) {
                    throw new IllegalStateException("Compact layout overlap: " + a.getMessage().getString() + " / " + b
                        .getMessage().getString());
                }
            }
        }
        check(widgets.stream().allMatch(widget -> widget.getX() >= 0 && widget.getY() >= 0 && widget.getX() + widget
            .getWidth() <= 320 && widget.getY() + widget.getHeight() <= 240),
            "Compact 320x240 watchlist controls fit without overlap");
    }

    private void verifyObservationCost(Minecraft client) {
        var source = new MobObservationSource();
        source.world(client.level);
        for (int index = 0; index < 1_000; index++) {
            var mob = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:zombie_villager")).create(
                client.level, EntitySpawnReason.LOAD);
            mob.setId(2_000_000 + index);
            mob.setPos(client.player.getX() + index % 200, client.player.getY() + index % 3, client.player.getZ());
            source.loaded(mob, client.level);
        }
        var snapshots = source.nearby(client, 256);
        check(snapshots.size() >= 1_000, "Thousand-mob observation includes all nearby targets");
        var distant = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:zombie_villager")).create(
            client.level, EntitySpawnReason.LOAD);
        distant.setId(3_000_000);
        distant.setPos(client.player.getX(), client.player.getY() + 257, client.player.getZ());
        source.loaded(distant, client.level);
        source.loaded(client.player, client.level);
        check(source.nearby(client, 256).stream().noneMatch(mob -> mob.uuid().equals(distant.getUUID()) || mob.uuid()
            .equals(client.player.getUUID())), "3D radius and player exclusion enforced");
        distant.setPos(client.player.getX(), client.player.getY() + 256, client.player.getZ());
        check(source.nearby(client, 256).stream().anyMatch(mob -> mob.uuid().equals(distant.getUUID())),
            "Already loaded mob is observed when entering radius");
        long start = System.nanoTime();
        for (int index = 0; index < 20; index++) {
            source.nearby(client, 256);
        }
        results.add("INFO: 1000-mob scan average " + (System.nanoTime() - start) / 20_000_000.0 + " ms");
        source.world(null);
        check(source.loadedCount() == 0, "Observation cleanup releases entity references");
    }

    private static Entity spawn(Minecraft client, int index) {
        var mob = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:zombie_villager")).create(
            client.level, EntitySpawnReason.LOAD);
        mob.setId(1_000_000 + index);
        mob.setCustomName(Component.literal(TARGET_NAME));
        mob.setPos(client.player.getX() + 2 + index, client.player.getY(), client.player.getZ());
        client.level.addEntity(mob);
        return mob;
    }

    private void enabled(boolean enabled) throws Exception {
        var values = settings.store().current();
        var option = settings.settings().stream().filter(value -> value.id().equals("enabled")).findFirst()
            .orElseThrow();
        values.set(option, new JsonPrimitive(enabled));
        settings.store().apply(values);
    }

    private static List<ClickEvent.RunCommand> chatButtons(Minecraft client) throws Exception {
        Object chat = VerificationPlatform.chat(client);
        var field = chat.getClass().getDeclaredField("allMessages");
        field.setAccessible(true);
        var messages = (List<?>) field.get(chat);
        for (Object message : messages) {
            var content = (Component) message.getClass().getMethod("content").invoke(message);
            var buttons = new ArrayList<ClickEvent.RunCommand>();
            content.visit((style, text) -> {
                if (style.getClickEvent() instanceof ClickEvent.RunCommand command && command.command().startsWith("/"
                    + MobAlertChat.COMMAND)) {
                    buttons.add(command);
                }
                return Optional.<Void>empty();
            }, Style.EMPTY);
            if (!buttons.isEmpty()) {
                return buttons;
            }
        }
        throw new IllegalStateException("No alert buttons in live chat");
    }

    private static void press(Screen screen, String key) {
        var button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(
            value -> value.getMessage().equals(Component.translatable(key))).findFirst().orElseThrow();
        button.onPress(null);
    }

    private void waitMillis(long millis) {
        next = System.currentTimeMillis() + millis;
    }

    private void check(boolean success, String message) {
        if (!success) {
            throw new IllegalStateException(message);
        }
        if (!message.equals("Within verification timeout")) {
            results.add("PASS: " + message);
        }
    }

    private void write(Minecraft client) {
        try {
            Files.write(client.gameDirectory.toPath().resolve("island-assistive-verification.txt"), results);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write verification results", error);
        }
    }

    private static final class ClickProbe extends Screen {

        private ClickProbe() {
            super(Component.empty());
        }

        static void click(ClickEvent event) {
            defaultHandleGameClickEvent(event, Minecraft.getInstance(), null);
        }
    }
}
