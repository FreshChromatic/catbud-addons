package github.freshchromatic.catbud_addons.magic_tower;

import github.freshchromatic.catbud_addons.catbud_core.CatbudCore;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

/** Runs only with -PverifySettings, in its own game directory. */
public final class SettingsVerificationClient implements ClientModInitializer {
    private int ticks, step;
    private long worldWaitAt;
    private String waitingScreen;
    private final List<String> results = new ArrayList<>();
    @Override public void onInitializeClient() {
        if (Boolean.getBoolean("catbud.verify.worldOnly")) step = 20;
        if (Boolean.getBoolean("catbud.verify.tooltipsOnly")) step = 40;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!mc.isGameLoadFinished() || mc.gui.overlay() != null) return;
            if (++ticks % 35 != 0) return;
            try {
                switch (step++) {
                    case 0 -> {
                        mc.options.pauseOnLostFocus = false; mc.options.guiScale().set(2); mc.resizeGui();
                        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), 1139, 755);
                        if (Boolean.getBoolean("catbud.verify.legacy")) {
                            check(!MagicTowerSettings.values().bool("routes.auto"), "Legacy automatic portal preference migrated");
                            var addon = CatbudCore.addons().getFirst();
                            check(!addon.store().current().json().has("debug"), "Legacy debug mode is not restored");
                            var draft = addon.store().current(); draft.reset("routes"); addon.store().apply(draft);
                        }
                        check(java.util.Arrays.stream(mc.options.keyMappings).noneMatch(k -> k.getName().equals("key.catbud_addons.toggle_debug")), "F8 debug key mapping removed");
                        mc.setScreenAndShow(CatbudCore.screen(mc.gui.screen()));
                    }
                    case 1 -> { capture(mc, "01-core.png"); press(mc.gui.screen(), "Magic Tower"); }
                    case 2 -> { capture(mc, "02-general.png"); press(mc.gui.screen(), "Radar"); }
                    case 3 -> { capture(mc, "03-radar.png"); press(mc.gui.screen(), "Enable radar"); }
                    case 4 -> { capture(mc, "04-disabled-radar.png"); check(MagicTowerSettings.values().bool("radar.enabled"), "Draft edits do not change runtime"); press(mc.gui.screen(), "Cancel"); }
                    case 5 -> { check(MagicTowerSettings.values().bool("radar.enabled"), "Cancel retains saved radar preference"); mc.setScreenAndShow(CatbudCore.screen(mc.gui.screen())); press(mc.gui.screen(), "Magic Tower"); press(mc.gui.screen(), "Radar"); }
                    case 6 -> { press(mc.gui.screen(), "Enable radar"); press(mc.gui.screen(), "Apply"); }
                    case 7 -> { check(!MagicTowerSettings.values().bool("radar.enabled"), "Apply updates runtime preference"); press(mc.gui.screen(), "Reset category"); press(mc.gui.screen(), "Apply"); }
                    case 8 -> { check(MagicTowerSettings.values().bool("radar.enabled"), "Reset category restores radar"); press(mc.gui.screen(), "ESP"); }
                    case 9 -> {
                        capture(mc, "05-esp.png");
                        var input = widgets(mc.gui.screen()).stream().filter(w -> w instanceof EditBox && w.getMessage().getString().equals("Outline opacity"))
                            .map(w -> (EditBox) w).findFirst().orElseThrow();
                        input.setValue("0"); press(mc.gui.screen(), "Apply");
                        check(MagicTowerSettings.values().number("esp.opacity") == 1, "Out-of-range percentage blocks saving");
                        input.setValue("50"); press(mc.gui.screen(), "Apply");
                        check(MagicTowerSettings.values().number("esp.opacity") == 0.5, "50 percent input stores normalized opacity");
                        press(mc.gui.screen(), "Reset category"); press(mc.gui.screen(), "Apply");
                        press(mc.gui.screen(), "#FFB347");
                    }
                    case 10 -> {
                        capture(mc, "06-color.png");
                        EditBox hex = widgets(mc.gui.screen()).stream().filter(w -> w instanceof EditBox).map(w -> (EditBox) w).findFirst().orElseThrow();
                        hex.setValue("#bad"); check(!button(mc.gui.screen(), "Done").active, "Invalid hex cannot be accepted");
                        hex.setValue("#123456"); press(mc.gui.screen(), "Done");
                    }
                    case 11 -> { press(mc.gui.screen(), "Apply"); check(MagicTowerSettings.values().text("esp.color.chest").equals("#123456"), "Color picker applies validated RGB"); press(mc.gui.screen(), "Reset category"); press(mc.gui.screen(), "Apply"); press(mc.gui.screen(), "Routes"); }
                    case 12 -> { capture(mc, "07-routes.png"); press(mc.gui.screen(), "Hints"); }
                    case 13 -> { capture(mc, "08-hints.png"); press(mc.gui.screen(), "Status"); }
                    case 14 -> { capture(mc, "09-status.png");
                        var action = MagicTowerSettings.status().stream().filter(r -> r.action() != null).findFirst().orElseThrow();
                        check(!action.available().getAsBoolean(), "World actions disabled outside a world");
                        press(mc.gui.screen(), "Radar");
                        ((EditBox) mc.gui.screen().children().stream().filter(w -> w instanceof EditBox).findFirst().orElseThrow()).setValue("color");
                    }
                    case 15 -> { capture(mc, "10-search.png"); check(widgets(mc.gui.screen()).stream().anyMatch(w -> w instanceof Button && w.getMessage().getString().contains(" / ")), "Search finds settings across categories"); }
                    case 16 -> {
                        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), 854, 480);
                        mc.options.guiScale().set(4); mc.resizeGui();
                    }
                    case 17 -> { capture(mc, "11-small.png");
                        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), 1139, 755);
                        mc.options.guiScale().set(2); mc.resizeGui();
                        mc.getLanguageManager().setSelected("zh_tw"); mc.options.languageCode = "zh_tw";
                        mc.reloadResourcePacks();
                    }
                    case 18 -> { capture(mc, "12-zh-search.png"); press(mc.gui.screen(), "雷達"); }
                    case 19 -> { capture(mc, "13-zh-radar.png");
                        mc.getLanguageManager().setSelected("en_us"); mc.options.languageCode = "en_us";
                        mc.reloadResourcePacks();
                    }
                    case 20 -> { worldWaitAt = System.currentTimeMillis(); net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.testWorld(mc, () -> {}); }
                    case 21 -> {
                        if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen create)) {
                            if (System.currentTimeMillis() - worldWaitAt > 120_000) throw new AssertionError("World creation screen timeout");
                            step--; return;
                        }
                        create.getUiState().setName("Catbud Settings Verification"); create.getUiState().setAllowCommands(true);
                        press(create, "Create New World");
                    }
                    case 22 -> {
                        if (mc.player == null || mc.level == null) {
                            Screen screen = mc.gui.screen();
                            String name = screen == null ? "none" : screen.getClass().getSimpleName();
                            if (!name.equals(waitingScreen)) {
                                waitingScreen = name;
                                CatbudAddonsClient.LOGGER.info("World verification waiting: {} buttons={}", name,
                                    screen == null ? List.of() : widgets(screen).stream().filter(w -> w instanceof Button).map(w -> w.getMessage().getString()).toList());
                                capture(mc, "world-wait-" + name + ".png");
                            }
                            if (screen != null) for (var widget : widgets(screen))
                                if (widget instanceof Button b && b.active && List.of("Proceed", "I know what I'm doing!", "Yes").contains(b.getMessage().getString())) {
                                    b.onPress(new KeyEvent(257, 0, 0)); break;
                                }
                            if (System.currentTimeMillis() - worldWaitAt > 180_000) throw new AssertionError("World startup timeout");
                            step--; return;
                        }
                        mc.setScreenAndShow(null);
                        check(CatbudAddonsClient.session().forceStart(mc), "Force scan starts in a loaded world");
                        var pos = mc.player.blockPosition().offset(0, 0, 5);
                        var state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "copper_chest")).defaultBlockState();
                        mc.level.setBlock(pos, state, 3);
                    }
                    case 23 -> {
                        check(CatbudAddonsClient.session().targets().targetCount("chest") > 0, "Scanning discovers loaded copper chest");
                        net.minecraft.client.KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_Z));
                    }
                    case 24 -> {
                        check(mc.gui.screen() instanceof github.freshchromatic.catbud_addons.catbud_core.SettingsScreen, "Z opens shared settings in a world");
                        press(mc.gui.screen(), "Magic Tower"); press(mc.gui.screen(), "Status");
                    }
                    case 25 -> { capture(mc, "14-world-status.png");
                        var explore = MagicTowerSettings.status().stream().filter(r -> r.label().getString().equals("This floor: exploration routes")).findFirst().orElseThrow();
                        check(explore.available().getAsBoolean(), "Floor controls available while scanning"); explore.action().run();
                        check(!TargetLines.manualExplorationEnabled(), "Floor exploration override stops exploration");
                        check(MagicTowerSettings.values().bool("routes.exploreDefault"), "Floor override is not persisted");
                        TargetLines.setAutoSuppressed(WorldTargets.PortalType.NETHER, true);
                        CatbudAddonsClient.session().onMessage(net.minecraft.network.chat.Component.translatable("plugins.magic_tower.waiting_layer_response"), "Title");
                        TargetLines.tick(mc, CatbudAddonsClient.session());
                        check(TargetLines.manualExplorationEnabled() && !TargetLines.autoSuppressed(WorldTargets.PortalType.NETHER), "New floor restores defaults and clears portal suppression");
                        CatbudAddonsClient.session().forceStop(); TargetLines.tick(mc, CatbudAddonsClient.session());
                        check(!CatbudAddonsClient.session().isActive() && CatbudAddonsClient.session().targets().chests().isEmpty(), "Stop clears markers and disables scanning");
                        CatbudAddonsClient.session().useAuto();
                        check(CatbudAddonsClient.session().modeKey().equals("auto"), "Automatic detection can be restored");
                        CatbudAddonsClient.session().forceStart(mc);
                        TargetLines.tick(mc, CatbudAddonsClient.session());
                        check(TargetLines.manualExplorationEnabled(), "New session restores saved exploration default");
                        press(mc.gui.screen(), "General");
                    }
                    case 26 -> {
                        press(mc.gui.screen(), "Enable Magic Tower"); press(mc.gui.screen(), "Apply");
                        check(!CatbudAddonsClient.session().isActive() && CatbudAddonsClient.session().targets().chests().isEmpty(), "Master disable immediately clears active markers");
                        press(mc.gui.screen(), "Reset category"); press(mc.gui.screen(), "Apply");
                        mc.setScreenAndShow(new net.minecraft.client.gui.screens.ChatScreen("", false));
                        net.minecraft.client.KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_Z));
                    }
                    case 27 -> {
                        check(mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen, "Z does not interrupt chat");
                        mc.setScreenAndShow(CatbudCore.screen(null)); press(mc.gui.screen(), "Magic Tower"); press(mc.gui.screen(), "Routes"); scrollBottom(mc.gui.screen());
                    }
                    case 28 -> { capture(mc, "15-routes-bottom.png"); press(mc.gui.screen(), "Radar"); scrollBottom(mc.gui.screen()); }
                    case 29 -> { capture(mc, "16-radar-bottom.png"); press(mc.gui.screen(), "Status"); scrollBottom(mc.gui.screen()); }
                    case 30 -> { capture(mc, "17-status-bottom.png");
                        Files.write(mc.gameDirectory.toPath().resolve("verification-results.txt"), results); mc.stop();
                    }
                    case 40 -> {
                        mc.options.pauseOnLostFocus = false; mc.options.guiScale().set(2); mc.resizeGui();
                        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), 1139, 755);
                        mc.setScreenAndShow(CatbudCore.screen(null)); press(mc.gui.screen(), "Magic Tower"); press(mc.gui.screen(), "Routes");
                        scrollBottom(mc.gui.screen()); hover(mc, 100, 350);
                    }
                    case 41 -> { capture(mc, "18-tooltip-desktop.png"); press(mc.gui.screen(), "Status"); hover(mc, 100, 215); }
                    case 42 -> { capture(mc, "19-status-tooltip-desktop.png");
                        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), 854, 480);
                        press(mc.gui.screen(), "Routes"); scrollBottom(mc.gui.screen()); hover(mc, 100, 180);
                    }
                    case 43 -> { capture(mc, "20-tooltip-compact.png"); press(mc.gui.screen(), "Status"); hover(mc, 100, 215); }
                    case 44 -> { capture(mc, "21-status-tooltip-compact.png"); mc.stop(); }
                    default -> mc.stop();
                }
            } catch (Throwable e) {
                capture(mc, "verification-failure.png");
                results.add("FAILED step " + (step - 1) + ": " + e);
                CatbudAddonsClient.LOGGER.error("Settings verification failed", e);
                try { Files.write(mc.gameDirectory.toPath().resolve("verification-results.txt"), results); } catch (Exception ignored) {}
                mc.stop();
            }
        });
    }
    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        results.add("PASS " + label);
    }
    private static List<AbstractWidget> widgets(ContainerEventHandler root) {
        List<AbstractWidget> output = new ArrayList<>();
        for (GuiEventListener child : root.children()) {
            if (child instanceof AbstractWidget widget) output.add(widget);
            if (child instanceof ContainerEventHandler container) output.addAll(widgets(container));
        }
        return output;
    }
    private static Button button(Screen screen, String label) {
        return widgets(screen).stream().filter(w -> w instanceof Button && w.getMessage().getString().equals(label))
            .map(w -> (Button) w).findFirst().orElseThrow(() -> new IllegalStateException("Missing button " + label));
    }
    private static void press(Screen screen, String label) {
        Button button = button(screen, label);
        if (!button.active) throw new IllegalStateException("Inactive button " + label);
        button.onPress(new KeyEvent(257, 0, 0));
    }
    private static void scrollBottom(Screen screen) {
        widgets(screen).stream().filter(w -> w instanceof AbstractScrollArea).map(w -> (AbstractScrollArea) w)
            .forEach(list -> list.setScrollAmount(list.maxScrollAmount()));
    }
    private static void hover(Minecraft mc, int x, int y) {
        // Set the test client's pointer state even when Windows keeps its window unfocused.
        try {
            var xpos = mc.mouseHandler.getClass().getDeclaredField("xpos"); xpos.setAccessible(true); xpos.setDouble(mc.mouseHandler, x);
            var ypos = mc.mouseHandler.getClass().getDeclaredField("ypos"); ypos.setAccessible(true); ypos.setDouble(mc.mouseHandler, y);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static void capture(Minecraft mc, String file) {
        Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1, text -> {});
    }
}
