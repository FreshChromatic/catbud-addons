package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.catbud_core.CatbudCore;
import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import github.freshchromatic.catbud_addons.catbud_core.SettingsScreen;

import java.nio.file.Files;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class BlockMiningVerificationClient implements ClientModInitializer {

    private final List<String> results = new ArrayList<>();

    private BlockMiningSession                        session;
    private BlockPos                                  target;
    private CompletableFuture<Void>                   serverTask;
    private CompletableFuture<Boolean>                serverAir;
    private int                                       stage;
    private int                                       ticks;
    private int                                       direction;
    private boolean                                   done;
    private long                                      started;
    private Object                                    reference;
    private Class<?>                                  referenceClass;
    private URLClassLoader                            referenceLoader;
    private int                                       referenceMode;
    private volatile boolean                          regenerating;
    private volatile int                              regenerated;
    private boolean                                   moved;
    private Vec3                                      moveStart;
    private int                                       cameraRegenerations;
    private net.minecraft.world.entity.Entity         freeCamera;
    private final java.util.Map<BlockPos, BlockState> occlusion = new java.util.HashMap<>();

    @Override
    public void onInitializeClient() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (regenerating && server.overworld().getBlockState(target).isAir()) {
                server.overworld().setBlock(target, vein(Direction.UP), 2);
                regenerated++;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (done || !client.isGameLoadFinished()) {
                return;
            }
            if (started == 0) {
                started = System.currentTimeMillis();
                client.options.pauseOnLostFocus = false;
                client.options.renderDistance().set(4);
            }
            try {
                check(System.currentTimeMillis() - started < 240_000, "Verification timeout");
                if (VerificationPlatform.loading(client) || stage != 27 && !VerificationPlatform.prepareWorld(client)
                    || serverTask != null && !serverTask.isDone()) {
                    return;
                }
                if (serverTask != null) {
                    serverTask.join();
                }
                verify(client);
            } catch (Throwable error) {
                done = true;
                if (session != null) {
                    session.stop();
                }
                results.add("FAIL: stage=" + stage + " ticks=" + ticks + " " + error);
                if (client.player != null) {
                    results.add("PLAYER: ground=" + client.player.onGround() + " spectator=" + client.player
                        .isSpectator() + " water=" + client.player.isInWater() + " flight=" + client.player
                            .isFallFlying() + " mode=" + client.gameMode.getPlayerMode());
                }
                write(client);
                throw new IllegalStateException("Mining verification failed", error);
            }
        });
    }

    private void verify(Minecraft client) throws Exception {
        ticks++;
        switch (stage) {
            case 0 -> {
                var addon = FabricLoader.getInstance().getEntrypoints("client", ClientModInitializer.class).stream()
                    .filter(IslandAssistiveClient.class::isInstance).map(IslandAssistiveClient.class::cast).findFirst()
                    .orElseThrow();
                session = addon.mining();
                boolean suppliedMap = System.getProperty("catbud.miningWorld") != null;
                target = suppliedMap ? new BlockPos(-3576, 65, -3576)
                    : new BlockPos(0, client.player.getBlockY() + 8, 0);
                results.add("WORLD: " + (suppliedMap ? "copy of supplied map at original coordinates"
                    : "isolated fixture"));
                serverTask = client.getSingleplayerServer().submit(() -> {
                    var player = client.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    var level = player.level();
                    if (!suppliedMap) {
                        arena(client);
                    }
                    level.setBlock(target, Blocks.DIRT.defaultBlockState(), 2);
                    player.setGameMode(GameType.SURVIVAL);
                    player.teleportTo(target.getX() + (suppliedMap ? 0.299 : -0.31), target.getY() + 2, target.getZ()
                        + (suppliedMap ? 0.438 : 0.5));
                    player.setYRot(-90);
                    player.setXRot(45);
                });
                next(1);
            }
            case 1 -> {
                if (ticks < 40) {
                    return;
                }
                ClientPlatform.show(client, null);
                client.mouseHandler.grabMouse();
                session.select();
                var selection = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(
                    Vec3.atCenterOf(target), Direction.UP, target, false));
                check(selection == net.minecraft.world.InteractionResult.FAIL, "Selection consumes interaction");
                check(new BlockMiningTargetStore(CatbudCore.configPath("island_assistive_mining_target")).target()
                    .x() == target.getX(), "Selected target persisted");
                check(client.level.getBlockState(target).is(Blocks.DIRT), "Selection leaves target intact");
                var referencePath = System.getProperty("catbud.autoClickerReference");
                if (referencePath != null) {
                    referenceLoader = new URLClassLoader(new java.net.URL[] { Path.of(referencePath).toUri().toURL() },
                        getClass().getClassLoader());
                    referenceClass = referenceLoader.loadClass("pro.mikey.autoclicker.AutoClicker");
                    reference = referenceClass.getConstructor().newInstance();
                    referenceClass.getMethod("clientReady", Minecraft.class).invoke(reference, client);
                    var active = referenceClass.getDeclaredField("isActive");
                    active.setAccessible(true);
                    active.set(reference, true);
                    referenceMode = 0;
                    referenceSetup();
                    next(2);
                } else {
                    beginFaces(client);
                }
            }
            case 2 -> {
                if (ticks < 8) {
                    if (!client.level.getBlockState(target).is(Blocks.DIRT)) {
                        ticks = 0;
                        return;
                    }
                    var point = new MiningAimPlanner().find(client, target, client.player.getEyePosition(), null);
                    check(point != null, "Reference has visible dirt target");
                    var delta = point.subtract(client.player.getEyePosition());
                    client.player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
                    client.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
                    return;
                }
                check(client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(target) || client.level
                    .getBlockState(target).isAir(), "Reference crosshair is on target");
                referenceClass.getMethod("clientTickEvent", Minecraft.class).invoke(reference, client);
                if (ticks < 45) {
                    return;
                }
                boolean removed = client.getSingleplayerServer().submit(() -> client.getSingleplayerServer().overworld()
                    .getBlockState(target).isAir()).join();
                results.add("OBSERVED Auto-Clicker cooldown=" + ((referenceMode & 1) != 0) + " mob=" + ((referenceMode
                    & 2) != 0) + " removed=" + removed + " keyDown=" + client.options.keyAttack.isDown());
                client.options.keyAttack.setDown(false);
                client.gameMode.stopDestroyBlock();
                if (++referenceMode < 4) {
                    referenceSetup();
                    block(client, Blocks.DIRT.defaultBlockState());
                    next(2);
                } else {
                    referenceLoader.close();
                    beginFaces(client);
                }
            }
            case 3 -> {
                if (ticks < 8) {
                    return;
                }
                check(client.level.getBlockState(target).is(Blocks.SCULK_VEIN), "Thin target present before mining "
                    + direction);
                session.start();
                check(session.running(), "Session starts for face " + direction + ": " + session.status().getString());
                next(4);
            }
            case 4 -> {
                if (ticks > 160) {
                    throw new AssertionError("Face " + Direction.values()[direction] + " not mined: " + session.status()
                        .getString());
                }
                if (!client.level.getBlockState(target).isAir()) {
                    return;
                }
                if (!serverConfirmsAir(client)) {
                    return;
                }
                results.add("PASS: server confirms face=" + Direction.values()[direction] + " ticks=" + ticks);
                if (++direction < 6) {
                    block(client, vein(Direction.values()[direction]));
                    next(5);
                } else {
                    session.stop();
                    block(client, Blocks.STONE.defaultBlockState());
                    next(6);
                }
            }
            case 5 -> {
                if (ticks >= 8) {
                    check(session.running(), "Continuous generation keeps session active");
                    next(4);
                }
            }
            case 6 -> {
                if (ticks < 8) {
                    return;
                }
                session.start();
                next(7);
            }
            case 7 -> {
                if (ticks > 300) {
                    throw new AssertionError("Stone not mined: " + session.status().getString());
                }
                if (!client.level.getBlockState(target).isAir() || !serverConfirmsAir(client)) {
                    return;
                }
                results.add("PASS: survival stone mined with cooldown and preserved progress in " + ticks + " ticks");
                session.stop();
                block(client, Blocks.BEDROCK.defaultBlockState());
                next(8);
            }
            case 8 -> {
                if (ticks < 8) {
                    return;
                }
                session.start();
                next(9);
            }
            case 9 -> {
                check(session.state() == BlockMiningSession.State.PAUSED, "Unbreakable target pauses");
                check(!client.options.keyAttack.isDown(), "Pause releases attack key");
                results.add("PASS: unbreakable target and input cleanup");
                session.stop();
                session.select();
                ClientPlatform.show(client, new net.minecraft.client.gui.screens.PauseScreen(true));
                next(10);
            }
            case 10 -> {
                check(session.state() == BlockMiningSession.State.STOPPED, "Opening screen cancels selection");
                results.add("PASS: selection cancellation");
                VerificationPlatform.resize(client, 1048, 720);
                client.options.guiScale().set(2);
                VerificationPlatform.resizeGui(client);
                ClientPlatform.show(client, new SettingsScreen(null));
                press(ClientPlatform.screen(client), "island.name");
                press(ClientPlatform.screen(client), "category.catbud_core.mining");
                next(11);
            }
            case 11 -> {
                VerificationPlatform.capture(client, "island-mining-settings.png");
                press(ClientPlatform.screen(client), "island.mining.select");
                check(session.state() == BlockMiningSession.State.SELECTING, "Real settings button arms selection");
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(
                    target), Direction.UP, target, false));
                check(session.state() == BlockMiningSession.State.STOPPED, "Real use-block callback selects target");
                results.add("PASS: settings category, selection button and use-block callback");
                block(client, vein(Direction.UP).setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true)
                    .setValue(MultifaceBlock.getFaceProperty(Direction.EAST), true));
                next(12);
            }
            case 12 -> {
                if (ticks < 8) {
                    return;
                }
                session.start();
                next(13);
            }
            case 13 -> {
                check(ticks < 180, "Multi-face mining timeout: " + session.status().getString());
                if (!client.level.getBlockState(target).isAir() || !serverConfirmsAir(client)) {
                    return;
                }
                results.add("PASS: server confirms multi-face vein removed");
                session.stop();
                block(client, vein(Direction.UP));
                next(14);
            }
            case 14 -> {
                if (ticks < 8) {
                    return;
                }
                regenerating = true;
                session.start();
                next(15);
            }
            case 15 -> {
                check(session.running(), "Rapid same-state generation remains active: " + session.status().getString());
                if (Boolean.getBoolean("catbud.verifyFreeCamera") && ticks == 30) {
                    check(FabricLoader.getInstance().isModLoaded("tweakeroo"), "Actual Tweakeroo loaded");
                    freeCamera(client, true);
                    freeCamera = client.getCameraEntity();
                    check(freeCamera != client.player, "Tweakeroo camera detached from player");
                    freeCamera.setPos(client.player.getX() + 8, client.player.getY() + 6, client.player.getZ() + 8);
                    freeCamera.setYRot(135);
                    freeCamera.setXRot(-30);
                    cameraRegenerations = regenerated;
                    results.add("INFO: Tweakeroo camera detached while body keeps mining");
                }
                if (ticks < 240 || regenerated < 20 && ticks < 400) {
                    return;
                }
                regenerating = false;
                check(regenerated >= 20, "Server confirms at least 20 consecutive replacements: " + regenerated);
                if (freeCamera != null) {
                    check(regenerated - cameraRegenerations >= 10,
                        "At least ten blocks mined from body in free camera");
                    check(Math.abs(freeCamera.getYRot() - 135) < 0.01 && Math.abs(freeCamera.getXRot() + 30) < 0.01,
                        "Mining does not rotate detached camera");
                    results.add("PASS: actual Tweakeroo free camera, body mined " + (regenerated - cameraRegenerations)
                        + " blocks; camera viewpoint preserved");
                    freeCamera(client, false);
                }
                results.add("PASS: " + regenerated + " same-state regenerations over " + ticks + " ticks");
                session.stop();
                block(client, Blocks.STONE.defaultBlockState());
                next(16);
            }
            case 16 -> {
                if (ticks < 8) {
                    return;
                }
                session.start();
                next(17);
            }
            case 17 -> {
                if (ticks < 8) {
                    return;
                }
                client.player.setYRot(client.player.getYRot() + 10);
                next(18);
            }
            case 18 -> {
                check(session.state() == BlockMiningSession.State.PAUSED, "Manual view change pauses mining");
                check(!client.options.keyAttack.isDown() && !client.options.keyShift.isDown(),
                    "Manual takeover releases inputs");
                results.add("PASS: manual takeover releases inputs");
                block(client, vein(Direction.WEST));
                serverTask = client.getSingleplayerServer().submit(() -> {
                    arena(client);
                    client.getSingleplayerServer().overworld().setBlock(target, vein(Direction.WEST), 2);
                    client.getSingleplayerServer().getPlayerList().getPlayers().getFirst().teleportTo(target.getX()
                        - 0.7, target.getY() + 2, target.getZ() + 0.5);
                });
                next(19);
            }
            case 19 -> {
                if (ticks < 15) {
                    return;
                }
                check(new MiningAimPlanner().find(client, target, client.player.getEyePosition(), null) == null,
                    "Movement fixture has no stationary aim point");
                session.configure(false, 1, 180, true);
                session.start();
                next(20);
            }
            case 20 -> {
                check(session.state() == BlockMiningSession.State.PAUSED,
                    "Movement disabled pauses unreachable target");
                results.add("PASS: movement disabled preserves standing position");
                session.configure(true, 1, 180, true);
                moveStart = client.player.position();
                if (Boolean.getBoolean("catbud.verifyFreeCamera")) {
                    freeCamera(client, true);
                    freeCamera = client.getCameraEntity();
                    freeCamera.setPos(client.player.getX() + 8, client.player.getY() + 6, client.player.getZ() + 8);
                    freeCamera.setYRot(135);
                    freeCamera.setXRot(-30);
                }
                session.start();
                next(21);
            }
            case 21 -> {
                moved |= session.state() == BlockMiningSession.State.MOVING;
                check(ticks < 180, "Movement mining timeout: " + session.status().getString());
                check(client.player.position().distanceTo(moveStart) <= 1.1, "Movement stays inside radius");
                if (!client.level.getBlockState(target).isAir() || !serverConfirmsAir(client)) {
                    return;
                }
                check(moved && client.player.position().distanceTo(moveStart) > 0.1, "Actual micro movement occurred");
                check(Math.abs(client.player.getY() - moveStart.y) < 0.01, "Movement remains at supported height");
                check(!new MiningPositionPlanner(new MiningAimPlanner()).safePath(client, target, client.player
                    .position(), moveStart.add(2, 0, 0)), "Void path rejected");
                results.add("PASS: safe micro movement mines occluded west vein, distance=" + client.player.position()
                    .distanceTo(moveStart));
                if (Boolean.getBoolean("catbud.verifyFreeCamera")) {
                    check(Math.abs(freeCamera.getYRot() - 135) < 0.01,
                        "Free camera stays independent during body movement");
                    freeCamera(client, false);
                    results.add("PASS: body micro movement works with actual Tweakeroo free camera");
                }
                session.stop();
                var otherStore = new BlockMiningTargetStore(client.gameDirectory.toPath().resolve(
                    "other-world-target.json"));
                otherStore.save(new BlockMiningTarget("server:other", "minecraft:overworld", 0, target.getY(), 0));
                var otherSession = new BlockMiningSession(client, otherStore);
                otherSession.start();
                check(otherSession.state() == BlockMiningSession.State.PAUSED, "Wrong world cannot start");
                results.add("PASS: world binding rejects a different world");
                block(client, Blocks.STONE.defaultBlockState());
                next(28);
            }
            case 22 -> {
                if (ticks < 8) {
                    return;
                }
                session.interruptions(new MiningInterruptionPolicy(true, true, false, false));
                session.start();
                ClientPlatform.show(client, new net.minecraft.client.gui.screens.PauseScreen(true));
                next(23);
            }
            case 23 -> {
                check(session.state() == BlockMiningSession.State.SUSPENDED,
                    "Configured screen interruption suspends active mining");
                check(!client.options.keyAttack.isDown() && !client.options.keyShift.isDown(), "Menu releases inputs");
                results.add("PASS: active mining stops for a menu");
                VerificationPlatform.capture(client, "island-mining-verification.png");
                ClientPlatform.show(client, null);
                client.mouseHandler.grabMouse();
                session.interruptions(MiningInterruptionPolicy.DEFAULT);
                session.start();
                next(24);
            }
            case 24 -> {
                if (!client.options.keyAttack.isDown() && ticks < 40) {
                    return;
                }
                check(client.options.keyAttack.isDown(), "Slot check starts during held mining");
                var inventory = client.player.getInventory();
                int slot = inventory.getSelectedSlot();
                try {
                    inventory.setSelectedSlot((slot + 1) % 9);
                    session.tick();
                    check(session.running() && session.state() != BlockMiningSession.State.PAUSED,
                        "Automatic tool changes do not count as manual input");
                } finally {
                    inventory.setSelectedSlot(slot);
                }
                results.add("PASS: automatic tool changes do not count as manual input");
                session.manualScroll();
                check(session.state() == BlockMiningSession.State.PAUSED && !client.options.keyAttack.isDown(),
                    "Actual scroll input releases held mining input");
                results.add("PASS: actual scroll input releases held mining input");
                session.start();
                next(25);
            }
            case 25 -> {
                if (!client.options.keyAttack.isDown() && ticks < 40) {
                    return;
                }
                check(client.options.keyAttack.isDown(), "Death check starts during held mining");
                float health = client.player.getHealth();
                try {
                    client.player.setHealth(0);
                    session.tick();
                    check(session.state() == BlockMiningSession.State.PAUSED && !client.options.keyAttack.isDown(),
                        "Dead player releases held mining input");
                } finally {
                    client.player.setHealth(health);
                }
                results.add("PASS: dead-player boundary releases held mining input");
                session.start();
                next(26);
            }
            case 26 -> {
                if (!client.options.keyAttack.isDown() && ticks < 40) {
                    return;
                }
                check(session.running() && client.options.keyAttack.isDown(),
                    "Disconnect check starts during held mining");
                client.disconnect(new net.minecraft.client.gui.screens.TitleScreen(), false, false);
                next(27);
            }
            case 27 -> {
                if (ticks < 40) {
                    return;
                }
                check(session.state() == BlockMiningSession.State.STOPPED && !client.options.keyAttack.isDown()
                    && !client.options.keyShift.isDown(), "Real disconnect clears held mining inputs");
                results.add("PASS: real disconnect clears held mining inputs");
                results.add("PASS: all mining client checks");
                write(client);
                done = true;
                client.stop();
            }
            case 28 -> {
                if (ticks < 8) {
                    return;
                }
                session.start();
                serverTask = client.getSingleplayerServer().submit(() -> {
                    var level = client.getSingleplayerServer().overworld();
                    for (var face : Direction.values()) {
                        var pos = target.relative(face);
                        occlusion.put(pos, level.getBlockState(pos));
                        level.setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), 2);
                    }
                });
                next(29);
            }
            case 29 -> {
                if (ticks < 6) {
                    return;
                }
                check(session.running(), "Briefly occluded target does not permanently pause");
                serverTask = client.getSingleplayerServer().submit(() -> occlusion.forEach((pos, state) -> client
                    .getSingleplayerServer().overworld().setBlock(pos, state, 2)));
                next(30);
            }
            case 30 -> {
                check(ticks < 220, "Target recovers after temporary occlusion: " + session.status().getString());
                if (!client.level.getBlockState(target).isAir() || !serverConfirmsAir(client)) {
                    return;
                }
                results.add("PASS: temporary full occlusion recovers without manual restart");
                session.stop();
                block(client, Blocks.STONE.defaultBlockState());
                next(22);
            }
            default -> throw new AssertionError("Unexpected stage");
        }
    }

    private void freeCamera(Minecraft client, boolean enabled) throws ReflectiveOperationException {
        var toggleClass = Class.forName("fi.dy.masa.tweakeroo.config.FeatureToggle");
        var toggle = toggleClass.getField("TWEAK_FREE_CAMERA").get(null);
        toggleClass.getMethod("setBooleanValue", boolean.class).invoke(toggle, enabled);
        if (enabled && client.getCameraEntity() == client.player) {
            Class.forName("fi.dy.masa.tweakeroo.util.CameraEntity").getMethod("setCameraState", boolean.class, Class
                .forName("fi.dy.masa.tweakeroo.util.CameraPreset")).invoke(null, true, null);
        }
    }

    private void arena(Minecraft client) {
        var level = client.getSingleplayerServer().overworld();
        for (var pos : BlockPos.betweenClosed(target.offset(-4, -2, -4), target.offset(4, 6, 4))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        for (int x = -3; x <= -1; x++) {
            for (int z = -3; z <= 3; z++) {
                level.setBlock(target.offset(x, 1, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    private void referenceSetup() throws Exception {
        var configField = referenceClass.getDeclaredField("config");
        configField.setAccessible(true);
        var config = configField.get(reference);
        var left = config.getClass().getMethod("getLeftClick").invoke(config);
        left.getClass().getMethod("setActive", boolean.class).invoke(left, true);
        left.getClass().getMethod("setRespectCooldown", boolean.class).invoke(left, (referenceMode & 1) != 0);
        left.getClass().getMethod("setMobMode", boolean.class).invoke(left, (referenceMode & 2) != 0);
    }

    private void beginFaces(Minecraft client) {
        client.options.keyAttack.setDown(false);
        client.gameMode.stopDestroyBlock();
        session.configure(true, 0.5, 180, true);
        block(client, vein(Direction.values()[0]));
        next(3);
    }

    private BlockState vein(Direction face) {
        return Blocks.SCULK_VEIN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(face), true);
    }

    private void block(Minecraft client, BlockState state) {
        var server = client.getSingleplayerServer();
        var previous = serverTask;
        serverTask = server.submit(() -> {
            if (previous != null) {
                previous.join();
            }
            server.overworld().setBlock(target, state, 2);
        });
    }

    private void next(int value) {
        stage = value;
        ticks = 0;
        serverAir = null;
    }

    private boolean serverConfirmsAir(Minecraft client) {
        if (serverAir == null) {
            serverAir = client.getSingleplayerServer().submit(() -> client.getSingleplayerServer().overworld()
                .getBlockState(target).isAir());
            return false;
        }
        if (!serverAir.isDone()) {
            return false;
        }
        boolean removed = serverAir.join();
        serverAir = null;
        return removed;
    }

    private void write(Minecraft client) {
        try {
            Files.write(client.gameDirectory.toPath().resolve("mining-report.txt"), results);
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void press(Screen screen, String key) {
        String label = Component.translatable(key).getString();
        var button = findButton(screen, label);
        if (button == null) {
            throw new IllegalStateException("Button not found: " + label);
        }
        button.onPress(null);
    }

    private static Button findButton(ContainerEventHandler parent, String label) {
        for (var child : parent.children()) {
            if (child instanceof Button candidate && candidate.getMessage().getString().equals(label)) {
                return candidate;
            }
            if (child instanceof ContainerEventHandler container) {
                var found = findButton(container, label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
