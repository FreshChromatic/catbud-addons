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
    void skippingOrSnoozingAnOlderRevisionDoesNotSuppressTheNextRevision() {
        var state = new NotificationStateStore(directory.resolve("notifications.json"));
        state.skip("core", "1.0.4");
        state.skip("core", "1.0.4-r2");
        state.snooze("update:core:1.0.4-r2", 100);
        var second = revisionSnapshot(2);
        assertTrue(NotificationPolicy.select(second, List.of(), state, 101).updates().isEmpty());
        var third = revisionSnapshot(3);
        var delivery = NotificationPolicy.select(third, List.of(), state, 101);
        assertEquals(1, delivery.updates().size());
        assertEquals("1.0.4 (r3)", delivery.updates().getFirst().version());
        NotificationPolicy.delivered(delivery, state, 101);
        var reloaded = new NotificationStateStore(directory.resolve("notifications.json"));
        assertTrue(NotificationPolicy.select(third, List.of(), reloaded, 102).updates().isEmpty());
        assertEquals(1, NotificationPolicy.select(revisionSnapshot(4), List.of(), reloaded, 102).updates().size());
    }

    private UpdateCoordinator.Snapshot revisionSnapshot(int number) {
        var revision = new ReleaseRevision("1.0.4", number);
        var artifact = new ReleaseManifest.Artifact("core:26.2", "core", "catbud_core", "26.2", ">=0.19.5", 25, "1.0.4",
            revision.file("core", "26.2"), "", 2, "a".repeat(64), List.of(), Map.of());
        var plan = new UpdatePlan(revision.tag(), "26.2", directory, List.of(new UpdatePlan.Replacement(directory
            .resolve("old.jar"), "b".repeat(64), artifact)), Map.of());
        return new UpdateCoordinator.Snapshot(UpdateCoordinator.CheckState.AVAILABLE,
            UpdateCoordinator.InstallState.IDLE, "", plan, List.of(), 0, 0, 0);
    }

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
