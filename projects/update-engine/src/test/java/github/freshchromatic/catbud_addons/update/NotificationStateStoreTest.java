package github.freshchromatic.catbud_addons.update;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NotificationStateStoreTest {

    @TempDir Path directory;

    @Test
    void persistsReadingAndOnlyRenotifiesImportantRevisions() {
        Path file = directory.resolve("notifications.json");
        var store = new NotificationStateStore(file);
        String id = UUID.randomUUID().toString();
        var initial = notice(id, 1, false);
        assertTrue(store.unread(initial));
        store.read(initial);
        store.reminded(initial.reminderId(), 100);
        var reloaded = new NotificationStateStore(file);
        assertFalse(reloaded.unread(initial));
        assertFalse(reloaded.shouldRemind(initial.reminderId(), 200));
        assertFalse(reloaded.unread(notice(id, 2, false)));
        assertTrue(reloaded.unread(notice(id, 3, true)));
    }

    @Test
    void separatesServerAudienceAndHonorsSnoozeAndSkippedVersions() {
        var store = new NotificationStateStore(directory.resolve("notifications.json"));
        var notice = notice(UUID.randomUUID().toString(), 1, false);
        assertTrue(notice.applies(Set.of("public_warps"), "26.2", "catrealm", 100));
        assertFalse(notice.applies(Set.of("public_warps"), "26.2", "catbud", 100));
        assertFalse(notice.applies(Set.of("magic_tower"), "26.2", "catrealm", 100));
        store.snooze("update:core:1.0.4", 100);
        assertFalse(store.shouldRemind("update:core:1.0.4", 200));
        assertTrue(store.shouldRemind("update:core:1.0.4", 86400101));
        store.skip("core", "1.0.4");
        assertTrue(store.skipped("core", "1.0.4"));
        assertFalse(store.skipped("core", "1.0.5"));
    }

    private static Notice notice(String id, int revision, boolean notify) {
        return new Notice(id, revision, "Maintenance", "Service maintenance", "info", List.of("public_warps"), List.of(
            "26.2"), List.of("catrealm"), 0, 100000000, "", notify);
    }
}
