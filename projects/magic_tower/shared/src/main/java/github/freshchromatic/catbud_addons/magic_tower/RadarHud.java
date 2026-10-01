package github.freshchromatic.catbud_addons.magic_tower;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import github.freshchromatic.catbud_addons.catbud_core.HudGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

final class RadarHud {
    private static final Identifier ARROW = Identifier.fromNamespaceAndPath("catbud_addons", "textures/gui/radar_arrow.png");
    private static final Identifier ABOVE = Identifier.fromNamespaceAndPath("catbud_addons", "textures/gui/radar_above.png");
    private static final Identifier BELOW = Identifier.fromNamespaceAndPath("catbud_addons", "textures/gui/radar_below.png");
    private static final Identifier COIN = Identifier.fromNamespaceAndPath("catbud_addons", "textures/gui/coin_of_tower.png");
    private static final Identifier BOOK = Identifier.fromNamespaceAndPath("minecraft", "textures/item/enchanted_book.png");

    private record Marker(Vec3 center, WorldTargets.PortalType portalType, WorldTargets.SpecialType specialType) {}

    private RadarHud() {}

    static void render(HudGraphics graphics, Minecraft client, TowerSession session) {
        if (!session.isActive() || client.level == null || client.player == null) return;
        var settings = MagicTowerSettings.values();
        if (!settings.bool("radar.enabled")) return;
        Camera camera = ClientPlatform.camera(client);
        Vec3 cameraPos = ClientPlatform.position(camera);
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        int margin = Math.min(settings.integer("radar.margin"), Math.min(width, height) / 2 - 1);
        int tint = (int) Math.round(settings.number("radar.opacity") * 255) << 24 | 0xFFFFFF;
        List<Marker> allMarkers = new ArrayList<>();
        for (WorldTargets.PortalMarker portal : session.targets().portalMarkers())
            allMarkers.add(new Marker(portal.center(), portal.type(), null));
        for (WorldTargets.SpecialMarker special : session.targets().specialMarkers())
            allMarkers.add(new Marker(special.center(), null, special.type()));
        List<Marker> markers = allMarkers.stream()
            .filter(marker -> settings.bool("radar." + markerType(marker)))
            .filter(marker -> settings.number("radar.distance") == 0
                || marker.center().distanceToSqr(cameraPos) <= Math.pow(settings.number("radar.distance"), 2))
            .sorted(Comparator.comparingDouble(marker -> marker.center().distanceToSqr(cameraPos)))
            .limit(64).toList();
        for (Marker marker : markers) {
            Vec3 target = marker.center();
            Vec3 delta = target.subtract(cameraPos);
            double distance = delta.length();
            if (distance < settings.number("radar.hideNear")) continue;
            double yaw = Math.toRadians(ClientPlatform.yRot(camera));
            double pitch = Math.toRadians(ClientPlatform.xRot(camera));
            double right = cameraRight(delta, yaw);
            double forward = -delta.x * Math.sin(yaw) + delta.z * Math.cos(yaw);
            double viewForward = forward * Math.cos(pitch) - delta.y * Math.sin(pitch);
            double viewUp = forward * Math.sin(pitch) + delta.y * Math.cos(pitch);
            float fov = (float) Math.toRadians(client.options.fov().get());
            double focal = (height / 2.0) / Math.tan(fov / 2.0);
            double projectedX = width / 2.0 + right / Math.max(0.01, viewForward) * focal;
            double projectedY = height / 2.0 - viewUp / Math.max(0.01, viewForward) * focal;
            boolean onScreen = viewForward > 0 && projectedX >= margin && projectedX <= width - margin
                && projectedY >= margin && projectedY <= height - margin;
            int x, y;
            double angle;
            if (onScreen) {
                Vec3 exact = ClientPlatform.project(client, target);
                x = (int) Math.round((exact.x + 1) * width / 2);
                y = (int) Math.round((1 - exact.y) * height / 2);
                angle = Math.atan2(y - height / 2.0, x - width / 2.0);
            } else {
                double dx = viewForward > 0 ? projectedX - width / 2.0 : right;
                double dy = viewForward > 0 ? projectedY - height / 2.0 : -viewUp;
                if (Math.abs(dx) + Math.abs(dy) < 0.001) dy = 1;
                double factor = Math.min((width / 2.0 - margin) / Math.max(0.001, Math.abs(dx)),
                    (height / 2.0 - margin) / Math.max(0.001, Math.abs(dy)));
                x = (int) Math.round(width / 2.0 + dx * factor);
                y = (int) Math.round(height / 2.0 + dy * factor);
                angle = Math.atan2(dy, dx);
            }
            x = Math.clamp(x, margin, width - margin);
            y = Math.clamp(y, margin, height - margin);
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y);
            graphics.pose().scale((float) settings.number("radar.iconScale"));
            if (marker.specialType() != null) {
                graphics.blit(RenderPipelines.GUI_TEXTURED,
                    marker.specialType() == WorldTargets.SpecialType.COIN_BANK ? COIN : BOOK,
                    -8, -8, 0, 0, 16, 16, 16, 16, tint);
            } else {
                int color = settings.color("radar.color." + markerType(marker), "radar.opacity");
                graphics.centeredText(client.font,
                    marker.portalType() == WorldTargets.PortalType.NETHER ? "N" : "E", 0, -4, color);
            }
            if (!onScreen && settings.bool("radar.arrows")) {
                graphics.pose().pushMatrix();
                graphics.pose().rotate((float) angle + (float) (Math.PI * 0.75));
                graphics.blit(RenderPipelines.GUI_TEXTURED, ARROW, -5, -19, 0, 0, 10, 10, 10, 10, tint);
                graphics.pose().popMatrix();
            }
            if (settings.bool("radar.vertical") && Math.abs(delta.y) >= settings.number("radar.verticalThreshold")) {
                Identifier vertical = delta.y > 0 ? ABOVE : BELOW;
                graphics.blit(RenderPipelines.GUI_TEXTURED, vertical, 5, -13, 0, 0, 10, 10, 10, 10, tint);
            }
            graphics.pose().popMatrix();
            if (settings.bool("radar.distanceText")) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(x, y + (float) (10 * settings.number("radar.iconScale")));
                graphics.pose().scale((float) settings.number("radar.textScale"));
                graphics.centeredText(client.font, Math.round(distance) + "m", 0, 0, tint);
                graphics.pose().popMatrix();
            }
        }
    }

    private static String markerType(Marker marker) {
        if (marker.specialType() != null) return marker.specialType() == WorldTargets.SpecialType.COIN_BANK ? "coin" : "enchantment";
        return marker.portalType() == WorldTargets.PortalType.NETHER ? "nether" : "end";
    }

    static double cameraRight(Vec3 delta, double yawRadians) {
        return -delta.x * Math.cos(yawRadians) - delta.z * Math.sin(yawRadians);
    }
}
