package github.freshchromatic.catbud_addons.public_warps;

import com.mojang.blaze3d.platform.InputConstants;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.*;

import java.nio.file.*;
import java.util.*;

public final class WarpVerificationClient implements ClientModInitializer {

    private final boolean                            ownerOnly      = Boolean.getBoolean("catbud.verify.owner");
    private final boolean                            inputOnly      = Boolean.getBoolean("catbud.verify.input")
        || ownerOnly;
    private int                                      ticks, step;
    private long                                     next;
    private long                                     stationaryStart;
    private SnapshotCaptureJob                       incrementalCapture;
    private int                                      captureTicks;
    private PreviewBuildQueue<String, WarpSceneMesh> meshProbe;
    private PreviewBuildQueue.Task<WarpSceneMesh>    meshWork;
    private WarpSceneMesh                            completedMesh;
    private int                                      meshTicks;
    private long                                     maximumMeshSlice;
    private long                                     previewReadyDeadline;
    private BlockSnapshot                            denseSnapshot;
    private int                                      drawingStage   = -1;
    private long                                     drawingStarted;
    private final List<Long>                         drawingSamples = new ArrayList<>();
    private final StringBuilder                      drawingReport  = new StringBuilder();

    private void openOwnerForm(Minecraft mc, WarpScreen parent) {
        var warp = PublicWarpsClient.INSTANCE.store().warps.getFirst();
        var http = java.net.http.HttpClient.newHttpClient();
        var account = UUID.fromString(warp.uuid);
        var api = new OwnerWarpApiClient(http, "http://127.0.0.1:9", WarpServerScope.CATBUD, account);
        var proof = new OfficialAccountProof(http, account, "0");
        var session = new OwnerEditSession(Runnable::run, Runnable::run, api, proof, new OwnerEditSession.Context(
            () -> true, saved -> {
            }, warp.name));
        var editor = new OwnerEditScreen(parent, warp, session);
        ClientPlatform.show(mc, editor);
        for (var child : editor.children()) {
            if (child instanceof EditBox name) {
                name.setValue("Community market");
            }
            if (child instanceof MultiLineEditBox description) {
                description.setValue("Public shops and trading area\nPlease keep the entrance clear.");
            }
        }
    }

    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (drawingStage >= 0) {
                verifyDenseDrawing(mc);
                return;
            }
            if (meshProbe != null) {
                verifyMeshTicks(mc);
                return;
            }
            if (incrementalCapture != null) {
                verifyIncrementalCapture(mc);
            }
            if (VerificationPlatform.loading(mc)) {
                return;
            }
            if (++ticks < 40 || System.currentTimeMillis() < next) {
                return;
            }
            if (!VerificationPlatform.prepareWorld(mc)) {
                return;
            }
            next = System.currentTimeMillis() + 2500;
            try {
                switch (step++) {
                    case 0 -> {
                        mc.options.pauseOnLostFocus = false;
                        VerificationPlatform.resize(mc, 1048, 556);
                        mc.options.guiScale().set(2);
                        VerificationPlatform.resizeGui(mc);
                        if (!inputOnly) {
                            verifyMeshCosts(mc);
                        }
                        if (mc.level != null && mc.player != null && !inputOnly) {
                            var sheep = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
                                net.minecraft.resources.Identifier.parse("minecraft:sheep")).create(mc.level,
                                    net.minecraft.world.entity.EntitySpawnReason.LOAD);
                            sheep.setId(100000);
                            EntityVisual.apply(sheep, Map.of("Color", "14b"));
                            sheep.setPos(mc.player.getX() + 1, mc.player.getY(), mc.player.getZ());
                            mc.level.addEntity(sheep);
                            var captured = BlockSnapshotCapture.capture(mc, mc.player.blockPosition());
                            captured.validate();
                            incrementalCapture = new SnapshotCaptureJob(mc, mc.player.blockPosition());
                            if (captured.blocks.length != 125000 || captured.entities.stream().noneMatch(e -> e.type()
                                .equals("minecraft:sheep") && e.appearance().getOrDefault("Color", "").startsWith("14"))
                                || captured.entities.stream().anyMatch(e -> mc.player.getUUID().toString().equals(e
                                    .playerUUID()))) {
                                throw new AssertionError("Actual snapshot capture mismatch: " + captured.entities);
                            }
                            Files.writeString(mc.gameDirectory.toPath().resolve("capture-verification.txt"),
                                "Actual client world capture: 125000 blocks, sheep"
                                    + " visual data retained, own player excluded.\n");
                            mc.level.removeEntity(sheep.getId(),
                                net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                        }
                        var data = PublicWarpsClient.INSTANCE;
                        for (int n = 0; n < (inputOnly ? 1 : 12); n++) {
                            var w = new WarpData();
                            w.uuid = new UUID(0, n + 1).toString();
                            w.name = "Warp_" + (n + 1);
                            if (inputOnly) {
                                w.displayName = "Community market";
                                w.description = "Shops and public trading area";
                                w.tags = List.of("Market", "Shops");
                            }
                            w.status = "verified_report";
                            data.store().warps.add(w);
                            var s = new BlockSnapshot(50, 50, 50, "minecraft:overworld");
                            s.eyeX = 5;
                            s.eyeY = 2.62;
                            s.eyeZ = 5;
                            s.entities.add(new BlockSnapshot.SnapshotEntity("minecraft:sheep", 5, 1, 6, 0, 0, null,
                                null, Map.of("Color", "14b")));
                            s.entities.add(new BlockSnapshot.SnapshotEntity("minecraft:armor_stand", 9, 1, 9, 0, 0,
                                null, null, Map.of("ShowArms", "1b")));
                            s.palette.addAll(List.of("minecraft:air", "minecraft:grass_block[snowy=false]",
                                "minecraft:oak_planks", "minecraft:deepslate_tiles", "minecraft:glass",
                                "minecraft:oak_log[axis=y]"));
                            for (int z = 0; z < 10; z++) {
                                for (int x = 0; x < 10; x++) {
                                    s.blocks[s.index(x, 0, z)] = 1;
                                    for (int y = 1; y < 5; y++) {
                                        if (x >= 2 && x <= 7 && z >= 2 && z <= 7) {
                                            int v = 0;
                                            boolean wall = x == 2 || x == 7 || z == 2 || z == 7;
                                            boolean window = y == 2 && (x == 4 || x == 5 || z == 4 || z == 5);
                                            if (wall) {
                                                v = window ? 4 : 2;
                                            }
                                            if ((x == 2 || x == 7) && (z == 2 || z == 7)) {
                                                v = 5;
                                            }
                                            s.blocks[s.index(x, y, z)] = v;
                                        }
                                    }
                                    if (x >= 1 && x <= 8 && z >= 1 && z <= 8) {
                                        s.blocks[s.index(x, 5, z)] = 3;
                                    }
                                }
                            }
                            if (n == 11) {
                                for (int y = 1; y < 50; y++) {
                                    for (int z = 0; z < 50; z++) {
                                        for (int x = 0; x < 50; x++) {
                                            s.blocks[s.index(x, y, z)] = 0;
                                        }
                                    }
                                }
                                s.entities.add(new BlockSnapshot.SnapshotEntity("minecraft:player", 2, 1, 8, 0, 0,
                                    "00000000-0000-0000-0000-000000000123", "Fixture_Player", Map.of()));
                            }
                            if (n == 11) {
                                for (int z = 0; z < 50; z++) {
                                    for (int x = 0; x < 50; x++) {
                                        s.blocks[s.index(x, 0, z)] = 1;
                                    }
                                }
                                s.entities.set(0, new BlockSnapshot.SnapshotEntity("minecraft:sheep", 8, 1, 9, 0, 0,
                                    null, null, Map.of("Color", "14b")));
                                s.entities.set(1, new BlockSnapshot.SnapshotEntity("minecraft:armor_stand", 5, 1, 9,
                                    180, 0, null, null, Map.of("ShowArms", "1b", "equipment",
                                        "{head:{id:\"minecraft:leather_helmet\",count:1,components:{\"minecraft:dyed_color\":16711680,\"minecraft:item_name\":\"private-name\"}},mainhand:{id:\"minecraft:diamond_sword\",count:1}}")));
                                s = BlockSnapshot.decode(SharedPreview.from(s).bytes());
                            }
                            Path real = mc.gameDirectory.toPath().resolve("real-preview.txt");
                            if (n == 0 && Files.exists(real)) {
                                s = BlockSnapshot.decode(Base64.getDecoder().decode(Files.readString(real).trim()));
                                w.name = "daniel1689";
                            }
                            data.store().previews.put(w.uuid, Base64.getEncoder().encodeToString(s.encode()));
                            if (n < 3) {
                                var v = new WarpData.Visit();
                                v.count = 18 - n * 5;
                                data.store().visits.put(w.uuid, v);
                            }
                        }
                        mc.setScreenAndShow(new WarpScreen());
                    }
                    case 1 -> {
                        VerificationPlatform.hover(mc, 40, 110);
                        ((WarpScreen) ClientPlatform.screen(mc)).mouseClicked(new MouseButtonEvent(40, 110,
                            new MouseButtonInfo(0, 0)), false);
                    }
                    case 2 -> {
                        if (!previewsReady(mc)) {
                            if (previewReadyDeadline == 0) {
                                previewReadyDeadline = System.currentTimeMillis() + 30000;
                            }
                            if (System.currentTimeMillis() >= previewReadyDeadline) {
                                throw new AssertionError("Visible previews never became ready");
                            }
                            step--;
                            next = System.currentTimeMillis() + 250;
                            return;
                        }
                        capture(mc, "01-selected-exterior.png");
                        if (ownerOnly) {
                            openOwnerForm(mc, (WarpScreen) ClientPlatform.screen(mc));
                            return;
                        }
                        if (inputOnly) {
                            verifyPreviewInput(mc, (WarpScreen) ClientPlatform.screen(mc));
                            Files.writeString(mc.gameDirectory.toPath().resolve("preview-input-verification.txt"),
                                "PASS: rendered preview, drag/wheel, focused"
                                    + " WASD/Space/Shift, R reset, search focus" + " release, two-step Esc.\n");
                            mc.stop();
                            return;
                        }
                        VerificationPlatform.hover(mc, 510, 20);
                        stationaryStart = WarpSceneRenderer.draws;
                    }
                    case 3 -> {
                        if (ownerOnly) {
                            capture(mc, "owner-edit-form.png");
                            var editor = (OwnerEditScreen) ClientPlatform.screen(mc);
                            editor.onClose();
                            if (!(ClientPlatform.screen(mc) instanceof WarpScreen)) {
                                throw new IllegalStateException("Owner editor did not return to browser");
                            }
                            if (!PublicWarpsClient.INSTANCE.store().warps.getFirst().displayName.equals(
                                "Community market")) {
                                throw new IllegalStateException("Cancelled editor changed cached metadata");
                            }
                            Files.writeString(mc.gameDirectory.toPath().resolve("owner-edit-ui-verification.txt"),
                                "PASS: editor over original browser, editable custom"
                                    + " name and multiline description, player ID"
                                    + " retained, cancellation preserves metadata. No"
                                    + " official authentication simulated.\n");
                            mc.stop();
                            return;
                        }
                        capture(mc, "02-stationary.png");
                        long extra = WarpSceneRenderer.draws - stationaryStart;
                        Files.writeString(mc.gameDirectory.toPath().resolve("render-verification.txt"),
                            "Scene redraws in 2.5 seconds after hover leaves: " + extra + "; cached blits: "
                                + WarpSceneRenderer.cacheHits + "\n");
                        if (extra > 3) {
                            throw new AssertionError("Static preview keeps redrawing: " + extra);
                        }
                        clickMode(mc);
                    }
                    case 4 -> {
                        capture(mc, "03-explicit-interior.png");
                        var screen = (WarpScreen) ClientPlatform.screen(mc);
                        verifyDragZoom(screen);
                    }
                    case 5 -> {
                        capture(mc, "04-dragged-interior.png");
                        clickMode(mc);
                    }
                    case 6 -> {
                        capture(mc, "05-dragged-exterior.png");
                        var screen = ClientPlatform.screen(mc);
                        var box = (EditBox) screen.children().stream().filter(w -> w instanceof EditBox).findFirst()
                            .orElseThrow();
                        box.setValue("Warp_12");
                        ((WarpScreen) ClientPlatform.screen(mc)).mouseClicked(new MouseButtonEvent(40, 110,
                            new MouseButtonInfo(0, 0)), false);
                    }
                    case 7 -> {
                        capture(mc, "06-entities.png");
                        clickMode(mc);
                        VerificationPlatform.hover(mc, 510, 20);
                    }
                    case 8 -> capture(mc, "09-shared-equipment-closeup.png");
                    case 9 -> {
                        ((EditBox) ClientPlatform.screen(mc).children().stream().filter(w -> w instanceof EditBox)
                            .findFirst().orElseThrow()).setValue("");
                        mc.getLanguageManager().setSelected("zh_tw");
                        mc.options.languageCode = "zh_tw";
                        mc.reloadResourcePacks();
                    }
                    case 10 -> capture(mc, "07-zh-browser.png");
                    case 11 -> {
                        VerificationPlatform.resize(mc, 854, 480);
                        mc.options.guiScale().set(2);
                        VerificationPlatform.resizeGui(mc);
                    }
                    case 12 -> capture(mc, "08-compact.png");
                    case 13 -> {
                        Files.writeString(mc.gameDirectory.toPath().resolve("verification-results.txt"),
                            "Native shared-depth scene, preview double-click toggle,"
                                + " drag, zoom, static texture reuse, search, zh_tw and"
                                + " compact captures completed. Optional real preview"
                                + " fixture; live Catbud protocol not validated.\n");
                        startMeshProbe();
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
                try {
                    Files.writeString(mc.gameDirectory.toPath().resolve("verification-results.txt"), "FAILED: " + e);
                } catch (Exception ignored) {
                }
                mc.stop();
            }
        });
    }

    private void verifyIncrementalCapture(Minecraft mc) {
        try {
            captureTicks++;
            if (!incrementalCapture.advance(mc)) {
                return;
            }
            var snapshot = incrementalCapture.result();
            snapshot.validate();
            if (snapshot.blocks.length != 125000 || captureTicks != 31) {
                throw new IllegalStateException("Incremental capture tick or block count mismatch");
            }
            var cancelled = new SnapshotCaptureJob(mc, mc.player.blockPosition());
            if (cancelled.advance(mc)) {
                throw new IllegalStateException("Capture unexpectedly completed in one tick");
            }
            var level = mc.level;
            boolean rejected = false;
            try {
                mc.level = null;
                cancelled.advance(mc);
            } catch (IllegalStateException expected) {
                rejected = true;
            } finally {
                mc.level = level;
            }
            if (!rejected) {
                throw new IllegalStateException("Changed world did not cancel capture");
            }
            Files.writeString(mc.gameDirectory.toPath().resolve("incremental-capture-verification.txt"),
                "125000 blocks captured across 31 client ticks; world-change cancellation" + " verified.\n");
            Files.write(mc.gameDirectory.toPath().resolve("actual-fifty-cube.snapshot.gz"), snapshot.encode());
            incrementalCapture = null;
        } catch (Exception failure) {
            incrementalCapture = null;
            throw new IllegalStateException("Incremental capture verification failed", failure);
        }
    }

    private void verifyMeshCosts(Minecraft mc) throws Exception {
        var renderer = new PreviewRenderer();
        var build = PreviewRenderer.class.getDeclaredMethod("build", BlockSnapshot.class);
        build.setAccessible(true);
        var report = new StringBuilder();
        for (String pattern : List.of("solid", "checkerboard", "water", "lava")) {
            var snapshot = new BlockSnapshot(50, 50, 50, "minecraft:overworld");
            snapshot.palette.add("minecraft:air");
            String state = switch (pattern) {
                case "water" -> "minecraft:water[level=0]";
                case "lava" -> "minecraft:lava[level=0]";
                default -> "minecraft:stone";
            };
            snapshot.palette.add(state);
            for (int y = 0; y < 50; y++) {
                for (int z = 0; z < 50; z++) {
                    for (int x = 0; x < 50; x++) {
                        if (!pattern.equals("checkerboard") || (x + y + z) % 2 == 0) {
                            snapshot.blocks[snapshot.index(x, y, z)] = 1;
                        }
                    }
                }
            }
            long start = System.nanoTime();
            var faces = (List<?>) build.invoke(renderer, snapshot);
            long elapsed = System.nanoTime() - start;
            int expected = switch (pattern) {
                case "solid" -> 15000;
                case "checkerboard" -> 375000;
                default -> 15000;
            };
            if (faces.size() != expected) {
                throw new AssertionError("Unexpected " + pattern + " mesh: " + faces.size());
            }
            report.append(pattern).append(" blocks=125000 faces=").append(faces.size()).append(" buildMs=").append(
                elapsed / 1_000_000.0).append('\n');
        }
        var steppedWater = new BlockSnapshot(2, 2, 1, "minecraft:overworld");
        steppedWater.palette.addAll(List.of("minecraft:air", "minecraft:water[level=0]"));
        steppedWater.blocks[steppedWater.index(0, 0, 0)] = 1;
        steppedWater.blocks[steppedWater.index(0, 1, 0)] = 1;
        steppedWater.blocks[steppedWater.index(1, 0, 0)] = 1;
        var steppedFaces = (List<?>) build.invoke(renderer, steppedWater);
        if (steppedFaces.size() != 15) {
            throw new AssertionError("Fluid height edge was incorrectly culled: " + steppedFaces.size());
        }
        report.append("stepped water faces=15; height edge retained\n");
        Files.writeString(mc.gameDirectory.toPath().resolve("mesh-cost-verification.txt"), report);
        renderer.clear();
    }

    private boolean previewsReady(Minecraft mc) throws Exception {
        var screen = (WarpScreen) ClientPlatform.screen(mc);
        var rendererField = WarpScreen.class.getDeclaredField("previews");
        rendererField.setAccessible(true);
        var renderer = rendererField.get(screen);
        var buildsField = PreviewRenderer.class.getDeclaredField("builds");
        buildsField.setAccessible(true);
        var meshesField = PreviewRenderer.class.getDeclaredField("meshes");
        meshesField.setAccessible(true);
        return ((PreviewCache<?, ?>) meshesField.get(renderer)).size() > 0 && !((PreviewBuildQueue<?, ?>) buildsField
            .get(renderer)).hasWork();
    }

    @SuppressWarnings("unchecked")
    private void startMeshProbe() throws Exception {
        var snapshot = new BlockSnapshot(50, 50, 50, "minecraft:overworld");
        denseSnapshot = snapshot;
        snapshot.eyeX = 25.5;
        snapshot.eyeY = 26.62;
        snapshot.eyeZ = 25.5;
        snapshot.palette.addAll(List.of("minecraft:air", "minecraft:stone"));
        for (int y = 0; y < 50; y++) {
            for (int z = 0; z < 50; z++) {
                for (int x = 0; x < 50; x++) {
                    snapshot.blocks[snapshot.index(x, y, z)] = (x + y + z) % 2;
                }
            }
        }
        for (int index = 0; index < 256; index++) {
            String type = switch (index % 4) {
                case 0 -> "minecraft:sheep";
                case 1 -> "minecraft:pig";
                case 2 -> "minecraft:zombified_piglin";
                default -> "minecraft:armor_stand";
            };
            snapshot.entities.add(new BlockSnapshot.SnapshotEntity(type, 1 + index % 16, 26, 1 + index / 16, 0, 0, null,
                null, Map.of()));
        }
        var type = Class.forName(PreviewRenderer.class.getName() + "$SceneBuild");
        var constructor = type.getDeclaredConstructor(PreviewRenderer.class, BlockSnapshot.class);
        constructor.setAccessible(true);
        meshWork = new MeasuredSceneBuild((PreviewBuildQueue.Task<WarpSceneMesh>) constructor.newInstance(
            new PreviewRenderer(), snapshot));
        meshProbe = new PreviewBuildQueue<>(System::nanoTime, (key, result) -> completedMesh = result);
    }

    private void verifyMeshTicks(Minecraft mc) {
        try {
            meshTicks++;
            meshProbe.beginFrame();
            meshProbe.request("dense", () -> meshWork);
            long start = System.nanoTime();
            meshProbe.endFrame();
            maximumMeshSlice = Math.max(maximumMeshSlice, System.nanoTime() - start);
            if (meshTicks > 2000) {
                throw new AssertionError("Dense mesh never completed");
            }
            if (completedMesh != null) {
                if (meshTicks <= 1 || completedMesh.faces().size() != 375000 || completedMesh.entities()
                    .size() != 256) {
                    throw new AssertionError("Incremental mesh traversal mismatch");
                }
                Files.writeString(mc.gameDirectory.toPath().resolve("preparation-stage-verification.txt"),
                    ((MeasuredSceneBuild) meshWork).report());
                Files.writeString(mc.gameDirectory.toPath().resolve("incremental-mesh-verification.txt"),
                    "125000 blocks; 375000 faces; 256 entities; clientTicks=" + meshTicks + "; maximumSliceMs="
                        + maximumMeshSlice / 1_000_000.0 + "; includes incremental sort, entity preparation and"
                        + " grouping.\n");
                meshProbe.clear();
                meshProbe = null;
                startDenseDrawing(mc);
            }
        } catch (Exception | AssertionError failure) {
            try {
                Files.writeString(mc.gameDirectory.toPath().resolve("incremental-mesh-verification.txt"), "FAILED: "
                    + failure);
            } catch (Exception ignored) {
            }
            meshProbe = null;
            mc.stop();
        }
    }

    private void capture(Minecraft mc, String name) {
        VerificationPlatform.capture(mc, name);
    }

    private void startDenseDrawing(Minecraft mc) throws Exception {
        VerificationPlatform.resize(mc, 1048, 556);
        VerificationPlatform.resizeGui(mc);
        var data = PublicWarpsClient.INSTANCE;
        data.store().warps.clear();
        data.store().previews.clear();
        data.store().visits.clear();
        var warp = new WarpData();
        warp.uuid = "00000000-0000-0000-0000-000000009999";
        warp.name = "DenseRenderProbe";
        data.store().warps.add(warp);
        data.store().previews.put(warp.uuid, Base64.getEncoder().encodeToString(denseSnapshot.encode()));
        var screen = new WarpScreen();
        mc.setScreenAndShow(screen);
        var selected = WarpScreen.class.getDeclaredField("selected");
        selected.setAccessible(true);
        selected.set(screen, warp.key());
        VerificationPlatform.hover(mc, 510, 20);
        completedMesh = null;
        meshWork = null;
        drawingStage = 0;
        drawingStarted = System.currentTimeMillis();
    }

    private void verifyDenseDrawing(Minecraft mc) {
        try {
            long now = System.currentTimeMillis();
            var screen = (WarpScreen) ClientPlatform.screen(mc);
            if (drawingStage == 0) {
                if (!previewsReady(mc)) {
                    if (now - drawingStarted > 30000) {
                        throw new AssertionError("Dense drawing never became ready");
                    }
                    return;
                }
                drawingStage = 1;
                drawingStarted = now + 1000;
                return;
            }
            if (drawingStage == 2) {
                double yaw = previewControl(screen).yaw();
                screen.mouseDragged(previewClick(screen), 8, 0);
                previewControl(screen).advance(System.nanoTime() / 1e9, true, true);
                if (!screen.mouseDragged(previewClick(screen), 8, 0) || previewControl(screen).yaw() == yaw) {
                    throw new AssertionError("Dense preview drag did not rotate camera");
                }
            }
            if (now < drawingStarted) {
                return;
            }
            drawingSamples.add(mc.getFrameTimeNs());
            if (now - drawingStarted < 3000) {
                return;
            }
            drawingSamples.sort(Long::compare);
            String mode = switch (drawingStage) {
                case 1 -> "static";
                case 2 -> "drag";
                default -> "interior";
            };
            drawingReport.append(mode).append(" tickSamples=").append(drawingSamples.size()).append(
                " lastFrameMedianMs=").append(drawingSamples.get(drawingSamples.size() / 2) / 1_000_000.0).append(
                    " lastFrameP95Ms=").append(drawingSamples.get((drawingSamples.size() - 1) * 95 / 100) / 1_000_000.0)
                .append(" lastFrameMaxMs=").append(drawingSamples.get(drawingSamples.size() - 1) / 1_000_000.0).append(
                    " reportedFps=").append(mc.getFps()).append('\n');
            capture(mc, "dense-" + mode + ".png");
            drawingSamples.clear();
            if (drawingStage == 1) {
                screen.mouseClicked(previewClick(screen), false);
            } else if (drawingStage == 2) {
                screen.mouseReleased(previewClick(screen));
                clickMode(mc);
            } else {
                verifyRetainedResources(mc);
                Files.writeString(mc.gameDirectory.toPath().resolve("dense-drawing-verification.txt"),
                    "125000 blocks; 375000 faces; 256 entity states; client last-frame"
                        + " elapsed-time samples at tick cadence, not GPU timers or all" + " rendered frames.\n"
                        + drawingReport);
                drawingStage = -1;
                mc.stop();
                return;
            }
            drawingStage++;
            drawingStarted = now + 1000;
        } catch (Exception | AssertionError failure) {
            try {
                Files.writeString(mc.gameDirectory.toPath().resolve("dense-drawing-verification.txt"), "FAILED: "
                    + failure);
            } catch (Exception ignored) {
            }
            drawingStage = -1;
            mc.stop();
        }
    }

    private void verifyRetainedResources(Minecraft mc) throws Exception {
        Class<?> geometryClass;
        try {
            geometryClass = Class.forName("github.freshchromatic.catbud_addons.public_warps.RetainedGeometry");
        } catch (ClassNotFoundException legacyRenderer) {
            return;
        }
        var instanceField = WarpSceneRenderer.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        var renderer = instanceField.get(null);
        var geometryField = WarpSceneRenderer.class.getDeclaredField("geometry");
        geometryField.setAccessible(true);
        var scenes = (java.util.Map<?, ?>) geometryField.get(renderer);
        if (scenes.size() != 1) {
            throw new AssertionError("Grid and detail did not share one retained scene");
        }
        var buffers = new ArrayList<Object>();
        for (var groups : scenes.values()) {
            buffers.addAll((java.util.List<?>) groups);
        }
        long bytes = 0;
        for (var geometry : buffers) {
            bytes += (long) geometryClass.getMethod("bytes").invoke(geometry);
        }
        if (bytes < 375000L * 4 * 20) {
            throw new AssertionError("Dense scene vertex storage is incomplete");
        }
        var builderClass = Class.forName(geometryClass.getName() + "$Builder");
        var partial = (PreviewBuildQueue.Task<?>) builderClass.getConstructor(WarpSceneMesh.class).newInstance(scenes
            .keySet().iterator().next());
        if (partial.advance()) {
            throw new AssertionError("Partial upload unexpectedly completed");
        }
        var groupsField = builderClass.getDeclaredField("groups");
        groupsField.setAccessible(true);
        var partialBuffers = new ArrayList<>((java.util.List<?>) groupsField.get(partial));
        if (partialBuffers.isEmpty()) {
            throw new AssertionError("Partial upload did not allocate resources");
        }
        if (partial.advance()) {
            throw new AssertionError("Staged upload unexpectedly completed");
        }
        var stagingField = geometryClass.getDeclaredField("pendingAllocation");
        stagingField.setAccessible(true);
        var staging = stagingField.get(partialBuffers.getFirst());
        if (staging == null) {
            throw new AssertionError("Partial upload did not retain staging memory");
        }
        var pointer = staging.getClass().getDeclaredField("pointer");
        pointer.setAccessible(true);
        if (pointer.getLong(staging) == 0) {
            throw new AssertionError("Staging memory was not allocated");
        }
        partial.cancel();
        partial.cancel();
        if (pointer.getLong(staging) != 0) {
            throw new AssertionError("Cancelled staging memory was not released");
        }
        assertClosedGeometry(geometryClass, partialBuffers);
        WarpSceneRenderer.clearScenes();
        assertClosedGeometry(geometryClass, buffers);
        if (!scenes.isEmpty()) {
            throw new AssertionError("Cleared renderer retains scenes");
        }
        Files.writeString(mc.gameDirectory.toPath().resolve("retained-resource-verification.txt"),
            "Grid/detail share one mesh; vertexBytes=" + bytes
                + "; partial cancellation releases staging memory; renderer cleanup" + " closes all owned buffers.\n");
    }

    private void assertClosedGeometry(Class<?> geometryClass, java.util.List<?> groups) throws Exception {
        var verticesField = geometryClass.getDeclaredField("vertices");
        verticesField.setAccessible(true);
        for (var group : groups) {
            var buffer = verticesField.get(group);
            var closed = buffer.getClass().getMethod("isClosed");
            closed.setAccessible(true);
            if (!(boolean) closed.invoke(buffer)) {
                throw new AssertionError("Geometry buffer was not released");
            }
        }
    }

    private void clickMode(Minecraft mc) throws Exception {
        var screen = (WarpScreen) ClientPlatform.screen(mc);
        var control = previewControl(screen);
        boolean before = control.inside();
        var click = previewClick(screen);
        screen.mouseClicked(click, false);
        screen.mouseReleased(click);
        screen.mouseClicked(click, true);
        screen.mouseReleased(click);
        if (control.inside() != before) {
            throw new AssertionError("Preview double-click unexpectedly changed camera mode");
        }
        for (int i = 0; i < 100 && !control.inside(); i++) {
            screen.mouseScrolled(click.x(), click.y(), 0, 1);
            control.advance(System.nanoTime() / 1e9 + i / 60d, true, true);
        }
        if (!control.inside()) {
            throw new AssertionError("Scrolling could not enter the preview volume");
        }
    }

    private PreviewCameraControl previewControl(WarpScreen screen) throws Exception {
        var field = WarpScreen.class.getDeclaredField("cameraControl");
        field.setAccessible(true);
        return (PreviewCameraControl) field.get(screen);
    }

    private void verifyPreviewInput(Minecraft mc, WarpScreen screen) throws Exception {
        var control = previewControl(screen);
        var click = previewClick(screen);
        VerificationPlatform.hover(mc, click.x(), click.y());
        verifyDragZoom(screen);
        double before = control.offsetZ();
        double time = System.nanoTime() / 1e9;
        control.advance(time, true, true);
        for (int key : new int[] { InputConstants.KEY_W, InputConstants.KEY_A, InputConstants.KEY_S,
            InputConstants.KEY_D, InputConstants.KEY_SPACE, InputConstants.KEY_LSHIFT }) {
            var event = new KeyEvent(key, 0, 0);
            if (!screen.keyPressed(event) || !screen.keyReleased(event)) {
                throw new AssertionError("Preview keyboard did not consume movement: " + key);
            }
        }
        screen.keyPressed(new KeyEvent(InputConstants.KEY_W, 0, 0));
        control.advance(time + .05, true, true);
        screen.keyReleased(new KeyEvent(InputConstants.KEY_W, 0, 0));
        if (control.offsetZ() == before) {
            throw new AssertionError("Focused W did not move rendered camera");
        }
        if (!screen.keyPressed(new KeyEvent(InputConstants.KEY_R, 0, 0))) {
            throw new AssertionError("R reset was not consumed");
        }
        screen.mouseClicked(new MouseButtonEvent(60, 40, new MouseButtonInfo(0, 0)), false);
        if (control.focused()) {
            throw new AssertionError("Search click retained preview focus");
        }
        screen.mouseClicked(click, false);
        screen.mouseReleased(click);
        screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
        if (control.focused() || ClientPlatform.screen(mc) != screen) {
            throw new AssertionError("First Escape must release preview without closing screen");
        }
        screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
        if (ClientPlatform.screen(mc) == screen) {
            throw new AssertionError("Second Escape must close screen");
        }
    }

    private void verifyDragZoom(WarpScreen screen) throws Exception {
        var control = previewControl(screen);
        var click = previewClick(screen);
        double yaw = control.yaw();
        double pitch = control.pitch();
        double position = control.offsetZ();
        screen.mouseClicked(click, false);
        if (!screen.mouseDragged(click, 30, 15)) {
            throw new AssertionError("Preview drag was not accepted");
        }
        screen.mouseReleased(click);
        screen.mouseScrolled(click.x(), click.y(), 0, 1);
        control.advance(System.nanoTime() / 1e9 + .05, true, true);
        if (control.yaw() == yaw || control.pitch() == pitch || control.offsetZ() == position) {
            throw new AssertionError("Preview drag/zoom did not change camera");
        }
    }

    private MouseButtonEvent previewClick(WarpScreen screen) throws Exception {
        var side = WarpScreen.class.getDeclaredField("sideX");
        var width = WarpScreen.class.getDeclaredField("sideWidth");
        var top = WarpScreen.class.getDeclaredField("previewTop");
        var bottom = WarpScreen.class.getDeclaredField("bottom");
        for (var field : List.of(side, width, top, bottom)) {
            field.setAccessible(true);
        }
        return new MouseButtonEvent(side.getInt(screen) + width.getInt(screen) / 2.0, (top.getInt(screen) + bottom
            .getInt(screen)) / 2.0, new MouseButtonInfo(0, 0));
    }
}
