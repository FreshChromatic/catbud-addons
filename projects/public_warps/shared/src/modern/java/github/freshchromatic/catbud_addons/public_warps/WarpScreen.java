package github.freshchromatic.catbud_addons.public_warps;

import com.mojang.blaze3d.platform.InputConstants;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.*;

/** Browser grammar read from CustomSchematicBrowser/CustomDirectoryEntry, implemented independently. */
public final class WarpScreen extends Screen {

    private final PublicWarpsClient          data          = PublicWarpsClient.INSTANCE;
    private final PreviewRenderer            previews      = new PreviewRenderer();
    private final Map<String, PreviewMotion> motions       = new HashMap<>();
    private final PreviewCameraControl       cameraControl = new PreviewCameraControl();
    private boolean                          previewVisible;
    private int                              previewTop;
    private boolean                          draggingPreview;
    private EditBox                          search;
    private Button                           teleport, editOwn;
    private String                           selected, query = "";
    private int                              left          = 12, top = 32, gridWidth, gridTop, bottom, sideX, sideWidth,
        columns, tile, scroll;
    private long                             clickedAt;
    private String                           clickedKey;
    private String                           hoveredPreview;

    public WarpScreen() {
        super(Component.translatable("warps.title"));
    }

    private String tr(String key) {
        return Component.translatable(key).getString();
    }

    @Override
    protected void init() {
        sideWidth = Math.min(170, Math.max(100, width / 4));
        sideX = width - sideWidth - 12;
        gridWidth = sideX - left - 6;
        columns = columnCount(gridWidth);
        tile = Math.max(24, (gridWidth - 12 - (columns - 1) * 2) / columns);
        bottom = height - 36;
        gridTop = top + (data.frequent().isEmpty() ? 21 : 58);
        search = addRenderableWidget(new EditBox(font, left + 22, top + 4, Math.max(50, gridWidth - 106), 14, Component
            .translatable("warps.search")));
        search.setHint(Component.translatable("warps.search"));
        search.setValue(query);
        search.setResponder(s -> {
            query = s;
            scroll = 0;
        });
        addRenderableWidget(new BrowserButton(sideX - 78, top + 2, 70, 18, Component.translatable("warps.refresh"),
            b -> {
                data.refresh(true);
                data.requestSuggestions();
                previews.clear();
            }));
        teleport = addRenderableWidget(new BrowserButton(left, height - 28, 140, 20, Component.translatable(
            "warps.teleport"), b -> {
                WarpData w = selection();
                if (w != null) {
                    if (w.canTeleport()) {
                        data.teleport(w);
                    } else {
                        data.retryTeleport(w);
                    }
                }
            }));
        editOwn = addRenderableWidget(new BrowserButton(left + 148, height - 28, 104, 20, Component.translatable(
            "warps.owner.edit"), button -> {
                WarpData own = selection();
                if (data.canEditOwnWarp(own)) {
                    ClientPlatform.show(minecraft, new OwnerEditScreen(this, own, data.ownerEditSession(own)));
                }
            }));
        editOwn.visible = false;
        addRenderableWidget(new BrowserButton(width - 92, height - 28, 80, 20, Component.translatable("gui.done"),
            b -> onClose()));
    }

    private static int columnCount(int width) {
        if (width >= 320) {
            return 5;
        }
        return width >= 230 ? 4 : 3;
    }

    private int tileBackground(int index, boolean highlighted) {
        if (highlighted) {
            return 0x70FFFFFF;
        }
        boolean evenTile = ((index / columns + index % columns) & 1) == 0;
        return evenTile ? 0x38FFFFFF : 0x20FFFFFF;
    }

    private List<WarpData> entries() {
        String q = query.toLowerCase(Locale.ROOT);
        return data.warps().stream().filter(w -> w.searchText().toLowerCase(Locale.ROOT).contains(q)).toList();
    }

    private WarpData selection() {
        return data.warps().stream().filter(w -> w.key().equals(selected)).findFirst().orElse(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        previews.beginFrame();
        g.fill(0, 0, width, height, 0x88000000);
        g.text(font, Component.translatable("warps.title.server", data.serverLabel()), left + 6, 12, 0xFFFFFFFF);
        g.text(font, tr("warps.total") + ": " + data.warps().size(), sideX, 12, 0xFFBBBBBB);
        g.fill(left, top, sideX - 3, bottom, 0xA0000000);
        g.outline(left, top, gridWidth + 3, bottom - top, 0xFF999999);
        g.fill(sideX, top, sideX + sideWidth, bottom, 0xA0000000);
        g.outline(sideX, top, sideWidth, bottom - top, 0xFF999999);
        g.text(font, "▦", left + 4, top + 5, 0xFFDDDDDD);
        List<WarpData> common = data.frequent();
        gridTop = top + (common.isEmpty() ? 21 : 58);
        if (!common.isEmpty()) {
            g.text(font, tr("warps.frequent"), left + 4, top + 24, 0xFFBBBBBB);
            int w = (gridWidth - 12) / 3;
            for (int i = 0; i < common.size(); i++) {
                var warp = common.get(i);
                int x = left + 3 + i * w;
                g.fill(x, top + 36, x + w - 2, top + 54, warp.key().equals(selected) ? 0x70FFFFFF : 0x38FFFFFF);
                g.text(font, font.plainSubstrByWidth(warp.name + " · " + data.visits(warp).count, w - 6), x + 3, top
                    + 41, 0xFFFFFFFF);
            }
        }
        List<WarpData> rows = entries();
        int visibleRows = Math.max(1, (bottom - gridTop) / (tile + 2));
        int maxScroll = Math.max(0, (rows.size() + columns - 1) / columns - visibleRows);
        scroll = Math.min(scroll, maxScroll);
        g.enableScissor(left + 2, gridTop, sideX - 10, bottom - 2);
        previewVisible = false;
        double now = System.nanoTime() / 1e9;
        String nextHovered = null;
        for (int i = scroll * columns; i < rows.size(); i++) {
            int view = i - scroll * columns, x = left + 3 + view % columns * (tile + 2), y = gridTop + view / columns
                * (tile + 2);
            if (y >= bottom) {
                break;
            }
            var w = rows.get(i);
            boolean chosen = w.key().equals(selected), hover = mx >= x && mx < x + tile && my >= y && my < y + tile;

            var motion = motions.computeIfAbsent(w.key(), key -> new PreviewMotion());
            double inside = motion.updateHover(now, hover, data.reducedMotion());
            if (hover) {
                nextHovered = w.key();
            }
            g.fill(x, y, x + tile, y + tile, tileBackground(i, chosen || hover));
            if (chosen) {
                g.outline(x, y, tile, tile, 0xEEEEEEEE);
            }
            g.text(font, font.plainSubstrByWidth(w.name, tile - 6), x + 3, y + 3, !w.canTeleport() ? 0xFFFF8888
                : 0xFFFFFFFF);
            var snapshot = data.preview(w);
            int captionHeight = w.hasCustomName() ? 27 : 16;
            if (w.hasCustomName()) {
                g.text(font, font.plainSubstrByWidth(w.displayName, tile - 6), x + 3, y + 14, 0xFFC0C0C0);
            }
            if (snapshot != null) {
                previews.draw(g, snapshot, x + 2, y + captionHeight, tile - 4, Math.max(1, tile - captionHeight - 2),
                    motion.angle(), inside);
            } else {
                g.centeredText(font, tr("warps.no_preview"), x + tile / 2, y + tile / 2, 0xFFAAAAAA);
            }
        }
        if (hoveredPreview != null && !hoveredPreview.equals(nextHovered)) {
            motions.get(hoveredPreview).updateHover(now, false, data.reducedMotion());
        }
        hoveredPreview = nextHovered;
        g.disableScissor();
        if (rows.isEmpty()) {
            g.text(font, tr("warps.empty"), left + 6, gridTop + 12, 0xFFBBBBBB);
        }
        // Narrow bright scrollbar, matching the reference browser rather than card controls.
        int trackX = sideX - 9, trackH = Math.max(1, bottom - gridTop - 4);
        g.fill(trackX, gridTop, trackX + 5, bottom - 3, 0xFF444444);
        int thumbH = maxScroll == 0 ? trackH : Math.max(12, trackH * visibleRows / (visibleRows + maxScroll));
        int thumbY = gridTop + (maxScroll == 0 ? 0 : (trackH - thumbH) * scroll / maxScroll);
        g.fill(trackX, thumbY, trackX + 5, thumbY + thumbH, 0xFFDDDDDD);
        WarpData w = selection();
        editOwn.visible = data.canEditOwnWarp(w);
        if (w == null) {
            cameraControl.focus(false);
        }
        boolean retryAvailable = w != null && data.canRetryTeleport(w);
        teleport.active = w != null;
        teleport.setMessage(Component.translatable(retryAvailable ? "warps.retry_teleport" : "warps.teleport"));
        if (w != null) {
            g.enableScissor(sideX + 2, top + 2, sideX + sideWidth - 2, bottom - 2);
            int x = sideX + 4, y = top + 4;
            g.text(font, tr("warps.player_id"), x, y, 0xFFC0C0C0);
            y += 12;
            g.text(font, font.plainSubstrByWidth(w.name, sideWidth - 12), x + 4, y, 0xFFFFFFFF);
            y += 12;
            for (String info : metadataLines(w)) {
                for (var line : font.split(Component.literal(info), sideWidth - 8).stream().limit(3).toList()) {
                    if (y + 10 > bottom - 80) {
                        break;
                    }
                    g.text(font, line, x, y, 0xFFC0C0C0);
                    y += 10;
                }
            }
            if (!w.warningKey().isEmpty()) {
                g.text(font, font.plainSubstrByWidth(tr(w.warningKey()), sideWidth - 8), x, y, 0xFFFF8888);
                y += 12;
            } else if ("stale".equals(w.status)) {
                g.text(font, tr("warps.status.stale"), x, y, 0xFFC0C0C0);
                y += 12;
            }
            String submission = data.submissionLabel(w);
            if (!submission.isEmpty()) {
                g.text(font, font.plainSubstrByWidth(submission, sideWidth - 8), x, y, 0xFFC0C0C0);
                y += 12;
            }
            g.text(font, tr("warps.arrivals") + ": " + data.visits(w).count, x, y, 0xFFC0C0C0);
            y += 12;
            var s = data.preview(w);
            if (s != null) {
                g.text(font, s.width + " × " + s.height + " × " + s.depth, x, y, 0xFFC0C0C0);
                y += 12;
            }
            g.text(font, tr("warps.historical"), x, y, 0xFFAAAAAA);
            y += 12;
            g.text(font, tr("warps.toggle_view"), x, y, 0xFFAAAAAA);
            y += 14;
            if (s != null) {
                cameraControl.configure(s, sideWidth - 8, Math.max(20, bottom - y - 4));
                cameraControl.advance(now, minecraft.isWindowActive(), data.reducedMotion());
                previewVisible = true;
                previewTop = y;
                previews.draw(g, s, x, y, sideWidth - 8, Math.max(20, bottom - y - 4), cameraControl.yaw(), 1,
                    cameraControl.pitch(), cameraControl.zoom(), cameraControl.offsetX(), cameraControl.offsetY(),
                    cameraControl.offsetZ());
                if (cameraControl.focused()) {
                    g.outline(x, y, sideWidth - 8, Math.max(20, bottom - y - 4), 0xFFDDDDDD);
                }
            } else {
                cameraControl.focus(false);
                g.text(font, tr("warps.visit_for_preview"), x, y, 0xFFAAAAAA);
            }
            g.disableScissor();
        }
        previews.endFrame();
        super.extractRenderState(g, mx, my, delta);
        g.text(font, font.plainSubstrByWidth(data.notice(), Math.max(10, width - (editOwn.visible ? 364 : 252))), left
            + (editOwn.visible ? 260 : 148), height - 21, 0xFFBBBBBB);
    }

    private List<String> metadataLines(WarpData warp) {
        var lines = new ArrayList<String>();
        if (warp.hasCustomName()) {
            lines.add(tr("warps.custom_name") + ": " + warp.displayName);
        }
        if (warp.description != null && !warp.description.isBlank()) {
            lines.add(tr("warps.description") + ": " + warp.description);
        }
        if (!warp.tags.isEmpty()) {
            lines.add(tr("warps.tags") + ": " + String.join(" · ", warp.tags));
        }
        return lines;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
        if (previewContains(click.x(), click.y())) {
            if (click.button() == 1) {
                cameraControl.resetView(data.reducedMotion());
                return true;
            }
            if (click.button() == 0) {
                setFocused(null);
                cameraControl.focus(true);
                draggingPreview = true;
                setDragging(true);
                return true;
            }
        }
        cameraControl.focus(false);
        if (super.mouseClicked(click, doubleClick)) {
            return true;
        }
        if (click.button() != 0) {
            return false;
        }
        double x = click.x(), y = click.y();
        if (!data.frequent().isEmpty() && y >= top + 36 && y < top + 54 && x >= left + 3 && x < sideX - 9) {
            int i = (int) (x - left - 3) / Math.max(1, (gridWidth - 12) / 3);
            if (i < data.frequent().size()) {
                select(data.frequent().get(i));
                return true;
            }
        }
        if (x >= left + 3 && x < sideX - 10 && y >= gridTop && y < bottom - 2) {
            int col = (int) (x - left - 3) / (tile + 2), row = (int) (y - gridTop) / (tile + 2) + scroll, index = row
                * columns + col;
            var rows = entries();
            if (col < columns && index < rows.size()) {
                select(rows.get(index));
                return true;
            }
        }
        if (x >= sideX - 10 && x < sideX - 2 && y >= gridTop && y < bottom) {
            int max = Math.max(0, (entries().size() + columns - 1) / columns - Math.max(1, (bottom - gridTop) / (tile
                + 2)));
            scroll = (int) (max * (y - gridTop) / Math.max(1, bottom - gridTop));
            return true;
        }
        return false;
    }

    private void select(WarpData w) {
        long now = System.currentTimeMillis();
        if (!w.key().equals(selected)) {
            cameraControl.reset();
            draggingPreview = false;
        }
        selected = w.key();
        if (w.key().equals(clickedKey) && now - clickedAt <= 300) {
            clickedAt = 0;
            data.teleport(w);
        } else {
            clickedKey = w.key();
            clickedAt = now;
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (previewContains(x, y)) {
            cameraControl.scroll(vertical);
            return true;
        }
        if (x >= left && x < sideX && y >= gridTop && y < bottom) {
            scroll = Math.max(0, scroll - (int) Math.signum(vertical));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private boolean previewContains(double x, double y) {
        return previewVisible && x >= sideX + 4 && x < sideX + sideWidth - 4 && y >= previewTop && y < bottom - 4;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingPreview && event.button() == 0) {
            cameraControl.drag(dx, dy);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingPreview && (event.button() == 0 || event.button() == 1)) {
            draggingPreview = false;
            setDragging(false);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double x, double y) {
        if (!previewContains(x, y)) {
            cameraControl.focus(false);
            if (draggingPreview) {
                draggingPreview = false;
                setDragging(false);
            }
        }
        super.mouseMoved(x, y);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (cameraControl.focused()) {
            if (event.key() == InputConstants.KEY_ESCAPE) {
                cameraControl.focus(false);
                return true;
            }
            if (event.key() == InputConstants.KEY_R) {
                cameraControl.resetView(data.reducedMotion());
                return true;
            }
            var movement = movement(event.key());
            if (movement != null) {
                cameraControl.hold(movement, true);
                return true;
            }
        }
        if (event.key() == InputConstants.KEY_TAB) {
            cameraControl.focus(false);
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        var movement = movement(event.key());
        if (cameraControl.focused() && movement != null) {
            cameraControl.hold(movement, false);
            return true;
        }
        return super.keyReleased(event);
    }

    private static PreviewCameraControl.Movement movement(int key) {
        return switch (key) {
            case InputConstants.KEY_W -> PreviewCameraControl.Movement.FORWARD;
            case InputConstants.KEY_S -> PreviewCameraControl.Movement.BACKWARD;
            case InputConstants.KEY_A -> PreviewCameraControl.Movement.LEFT;
            case InputConstants.KEY_D -> PreviewCameraControl.Movement.RIGHT;
            case InputConstants.KEY_SPACE -> PreviewCameraControl.Movement.UP;
            case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> PreviewCameraControl.Movement.DOWN;
            default -> null;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        previews.clear();
        ClientPlatform.show(minecraft, null);
    }

    @Override
    public void removed() {
        cameraControl.reset();
        previews.clear();
        super.removed();
    }

    static final class BrowserButton extends Button {

        BrowserButton(int x, int y, int w, int h, Component text, OnPress press) {
            super(x, y, w, h, text, press, DEFAULT_NARRATION);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            g.fill(x, y, x + w, y + h, isHoveredOrFocused() ? 0xFF666666 : 0xFF444444);
            g.outline(x, y, w, h, 0xFFAAAAAA);
            g.centeredText(net.minecraft.client.Minecraft.getInstance().font, getMessage(), x + w / 2, y + (h - 8) / 2,
                active ? 0xFFFFFFFF : 0xFF888888);
        }
    }
}
