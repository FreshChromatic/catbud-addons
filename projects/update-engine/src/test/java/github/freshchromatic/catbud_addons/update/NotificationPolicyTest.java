package github.freshchromatic.catbud_addons.update;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NotificationPolicyTest {

    @TempDir Path directory;

    @Test
    void remindersAreNotConsumedUntilActuallyDeliveredAndStayDeduplicatedAcrossLaunches() {
        var state = new NotificationStateStore(directory.resolve("notifications.json"));
        var artifact = new ReleaseManifest.Artifact("core:26.2", "core", "catbud_core", "26.2", ">=0.19.5", 25, "1.0.4",
            "core.jar", "", 2, "a".repeat(64), List.of(), Map.of());
        var plan = new UpdatePlan("v1.0.4", "26.2", directory, List.of(new UpdatePlan.Replacement(directory.resolve(
            "old.jar"), "b".repeat(64), artifact)), Map.of());
        var notice = new Notice(UUID.randomUUID().toString(), 1, "Update", "New release", "info", List.of(), List.of(),
            List.of(), 0, 100000, "", false);
        var snapshot = new UpdateCoordinator.Snapshot(UpdateCoordinator.CheckState.AVAILABLE,
            UpdateCoordinator.InstallState.READY, "", plan, List.of(notice), 0, 2, 2);
        var first = NotificationPolicy.select(snapshot, List.of(notice), state, 100);
        assertEquals(1, first.updates().size());
        assertEquals(1, first.notices().size());
        assertFalse(first.installationId().isEmpty());
        assertEquals(first, NotificationPolicy.select(snapshot, List.of(notice), state, 100));
        NotificationPolicy.delivered(first, state, 100);
        var reloaded = new NotificationStateStore(directory.resolve("notifications.json"));
        var next = NotificationPolicy.select(snapshot, List.of(notice), reloaded, 200);
        assertTrue(next.updates().isEmpty());
        assertTrue(next.notices().isEmpty());
        assertTrue(next.installationId().isEmpty());
        assertTrue(reloaded.unread(notice));
    }
}
