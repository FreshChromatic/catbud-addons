package github.freshchromatic.catbud_addons.public_warps;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Objects;

public final class OwnerEditScreen extends Screen {

    private final WarpScreen       parent;
    private final WarpData         warp;
    private final OwnerEditSession session;
    private final OwnerEditModel   model;
    private EditBox                name;
    private MultiLineEditBox       description;
    private Button                 save, adopt, retry;
    private int                    x, y, panelWidth, panelHeight;

    public OwnerEditScreen(WarpScreen parent, WarpData warp, OwnerEditSession session) {
        super(Component.translatable("warps.owner.title"));
        this.parent = parent;
        this.warp = warp;
        this.session = session;
        model = new OwnerEditModel(warp);
    }

    @Override
    protected void init() {
        panelWidth = Math.min(350, width - 20);
        panelHeight = Math.min(280, height - 12);
        x = (width - panelWidth) / 2;
        y = (height - panelHeight) / 2;
        name = addRenderableWidget(new EditBox(font, x + 10, y + 64, panelWidth - 20, 18, Component.translatable(
            "warps.custom_name")));
        name.setMaxLength(64);
        name.setValue(model.displayName);
        name.setResponder(value -> model.displayName = value);
        description = addRenderableWidget(MultiLineEditBox.builder().setX(x + 10).setY(y + 103).setPlaceholder(Component
            .translatable("warps.owner.description_hint")).build(font, panelWidth - 20, Math.max(35, panelHeight - 185),
                Component.translatable("warps.description")));
        description.setCharacterLimit(512);
        description.setValue(model.description);
        description.setValueListener(value -> model.description = value);
        int footer = y + panelHeight - 28;
        addRenderableWidget(new WarpScreen.BrowserButton(x + panelWidth - 132, footer, 58, 20, Component.translatable(
            "gui.cancel"), button -> onClose()));
        save = addRenderableWidget(new WarpScreen.BrowserButton(x + panelWidth - 68, footer, 58, 20, Component
            .translatable("warps.owner.save"), button -> session.save(model)));
        adopt = addRenderableWidget(new WarpScreen.BrowserButton(x + 10, footer - 24, 125, 18, Component.translatable(
            "warps.owner.adopt_latest"), button -> adoptLatest()));
        retry = addRenderableWidget(new WarpScreen.BrowserButton(x + 141, footer - 24, panelWidth - 151, 18, Component
            .translatable("warps.owner.keep_mine"), button -> {
                model.revision = model.latest.revision;
                session.save(model);
            }));
        setInitialFocus(name);
    }

    private void adoptLatest() {
        model.displayName = Objects.toString(model.latest.displayName, "");
        model.description = Objects.toString(model.latest.description, "");
        model.revision = model.latest.revision;
        name.setValue(model.displayName);
        description.setValue(model.description);
        model.latest = null;
        model.state = OwnerEditModel.State.IDLE;
        model.messageKey = "warps.owner.ready";
    }

    @Override
    public void tick() {
        if (model.state == OwnerEditModel.State.SAVED) {
            onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        parent.extractRenderState(graphics, -1, -1, partialTick);
        graphics.fill(0, 0, width, height, 0xA0000000);
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xFF303030);
        graphics.outline(x, y, panelWidth, panelHeight, 0xFFAAAAAA);
        graphics.text(font, title, x + 10, y + 9, 0xFFFFFFFF);
        graphics.text(font, Component.translatable("warps.owner.account", PublicWarpsClient.INSTANCE.serverLabel(),
            warp.name), x + 10, y + 27, 0xFFBBBBBB);
        graphics.text(font, Component.translatable("warps.custom_name"), x + 10, y + 50, 0xFFFFFFFF);
        graphics.text(font, Component.translatable("warps.description"), x + 10, y + 89, 0xFFFFFFFF);
        name.active = description.active = !model.busy();
        save.active = !model.busy() && (model.state != OwnerEditModel.State.CONFLICT || model.latest == null);
        boolean conflict = model.state == OwnerEditModel.State.CONFLICT && model.latest != null;
        adopt.visible = retry.visible = conflict;
        if (conflict) {
            String latest = Component.translatable("warps.owner.latest", Objects.toString(model.latest.displayName, ""),
                Objects.toString(model.latest.description, "")).getString();
            graphics.text(font, font.plainSubstrByWidth(latest.replace('\n', ' '), panelWidth - 20), x + 10, y
                + panelHeight - 83, 0xFFBBBBBB);
        }
        int lineY = y + panelHeight - 72;
        for (var line : font.split(Component.translatable(model.messageKey), panelWidth - 20).stream().limit(2)
            .toList()) {
            graphics.text(font, line, x + 10, lineY, model.state == OwnerEditModel.State.ERROR
                || model.state == OwnerEditModel.State.CONFLICT ? 0xFFFF9999 : 0xFFBBBBBB);
            lineY += 10;
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        model.close();
        ClientPlatform.show(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        model.close();
    }
}
