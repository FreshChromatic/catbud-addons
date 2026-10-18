package github.freshchromatic.catbud_addons.monster_wave;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RunEvent(int schemaVersion, UUID runId, long sequence, Instant receivedAt, long sinceTriggerMs,
    Long sinceActiveMs, String type, Payload data) {

    public sealed interface Payload permits Started, State, World, Teleport, Bossbar, Text, TitleTiming, TitleClear,
        Health, HealthRisk, Resumed, Ended {}

    public record TextValue(String text, String componentJson, List<String> translationKeys, String encodingError) {

        public TextValue {
            translationKeys = List.copyOf(translationKeys);
        }
    }

    public record Started(String modVersion, String minecraftVersion, String mode) implements Payload {}

    public record State(String phase, String mode) implements Payload {}

    public record World(String dimension, boolean changedWorld, boolean changedDimension, boolean positionJump)
        implements Payload {}

    public record Teleport(String source) implements Payload {}

    public record Bossbar(String operation, UUID id, BossValue value, String signal) implements Payload {}

    public record BossValue(TextValue name, float progress, String color, String overlay, boolean darkenScreen,
        boolean playMusic, boolean createWorldFog) {}

    public enum Channel {
        TITLE, SUBTITLE, ACTIONBAR, SYSTEM_CHAT, PLAYER_CHAT
    }

    public record Text(Channel channel, TextValue value, boolean overlay) implements Payload {}

    public record TitleTiming(int fadeIn, int stay, int fadeOut) implements Payload {}

    public record TitleClear(boolean resetTimes) implements Payload {}

    public record Health(float health) implements Payload {}

    public record HealthRisk(float health, float largestDrop, float recentDrop, float predictedLoss, float reserve,
        String trigger, int nearbyHostiles, int nearbyRadius) implements Payload {}

    public record Resumed(UUID previousRunId, long disconnectedDurationMs) implements Payload {}

    public enum EndReason {
        LEFT_TARGET_WORLD, LOW_HEALTH, BURST_RISK, DISCONNECTED, QUEUE_CANCELLED, MANUAL_STOP, MODE_CHANGED, DISABLED,
        CLIENT_STOPPED
    }

    public record Ended(EndReason reason, Instant triggeredAt, Instant readyAt, Instant activeAt, Instant endedAt,
        long totalDurationMs, Long activeDurationMs, Float minimumHealth, boolean started) implements Payload {}
}
