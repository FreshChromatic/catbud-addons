package github.freshchromatic.catbud_addons.magic_tower;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import github.freshchromatic.catbud_addons.magic_tower.mixin.BossOverlayAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

public final class TowerSession {

    private static final String QUEUE_KEY = "plugins.venue_manager.room_queuing_distance";
    private static final String TOWER_KEY = "plugins.magic_tower.magic_tower";
    private static final String READY_KEY = "plugins.venue_manager.venue_waiting_activate";
    private static final String FLOOR_KEY = "plugins.magic_tower.waiting_layer_response";

    private static final Identifier CUSTOM_DIMENSION      = Identifier.fromNamespaceAndPath("minecraft", "custom");
    private static final long       VANISH_GRACE_MS       = 3_000;
    private static final long       READY_VANISH_GRACE_MS = 15_000;

    private enum Phase {
        IDLE, QUEUED, READY, ACTIVE
    }

    private enum ManualMode {
        AUTO, FORCED, OFF
    }

    private record ConnectionIdentity(String server, UUID player) {}

    public enum EvacuationStatus {
        UNKNOWN, PRESENT, ABSENT
    }

    private Phase      phase      = Phase.IDLE;
    private ManualMode manualMode = ManualMode.AUTO;

    private ConnectionIdentity connectionIdentity;
    private boolean            suspended;

    private UUID    bossId;
    private long    vanishedAt = -1;
    private boolean teleportPacketSeen;

    private Vec3       lastPosition;
    private Object     lastLevel;
    private Identifier lastDimension;

    private long    lastFloorSignal;
    private boolean floorBossbarVisible;

    private final TowerFloorState floorState = new TowerFloorState();

    private long floorEpoch;

    private final WorldTargets targets = new WorldTargets();

    public boolean isActive() {
        return !suspended && MagicTowerSettings.enabled() && (manualMode == ManualMode.FORCED
            || (manualMode == ManualMode.AUTO && phase == Phase.ACTIVE));
    }

    public String modeKey() {
        return manualMode.name().toLowerCase(java.util.Locale.ROOT);
    }

    public String phaseKey() {
        return manualMode == ManualMode.FORCED ? "active" : phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    public WorldTargets targets() {
        return targets;
    }

    public EvacuationStatus evacuationStatus() {
        return floorState.evacuation();
    }

    public java.util.OptionalInt floor() {
        return floorState.floor();
    }

    public long floorEpoch() {
        return floorEpoch;
    }

    public boolean forceStart(Minecraft mc) {
        if (!MagicTowerSettings.enabled() || mc.level == null || mc.player == null) {
            return false;
        }
        resetSession();
        manualMode = ManualMode.FORCED;
        lastLevel = mc.level;
        lastDimension = ClientPlatform.dimension(mc);
        lastPosition = mc.player.position();
        CatbudAddonsClient.LOGGER.debug("強制開始掃描");
        return true;
    }

    public void forceStop() {
        resetSession();
        manualMode = ManualMode.OFF;
        CatbudAddonsClient.LOGGER.debug("手動關閉掃描");
    }

    public void useAuto() {
        resetSession();
        manualMode = ManualMode.AUTO;
        CatbudAddonsClient.LOGGER.debug("恢復自動判定");
    }

    public void resetConnection() {
        resetSession();
        manualMode = ManualMode.AUTO;
        connectionIdentity = null;
        clearWorldReferences();
    }

    public void disconnect() {
        if (suspended) {
            return;
        }
        if (isActive() && CUSTOM_DIMENSION.equals(lastDimension) && connectionIdentity != null) {
            suspended = true;
            bossId = null;
            vanishedAt = -1;
            teleportPacketSeen = false;
            targets.releaseLoadedChunks();
            clearWorldReferences();
        } else {
            resetConnection();
        }
    }

    public void joinConnection() {
        if (!suspended) {
            resetConnection();
        }
    }

    void updateConnection(String server, UUID player, Identifier dimension) {
        ConnectionIdentity current = server == null ? null : new ConnectionIdentity(server, player);
        if (suspended) {
            if (connectionIdentity.equals(current) && CUSTOM_DIMENSION.equals(dimension)) {
                suspended = false;
                CatbudAddonsClient.LOGGER.debug("重新登入 minecraft:custom，恢復魔幻之塔狀態");
            } else {
                resetConnection();
            }
        }
        connectionIdentity = current;
    }

    private void clearWorldReferences() {
        lastLevel = null;
        lastDimension = null;
        lastPosition = null;
    }

    public String status() {
        return switch (manualMode) {
            case FORCED -> "強制掃描中";
            case OFF -> "已手動關閉";
            case AUTO -> switch (phase) {
                case IDLE -> "自動模式：待機";
                case QUEUED -> "自動模式：排隊中";
                case READY -> "自動模式：等待傳送";
                case ACTIVE -> "自動模式：掃描中";
            };
        };
    }

    public void onTeleportPacket() {
        if (manualMode != ManualMode.AUTO) {
            return;
        }
        if (phase == Phase.QUEUED || phase == Phase.READY) {
            teleportPacketSeen = true;
        }
    }

    public void onBossbarName(UUID id, Component name) {
        if (suspended || !MagicTowerSettings.enabled()) {
            return;
        }
        if (manualMode != ManualMode.AUTO) {
            if (isActive() && TextKeys.contains(name, FLOOR_KEY)) {
                onMessage(name, "Bossbar");
            }
            return;
        }
        if (phase == Phase.IDLE && TextKeys.contains(name, QUEUE_KEY) && TextKeys.contains(name, TOWER_KEY)) {
            bossId = id;
            phase = Phase.QUEUED;
            vanishedAt = -1;
            teleportPacketSeen = false;
            CatbudAddonsClient.LOGGER.debug("偵測魔幻之塔排隊 Bossbar");
        } else if ((phase == Phase.QUEUED || (phase == Phase.READY && id.equals(bossId))) && isReadyBar(name)) {
            boolean newlyReady = phase != Phase.READY;
            boolean replacement = !id.equals(bossId);
            bossId = id;
            phase = Phase.READY;
            vanishedAt = -1;
            if (newlyReady || replacement) {
                CatbudAddonsClient.LOGGER.debug("場地已準備，等待傳送" + (replacement ? "（新 Bossbar）" : ""));
            }
        }
        if (phase == Phase.ACTIVE && TextKeys.contains(name, FLOOR_KEY)) {
            floorBossbarVisible = true;
            onMessage(name, "Bossbar");
        }
    }

    private static boolean isReadyBar(Component name) {
        if (TextKeys.contains(name, READY_KEY)) {
            return true;
        }
        // Some servers send the already-rendered text rather than a translatable component.
        String rendered = name.getString();
        String localized = Language.getInstance().getOrDefault(READY_KEY);
        return rendered.contains(READY_KEY) || (!localized.equals(READY_KEY) && rendered.contains(localized));
    }

    public void onMessage(Component text, String channel) {
        if (suspended || !MagicTowerSettings.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (TextKeys.contains(text, FLOOR_KEY) && isActive() && now - lastFloorSignal >= 1_000) {
            lastFloorSignal = now;
            if (floorState.waiting(now)) {
                targets.clear();
                floorEpoch++;
                CatbudAddonsClient.LOGGER.debug("樓層訊號（" + channel + "）：清除標記並重新掃描");
            }
        }
        if (!"Title".equals(channel) && !"Subtitle".equals(channel)) {
            return;
        }
        if (phase == Phase.QUEUED || phase == Phase.READY || isActive()) {
            var announcement = TowerAnnouncementParser.parse(text);
            boolean changedFloor = announcement.floor().isPresent() && floorState.floor().isPresent() && !announcement
                .floor().equals(floorState.floor());
            floorState.announce(announcement, now);
            if (changedFloor && isActive()) {
                targets.clear();
                floorEpoch++;
                lastFloorSignal = now;
            }
        }
    }

    public void tick(Minecraft mc) {
        if (!MagicTowerSettings.enabled()) {
            return;
        }
        if (mc.level == null || mc.player == null) {
            // Respawn/dimension travel can temporarily remove the local level before the next
            // packet.
            if (mc.getConnection() == null) {
                disconnect();
            }
            return;
        }

        Identifier dimension = ClientPlatform.dimension(mc);
        var server = mc.getCurrentServer();
        updateConnection(server == null ? null : server.ip, mc.player.getUUID(), dimension);
        Vec3 position = mc.player.position();
        boolean changedWorld = lastLevel != null && lastLevel != mc.level;
        boolean changedDimension = lastDimension != null && !dimension.equals(lastDimension);
        boolean enteredCustom = CUSTOM_DIMENSION.equals(dimension) && lastDimension != null && !CUSTOM_DIMENSION.equals(
            lastDimension);
        boolean positionJump = lastPosition != null && position.distanceToSqr(lastPosition) > 64 * 64;

        Map<UUID, LerpingBossEvent> bars = ((BossOverlayAccessor) ClientPlatform.bossOverlay(mc)).catbud$getEvents();
        boolean floorBarNow = isActive() && bars.values().stream().anyMatch(bar -> TextKeys.contains(bar.getName(),
            FLOOR_KEY));
        if (floorBarNow && !floorBossbarVisible) {
            onMessage(Component.translatable(FLOOR_KEY), "Bossbar");
        }
        floorBossbarVisible = floorBarNow;
        if (manualMode != ManualMode.AUTO) {
            if (manualMode == ManualMode.FORCED) {
                if (changedWorld || changedDimension) {
                    targets.clear();
                    floorState.reset();

                    floorEpoch++;
                    CatbudAddonsClient.LOGGER.debug("世界變更，重新掃描");
                }
                targets.tick(mc.level, mc.player.blockPosition(), mc.options.getEffectiveRenderDistance());
            }
            lastLevel = mc.level;
            lastDimension = dimension;
            lastPosition = position;
            return;
        }
        if (phase == Phase.IDLE) {
            for (Map.Entry<UUID, LerpingBossEvent> entry : bars.entrySet()) {
                onBossbarName(entry.getKey(), entry.getValue().getName());
                if (phase != Phase.IDLE) {
                    break;
                }
            }
        }

        if (phase == Phase.QUEUED || phase == Phase.READY) {
            if (phase == Phase.QUEUED) {
                for (Map.Entry<UUID, LerpingBossEvent> entry : bars.entrySet()) {
                    if (isReadyBar(entry.getValue().getName())) {
                        onBossbarName(entry.getKey(), entry.getValue().getName());
                        break;
                    }
                }
            } else if (!bars.containsKey(bossId)) {
                for (Map.Entry<UUID, LerpingBossEvent> entry : bars.entrySet()) {
                    if (isReadyBar(entry.getValue().getName())) {
                        bossId = entry.getKey();
                        vanishedAt = -1;
                        CatbudAddonsClient.LOGGER.debug("場地準備 Bossbar 已更新，繼續等待傳送");
                        break;
                    }
                }
            }
            LerpingBossEvent trackedBar = bars.get(bossId);
            if (trackedBar != null) {
                vanishedAt = -1;
            } else if (vanishedAt < 0) {
                vanishedAt = System.currentTimeMillis();
                CatbudAddonsClient.LOGGER.debug(phase == Phase.READY ? "準備 Bossbar 消失，繼續等待傳送確認"
                    : "排隊 Bossbar 消失，等待準備訊息或傳送確認");
            }

            boolean teleportConfirmed = CUSTOM_DIMENSION.equals(dimension) && (enteredCustom || changedWorld
                || positionJump || teleportPacketSeen);
            if (teleportConfirmed && (phase == Phase.READY || trackedBar == null)) {
                phase = Phase.ACTIVE;
                vanishedAt = -1;
                teleportPacketSeen = false;
                targets.clear();
                floorState.entered(System.currentTimeMillis());
                floorEpoch++;
                lastFloorSignal = 0;
                floorBossbarVisible = false;
                CatbudAddonsClient.LOGGER.debug("已傳送至 minecraft:custom，任務開始");
            } else if (vanishedAt >= 0 && System.currentTimeMillis() - vanishedAt >= (phase == Phase.READY
                ? READY_VANISH_GRACE_MS
                : VANISH_GRACE_MS)) {
                cancel("Bossbar 消失後未確認傳送");
            }
        } else if (phase == Phase.ACTIVE) {
            if (!CUSTOM_DIMENSION.equals(dimension)) {
                cancel("離開 minecraft:custom");
            } else {
                targets.tick(mc.level, mc.player.blockPosition(), mc.options.getEffectiveRenderDistance());
            }
        }

        lastLevel = mc.level;
        lastDimension = dimension;
        lastPosition = position;
    }

    public void cancel(String reason) {
        if (phase != Phase.IDLE) {
            CatbudAddonsClient.LOGGER.debug("任務取消：" + reason);
        }
        resetSession();
    }

    private void resetSession() {
        suspended = false;
        phase = Phase.IDLE;
        bossId = null;
        vanishedAt = -1;
        teleportPacketSeen = false;
        floorBossbarVisible = false;
        lastFloorSignal = 0;
        targets.clear();
        floorState.reset();
        floorEpoch++;
    }
}
