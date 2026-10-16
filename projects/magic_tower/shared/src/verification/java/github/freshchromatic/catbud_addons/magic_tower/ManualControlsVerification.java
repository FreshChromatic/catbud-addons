package github.freshchromatic.catbud_addons.magic_tower;

import static github.freshchromatic.catbud_addons.magic_tower.SettingsVerificationClient.*;

import github.freshchromatic.catbud_addons.catbud_core.CatbudCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

final class ManualControlsVerification {

    private final List<String> results = new ArrayList<>();
    private int                step;
    private String             worldId;
    private long               reconnectStartedAt;

    void start(Minecraft client) {
        worldId = client.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .normalize().getFileName().toString();
        client.options.pauseOnLostFocus = false;
        client.options.guiScale().set(2);
        client.resizeGui();
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(client.getWindow().handle(), 1139, 755);
        openStatus(client);
    }

    void tick(Minecraft client) throws Exception {
        switch (step++) {
            case 0 -> {
                checkMode("auto");
                check(!hasButton(client, "Force scanning this connection"), "Manual actions start hidden");
                capture(client, "manual-01-automatic.png");
                press(client.gui.screen(), "+ Manual controls…");
                scrollBottom(client.gui.screen());
            }
            case 1 -> {
                check(hasButton(client, "Force scanning this connection"), "Expanding reveals manual actions");
                capture(client, "manual-02-expanded.png");
                press(client.gui.screen(), "Force scanning this connection");
            }
            case 2 -> {
                checkConfirmation(client);
                checkMode("auto");
                capture(client, "manual-03-force-confirmation.png");
                client.gui.screen().keyPressed(new KeyEvent(256, 0, 0));
            }
            case 3 -> {
                checkMode("auto");
                check(!(client.gui.screen() instanceof ConfirmScreen), "Escape returns to settings");
                press(client.gui.screen(), "Force scanning this connection");
            }
            case 4 -> press(client.gui.screen(), "Confirm forced scanning");
            case 5 -> {
                checkMode("forced");
                capture(client, "manual-04-forced.png");
                press(client.gui.screen(), "Restore automatic detection");
            }
            case 6 -> {
                checkMode("auto");
                check(!(client.gui.screen() instanceof ConfirmScreen),
                    "Automatic detection restores without confirmation");
                press(client.gui.screen(), "Stop scanning this connection");
            }
            case 7 -> {
                checkConfirmation(client);
                checkMode("auto");
                capture(client, "manual-05-stop-confirmation.png");
                press(client.gui.screen(), "Cancel");
            }
            case 8 -> {
                checkMode("auto");
                press(client.gui.screen(), "Stop scanning this connection");
            }
            case 9 -> press(client.gui.screen(), "Confirm stop scanning");
            case 10 -> {
                checkMode("off");
                capture(client, "manual-06-stopped.png");
                client.gui.screen().onClose();
                openStatus(client);
            }
            case 11 -> {
                checkMode("off");
                check(!hasButton(client, "Force scanning this connection"),
                    "Reopening collapses controls without changing mode");
                press(client.gui.screen(), "Restore automatic detection");
            }
            case 12 -> {
                checkMode("auto");
                client.getLanguageManager().setSelected("zh_tw");
                client.options.languageCode = "zh_tw";
                client.reloadResourcePacks();
            }
            case 13 -> {
                client.setScreenAndShow(null);
                openStatus(client);
            }
            case 14 -> {
                capture(client, "manual-07-zh-automatic.png");
                press(client.gui.screen(), "+ 手動控制…");
                scrollBottom(client.gui.screen());
            }
            case 15 -> {
                capture(client, "manual-08-zh-expanded.png");
                press(client.gui.screen(), "本次連線停止掃描");
            }
            case 16 -> {
                check(client.gui.screen() instanceof ConfirmScreen, "Chinese confirmation opens");
                capture(client, "manual-09-zh-confirmation.png");
                press(client.gui.screen(), "確認停止掃描");
            }
            case 17 -> {
                checkMode("off");
                client.disconnect(new TitleScreen(), false);
            }
            case 18 -> {
                checkMode("auto");
                check(client.getConnection() == null, "Disconnect ends the manual override");
                reconnectStartedAt = System.currentTimeMillis();
                client.createWorldOpenFlows().openWorld(worldId, () -> {
                });
            }
            case 19 -> {
                if (client.level == null || client.player == null) {
                    if (client.gui.screen() instanceof BackupConfirmScreen) {
                        press(client.gui.screen(), Component.translatable("selectWorld.backupJoinSkipButton")
                            .getString());
                    } else if (client.gui.screen() instanceof ConfirmScreen) {
                        press(client.gui.screen(), Component.translatable("gui.yes").getString());
                    }
                    if (System.currentTimeMillis() - reconnectStartedAt > 120_000) {
                        throw new AssertionError("Reconnect timed out");
                    }
                    step--;
                    return;
                }
                checkMode("auto");
                openStatus(client);
            }
            case 20 -> {
                check(!hasButton(client, "本次連線強制掃描"), "Reconnecting starts with manual controls collapsed");
                capture(client, "manual-10-reconnected.png");
                Files.write(client.gameDirectory.toPath().resolve("manual-controls-results.txt"), results);
                client.stop();
            }
            default -> throw new IllegalStateException("Unexpected verification step");
        }
    }

    private void checkConfirmation(Minecraft client) {
        check(client.gui.screen() instanceof ConfirmScreen, "Manual action opens confirmation");
        check(client.gui.screen().getFocused() instanceof Button button && button.getMessage().getString().equals(
            "Cancel"), "Cancel has initial keyboard focus");
    }

    private void checkMode(String expected) {
        check(CatbudAddonsClient.session().modeKey().equals(expected), "Mode is " + expected + " at step " + (step
            - 1));
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        results.add("PASS " + message);
    }

    private boolean hasButton(Minecraft client, String label) {
        return widgets(client.gui.screen()).stream().anyMatch(widget -> widget instanceof Button && widget.getMessage()
            .getString().equals(label));
    }

    private void openStatus(Minecraft client) {
        client.setScreenAndShow(CatbudCore.screen(null));
        String category = client.options.languageCode.equals("zh_tw") ? "狀態" : "Status";
        press(client.gui.screen(), "Magic Tower");
        press(client.gui.screen(), category);
    }
}
