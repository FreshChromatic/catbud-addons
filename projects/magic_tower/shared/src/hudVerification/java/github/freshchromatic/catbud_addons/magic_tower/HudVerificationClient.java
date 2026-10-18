package github.freshchromatic.catbud_addons.magic_tower;

import com.mojang.blaze3d.platform.InputConstants;

import github.freshchromatic.catbud_addons.catbud_core.*;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

import java.nio.file.Files;
import java.util.*;

public final class HudVerificationClient implements ClientModInitializer {

    private int                ticks, step;
    private final List<String> results = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!HudVerificationPlatform.ready(client) || ++ticks % 30 != 0) {
                return;
            }
            try {
                run(client);
            } catch (Exception | AssertionError error) {
                error.printStackTrace();
                results.add("FAIL " + error);
                finish(client);
            }
        });
    }

    private void run(Minecraft client) throws Exception {
        switch (step++) {
            case 0 -> {
                if (!client.options.languageCode.equals("en_us")) {
                    client.getLanguageManager().setSelected("en_us");
                    client.options.languageCode = "en_us";
                    client.reloadResourcePacks();
                    step--;
                    return;
                }
                client.options.pauseOnLostFocus = false;
                client.options.guiScale().set(2);
                HudVerificationPlatform.size(client, 1280, 800);
                HudVerificationPlatform.resize(client);
                ClientPlatform.show(client, CatbudCore.screen(ClientPlatform.screen(client)));
                press(client, "HUD");
            }
            case 1 -> {
                capture(client, "hud-01-central.png");
                check(CatbudCore.huds().elements().size() == 3, "Three HUD elements registered");
                check(CatbudCore.huds().elements().stream().noneMatch(HudElement::visible),
                    "HUD hidden outside an active tower");
                press(client, "HUD editor");
            }
            case 2 -> {
                capture(client, "hud-02-editor-default.png");
                var editor = (HudEditorScreen) ClientPlatform.screen(client);
                verifyPointerEditing(client, editor);
                var fixed = editor.session().config(editor.session().primary()).style().edit(b -> {
                    b.fixedWidth = 80;
                    b.fixedHeight = 32;
                });
                var measured = HudRenderer.measure(new HudContent(null, List.of(net.minecraft.network.chat.Component
                    .literal("A very long tower information line"))), fixed);
                check(measured.width() == 80 && measured.height() == 32 && measured.contentScale() < 1,
                    "Fixed dimensions fit long text inside the specified box");
                int row = 0;
                for (HudElement element : CatbudCore.huds().elements()) {
                    int y = 70 + row++ * 65;
                    editor.session().update(element.id(), c -> c.withPlacement(new HudPlacement(null, 165, y, 1)));
                }
                press(client, "Font & text");
                press(client, "Font: Minecraft");
                press(client, "Noto Sans TC");
                check(editor.session().config(editor.session().primary()).style().font() == HudStyle.FontFace.SANS,
                    "Font selector updates the draft");
                check(CatbudCore.hudStore().current().isEmpty(), "Editing does not publish HUD configuration");
            }
            case 3 -> {
                capture(client, "hud-03-font.png");
                var editor = (HudEditorScreen) ClientPlatform.screen(client);
                int textColor = editor.session().config(editor.session().primary()).style().textColor();
                press(client, String.format(Locale.ROOT, "Text color: #%06X", textColor & 0xFFFFFF));
                var input = widgets(ClientPlatform.screen(client)).stream().filter(EditBox.class::isInstance).map(
                    EditBox.class::cast).findFirst().orElseThrow();
                input.setValue("#FFD052");
                press(client, "Cancel");
                check(ClientPlatform.screen(client) instanceof DiscardChangesScreen,
                    "Color changes require discard confirmation");
                press(client, "Keep editing");
                check(widgets(ClientPlatform.screen(client)).stream().filter(EditBox.class::isInstance).map(
                    EditBox.class::cast).anyMatch(box -> box.getValue().equals("#FFD052")),
                    "Color confirmation retains typed value");
                press(client, "Done");
            }
            case 4 -> {
                capture(client, "hud-04-color.png");
                var editor = (HudEditorScreen) ClientPlatform.screen(client);
                check((editor.session().config(editor.session().primary()).style().textColor() & 0xFFFFFF) == 0xFFD052,
                    "Color picker keeps the editor draft");
                press(client, "Return to settings");
                check(CatbudCore.hudStore().current().isEmpty(), "Returning to settings does not save");
                press(client, "Cancel");
                check(ClientPlatform.screen(client) instanceof DiscardChangesScreen,
                    "Central settings protect returned HUD drafts");
                press(client, "Keep editing");
                press(client, "Apply");
                check(CatbudCore.hudStore().current().size() == 3, "Central Apply saves all three HUD configurations");
                press(client, "HUD editor");
                editor = (HudEditorScreen) ClientPlatform.screen(client);
                editor.session().updateSelected(c -> c.withStyle(c.style().edit(b -> b.radius = 20)));
                var dirty = editor.session().snapshot();
                press(client, "Cancel");
                check(ClientPlatform.screen(client) instanceof DiscardChangesScreen,
                    "Cancel asks before discarding changes");
                capture(client, "hud-07-discard.png");
                press(client, "Keep editing");
                check(((HudEditorScreen) ClientPlatform.screen(client)).session().snapshot().equals(dirty),
                    "Keep editing retains every change");
                editor.setFocused(null);
                editor.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
                check(ClientPlatform.screen(client) instanceof DiscardChangesScreen,
                    "Escape uses the same discard confirmation");
                press(client, "Discard and leave");
                check(CatbudCore.hudStore().current().entrySet().stream().allMatch(entry -> entry.getValue().style()
                    .radius() == CatbudCore.huds().get(entry.getKey()).defaults().style().radius()),
                    "Cancel retains saved appearance");
                press(client, "HUD editor");
                editor = (HudEditorScreen) ClientPlatform.screen(client);
                editor.session().updateSelected(c -> c.withStyle(c.style().edit(b -> b.radius = 11)));
                press(client, "Save HUD");
                check(CatbudCore.hudStore().current().values().stream().anyMatch(c -> c.style().radius() == 11),
                    "Save HUD writes without leaving the editor");
                press(client, "Cancel");
                check(ClientPlatform.screen(client) instanceof SettingsScreen,
                    "Saved editor closes without an unnecessary confirmation");
                press(client, "HUD editor");
                press(client, "Size");
                var unfinished = widgets(ClientPlatform.screen(client)).stream().filter(EditBox.class::isInstance).map(
                    EditBox.class::cast).filter(input -> input.getMessage().getString().equals("Fixed width (0 auto)"))
                    .findFirst().orElseThrow();
                String initialWidth = unfinished.getValue();
                unfinished.setValue("-");
                press(client, "Cancel");
                check(ClientPlatform.screen(client) instanceof DiscardChangesScreen,
                    "An unfinished numeric edit alone requires confirmation");
                press(client, "Keep editing");
                unfinished = widgets(ClientPlatform.screen(client)).stream().filter(EditBox.class::isInstance).map(
                    EditBox.class::cast).filter(input -> input.getMessage().getString().equals("Fixed width (0 auto)"))
                    .findFirst().orElseThrow();
                check(unfinished.getValue().equals("-"), "Continue editing preserves invalid input exactly");
                unfinished.setValue(initialWidth);
                press(client, "Cancel");
                check(ClientPlatform.screen(client) instanceof SettingsScreen,
                    "Reverting the unfinished edit removes the confirmation");
                client.getLanguageManager().setSelected("zh_tw");
                client.options.languageCode = "zh_tw";
                client.reloadResourcePacks();
            }
            case 5 -> {
                press(client, "HUD 編輯器");
                var editor = (HudEditorScreen) ClientPlatform.screen(client);
                editor.session().updateSelected(c -> c.withStyle(c.style().edit(b -> {
                    b.boxShadow = new HudStyle.Shadow(true, 0xB0000000, 3, 3, 6);
                    b.radius = 8;
                })));
            }
            case 6 -> {
                capture(client, "hud-05-chinese.png");
                HudVerificationPlatform.size(client, 854, 480);
                client.options.guiScale().set(3);
                HudVerificationPlatform.resize(client);
            }
            case 7 -> {
                capture(client, "hud-06-compact.png");
                check(ClientPlatform.screen(client) instanceof HudEditorScreen, "Compact editor survives resizing");
                press(client, "取消");
                check(ClientPlatform.screen(client) instanceof DiscardChangesScreen,
                    "Compact editor protects unsaved changes");
                press(client, "放棄修改並離開");
                HudVerificationPlatform.size(client, 1280, 800);
                client.options.guiScale().set(2);
                HudVerificationPlatform.resize(client);
                press(client, "HUD 編輯器");
                var editor = (HudEditorScreen) ClientPlatform.screen(client);
                editor.keyPressed(new KeyEvent(InputConstants.KEY_A, 0, InputConstants.MOD_CONTROL));
                press(client, "複製與操作");
                press(client, "合併所選背景");
            }
            case 8 -> {
                capture(client, "hud-08-merged.png");
                var editor = (HudEditorScreen) ClientPlatform.screen(client);
                var frame = HudFrame.measure(CatbudCore.huds(), editor.session().snapshot(), e -> true, true,
                    editor.width, editor.height, HudRenderer.guiScale());
                check(frame.backgrounds().size() == 1, "Merged HUDs render a single shared background");
                press(client, "複製元素");
                check(CatbudCore.huds().elements(editor.session().snapshot()).size() == 6,
                    "Duplicate creates independent HUD instances");
                frame = HudFrame.measure(CatbudCore.huds(), editor.session().snapshot(), e -> true, true, editor.width,
                    editor.height, HudRenderer.guiScale());
                check(frame.backgrounds().size() == 2, "Duplicated group gets its own background");
                press(client, "尺寸");
                var copyInput = widgets(ClientPlatform.screen(client)).stream().filter(EditBox.class::isInstance).map(
                    EditBox.class::cast).filter(input -> input.getMessage().getString().equals("固定寬度（0 自動）"))
                    .findFirst().orElseThrow();
                copyInput.setValue("-");
                press(client, "撤銷");
                editor.onClose();
                check(ClientPlatform.screen(client) instanceof DiscardChangesScreen,
                    "Removing a copy with unfinished input keeps the change summary valid");
                press(client, "繼續編輯");
                editor.keyPressed(new KeyEvent(InputConstants.KEY_A, 0, InputConstants.MOD_CONTROL));
                press(client, "複製與操作");
                press(client, "分離所選背景");
                press(client, "錨定與比例");
                press(client, "獨立 GUI 比例：關閉");
                check(editor.session().snapshot().values().stream().allMatch(c -> c.placement().guiScale() > 0),
                    "Independent scale applies to all selected HUDs");
                editor.session().select("catbud_addons:tower_floor", false);
                press(client, "顯示／隱藏面板");
                press(client, "顯示／隱藏面板");
                press(client, "相對於：螢幕");
                press(client, "貓幣");
                check(editor.session().config("catbud_addons:tower_floor").placement().relativeTo().equals(
                    "catbud_addons:tower_coins"), "Attachment picker targets another HUD directly");
                editor.session().select("catbud_addons:tower_coins", false);
                press(client, "文字格式");
                press(client, "字重（Noto 支援四級）：標準 400");
                press(client, "半粗 600");
                check(editor.session().config("catbud_addons:tower_coins").style().formatting()
                    .weight() == HudTextFormat.Weight.SEMIBOLD,
                    "Weight picker applies the real Noto semibold resource");
                press(client, "底線：關閉");
                check(editor.session().config("catbud_addons:tower_coins").style().formatting()
                    .weight() == HudTextFormat.Weight.SEMIBOLD,
                    "Changing text decorations preserves the chosen weight");
            }
            case 9 -> {
                capture(client, "hud-09-typography.png");
                press(client, "取消");
                press(client, "放棄修改並離開");
                var path = client.gameDirectory.toPath().resolve("layout-preview.json");
                if (Files.exists(path)) {
                    var store = new HudConfigStore(path);
                    HudVerificationPlatform.size(client, 1920, 990);
                    client.options.guiScale().set(3);
                    HudVerificationPlatform.resize(client);
                    ClientPlatform.show(client, new HudEditorScreen(ClientPlatform.screen(client), store.current(),
                        null, ignored -> {
                        }));
                    press(client, "顯示／隱藏面板");
                    ((HudEditorScreen) ClientPlatform.screen(client)).session().selectAll(List.of());
                } else {
                    finish(client);
                }
            }
            case 10 -> {
                var editor = (HudEditorScreen) ClientPlatform.screen(client);
                var frame = HudFrame.measure(CatbudCore.huds(), editor.session().snapshot(), e -> true, true,
                    editor.width, editor.height, HudRenderer.guiScale());
                boolean background = frame.entries().stream().anyMatch(entry -> entry.config().style().background());
                check(frame.backgrounds().size() == (background ? 1 : 0), "User layout respects background visibility");
                double left = frame.entries().stream().mapToDouble(entry -> entry.bounds().x()).min().orElseThrow();
                double right = frame.entries().stream().mapToDouble(entry -> entry.bounds().x() + entry.bounds()
                    .width()).max().orElseThrow();
                check(Math.abs((left + right) / 2 - editor.width / 2.0) < 0.01, "User status bar remains centered");
                check(frame.entries().stream().allMatch(entry -> Math.abs(entry.bounds().y() - 22) < 0.01 && Math.abs(
                    entry.bounds().height() - 24) < 0.01 && entry.layout().contentScale() == 1),
                    "User layout aligns all cells without shrinking text");
                capture(client, "hud-user-status-bar.png");
            }
            case 11 -> {
                finish(client);
            }
            default -> {
            }
        }
    }

    private void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
        results.add("PASS " + description);
    }

    private void verifyPointerEditing(Minecraft client, HudEditorScreen editor) {
        press(client, "Show / hide panels");
        var initialConfigs = editor.session().snapshot();
        String id = editor.session().primary();
        editor.session().selectAll(initialConfigs.keySet());
        editor.session().updateSelected(config -> config.withStyle(config.style().edit(
            style -> style.backgroundGroup = "")));
        editor.session().select(id, false);
        editor.keyPressed(new KeyEvent(InputConstants.KEY_A, 97, InputConstants.MOD_CONTROL));
        check(editor.session().selection().size() == 3, "Ctrl+A selects all elements");
        editor.session().select(id, false);
        var original = editor.session().config(id);
        editor.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, InputConstants.MOD_SHIFT));
        check(editor.session().config(id).placement().x() == original.placement().x() + 10,
            "Shift+arrow moves the selected element ten units");
        editor.keyPressed(new KeyEvent(InputConstants.KEY_Z, 122, InputConstants.MOD_CONTROL));
        check(editor.session().config(id).equals(original), "Ctrl+Z undoes the keyboard move");
        var layout = HudRenderer.measure(CatbudCore.huds().get(id).content(true), original.style());
        var box = HudLayoutEngine.place(original.placement(), layout.width(), layout.height(), editor.width,
            editor.height);
        var down = new net.minecraft.client.input.MouseButtonEvent(box.x() + 10, box.y() + 10,
            new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOD_ALT));
        var moved = new net.minecraft.client.input.MouseButtonEvent(down.x() + 30, down.y() + 20, down.buttonInfo());
        editor.mouseClicked(down, false);
        editor.mouseDragged(moved, 30, 20);
        editor.mouseReleased(moved);
        check(editor.session().config(id).placement().x() == original.placement().x() + 30,
            "Pointer drag changes the HUD position");
        editor.session().undo();
        check(editor.session().config(id).equals(original), "One Undo reverses an entire drag");
        var corner = new net.minecraft.client.input.MouseButtonEvent(box.x(), box.y() + box.height(), down
            .buttonInfo());
        var resized = new net.minecraft.client.input.MouseButtonEvent(corner.x() - 20, corner.y() + 10, down
            .buttonInfo());
        editor.mouseClicked(corner, false);
        editor.mouseDragged(resized, -20, 10);
        editor.mouseReleased(resized);
        check(editor.session().config(id).placement().scale() > original.placement().scale(),
            "Corner handle resizes the element");
        editor.session().undo();
        editor.session().beginGesture();
        initialConfigs.forEach((element, config) -> editor.session().update(element, ignored -> config));
        editor.session().endGesture();
        press(client, "Show / hide panels");
    }

    private static List<GuiEventListener> widgets(ContainerEventHandler container) {
        List<GuiEventListener> result = new ArrayList<>();
        for (GuiEventListener child : container.children()) {
            result.add(child);
            if (child instanceof ContainerEventHandler nested) {
                result.addAll(widgets(nested));
            }
        }
        return result;
    }

    private static void press(Minecraft client, String label) {
        widgets(ClientPlatform.screen(client)).stream().filter(Button.class::isInstance).map(Button.class::cast).filter(
            button -> button.active && button.getMessage().getString().equals(label)).findFirst().orElseThrow(
                () -> new AssertionError("Missing button: " + label)).onPress(new KeyEvent(257, 0, 0));
    }

    private static void capture(Minecraft client, String name) {
        HudVerificationPlatform.capture(client, name);
    }

    private void finish(Minecraft client) {
        try {
            Files.write(client.gameDirectory.toPath().resolve("hud-verification-results.txt"), results);
        } catch (Exception error) {
            error.printStackTrace();
        }
        step = 100;
        client.stop();
    }
}
