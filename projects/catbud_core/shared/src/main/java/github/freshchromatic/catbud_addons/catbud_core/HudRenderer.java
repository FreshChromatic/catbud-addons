package github.freshchromatic.catbud_addons.catbud_core;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.List;

public final class HudRenderer {

    private static final java.util.Map<HudTextFormat.Weight, FontDescription> SANS = java.util.Map.of(
        HudTextFormat.Weight.REGULAR, font("sans"), HudTextFormat.Weight.MEDIUM, font("sans_medium"),
        HudTextFormat.Weight.SEMIBOLD, font("sans_semibold"), HudTextFormat.Weight.HEAVY, font("sans_bold"));

    private static FontDescription font(String name) {
        return new FontDescription.Resource(Identifier.fromNamespaceAndPath("catbud_core", name));
    }

    public record Layout(HudContent content, List<Component> lines, double width, double height, double textWidth,
        double textHeight, double iconSize, double contentScale) {}

    private HudRenderer() {
    }

    public static Layout measure(HudContent content, HudStyle style) {
        Style textStyle = Style.EMPTY.withUnderlined(style.formatting().underline()).withBold(style.bold() || style
            .font() == HudStyle.FontFace.MINECRAFT && style.formatting().weight()
                .ordinal() >= HudTextFormat.Weight.SEMIBOLD.ordinal()).withItalic(style.italic()).withFont(style
                    .font() == HudStyle.FontFace.SANS ? SANS.get(style.formatting().weight())
                        : FontDescription.DEFAULT);
        List<Component> lines = content.lines().stream().map(line -> (Component) Component.literal(style.formatting()
            .apply(line.getString())).withStyle(textStyle)).toList();
        var font = Minecraft.getInstance().font;
        double textWidth = lines.stream().mapToInt(font::width).max().orElse(0) * style.textScale();
        double textHeight = lines.isEmpty() ? 0
            : lines.size() * font.lineHeight * style.textScale() + (lines.size() - 1) * style.lineGap();
        double icon = content.icon() == null ? 0 : style.iconSize();
        double gap = icon > 0 && !lines.isEmpty() ? style.iconGap() : 0;
        double width = Math.max(2, style.padding().left() + icon + gap + textWidth + style.padding().right());
        double height = Math.max(2, style.padding().top() + Math.max(icon, textHeight) + style.padding().bottom());
        width = style.fixedWidth() > 0 ? Math.max(2, style.fixedWidth()) : width;
        height = style.fixedHeight() > 0 ? Math.max(2, style.fixedHeight()) : height;
        double innerWidth = Math.max(0, width - style.padding().left() - style.padding().right());
        double innerHeight = Math.max(0, height - style.padding().top() - style.padding().bottom());
        double contentWidth = icon + gap + textWidth;
        double contentHeight = Math.max(icon, textHeight);
        double fit = Math.min(1, Math.min(innerWidth / Math.max(1, contentWidth), innerHeight / Math.max(1,
            contentHeight)));
        double availableText = Math.max(0, innerWidth - (icon + gap) * fit);
        return new Layout(content, lines, width, height, availableText, textHeight * fit, icon * fit, fit);
    }

    public static void render(HudGraphics graphics, HudElementConfig config, Layout layout, HudLayoutEngine.Bounds bounds) {
        render(graphics, config, layout, bounds, true);
    }

    private static void render(HudGraphics graphics, HudElementConfig config, Layout layout, HudLayoutEngine.Bounds bounds, boolean background) {
        HudStyle style = config.style().chroma().resolve(config.style(), System.currentTimeMillis());
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate((float) bounds.x(), (float) bounds.y());
        pose.scale((float) (bounds.width() / layout.width()));
        if (background) {
            HudShapes.background(graphics, layout.width(), layout.height(), style);
        }
        double innerHeight = layout.height() - style.padding().top() - style.padding().bottom();
        double textX = Math.min(layout.width(), style.padding().left());
        if (layout.iconSize() > 0) {
            graphics.texture(layout.content().icon(), (int) textX, (int) (style.padding().top() + (innerHeight - layout
                .iconSize()) / 2), (int) layout.iconSize(), (int) layout.iconSize());
            textX += layout.iconSize() + (layout.lines().isEmpty() ? 0 : style.iconGap() * layout.contentScale());
        }
        double textY = style.padding().top() + (innerHeight - layout.textHeight()) / 2;
        var font = Minecraft.getInstance().font;
        for (Component line : layout.lines()) {
            double spare = layout.textWidth() - font.width(line) * style.textScale() * layout.contentScale();
            double aligned = switch (style.alignment()) {
                case LEFT -> 0;
                case CENTER -> spare / 2;
                case RIGHT -> spare;
            };
            pose.pushMatrix();
            pose.translate((float) (textX + aligned), (float) textY);
            pose.scale((float) (style.textScale() * layout.contentScale()));
            if (style.textShadow().enabled()) {
                graphics.text(font, line, (int) style.textShadow().x(), (int) style.textShadow().y(), style.textShadow()
                    .color());
            }
            graphics.text(font, line, 0, 0, style.textColor());
            pose.popMatrix();
            textY += (font.lineHeight * style.textScale() + style.lineGap()) * layout.contentScale();
        }
        pose.popMatrix();
    }

    public static void renderFrame(HudGraphics graphics, HudFrame frame) {
        long time = System.currentTimeMillis();
        for (var background : frame.backgrounds()) {
            var bounds = background.bounds();
            var style = background.style().chroma().resolve(background.style(), time);
            var pose = graphics.pose();
            pose.pushMatrix();
            pose.translate((float) bounds.x(), (float) bounds.y());
            pose.scale((float) background.scale());
            HudShapes.background(graphics, bounds.width() / background.scale(), bounds.height() / background.scale(),
                style);
            pose.popMatrix();
        }
        for (var entry : frame.entries()) {
            render(graphics, entry.config(), entry.layout(), entry.bounds(), false);
        }
    }

    public static void renderGame(HudGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || ClientPlatform.hudHidden(client) || ClientPlatform.screen(
            client) instanceof HudEditorScreen) {
            return;
        }
        var configs = CatbudCore.hudStore().current();
        var frame = HudFrame.measure(CatbudCore.huds(), configs, element -> configs.getOrDefault(element.id(), element
            .defaults()).enabled() && element.visible(), false, client.getWindow().getGuiScaledWidth(), client
                .getWindow().getGuiScaledHeight(), guiScale());
        renderFrame(graphics, frame);
    }

    public static double guiScale() {
        var window = Minecraft.getInstance().getWindow();
        return (double) window.getWidth() / Math.max(1, window.getGuiScaledWidth());
    }
}
