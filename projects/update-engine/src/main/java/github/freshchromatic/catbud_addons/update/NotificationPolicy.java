package github.freshchromatic.catbud_addons.update;

import java.util.ArrayList;
import java.util.List;

public final class NotificationPolicy {

    public record UpdateReminder(String module, String version, String id) {}

    public record Delivery(List<UpdateReminder> updates, List<Notice> notices, String installationId) {

        public Delivery {
            updates = List.copyOf(updates);
            notices = List.copyOf(notices);
        }
    }

    private NotificationPolicy() {
    }

    public static Delivery select(UpdateCoordinator.Snapshot snapshot, List<Notice> applicable, NotificationStateStore state, long now) {
        var updates = new ArrayList<UpdateReminder>();
        if (snapshot.plan() != null && snapshot.check() == UpdateCoordinator.CheckState.AVAILABLE) {
            for (var item : snapshot.plan().replacements()) {
                var artifact = item.artifact();
                String id = artifact.reminderId();
                if (!state.skipped(artifact.module(), artifact.releaseRevision().key()) && state.shouldRemind(id,
                    now)) {
                    updates.add(new UpdateReminder(artifact.module(), artifact.releaseRevision().displayVersion(), id));
                }
            }
        }
        var notices = applicable.stream().filter(state::unread).filter(notice -> state.shouldRemind(notice.reminderId(),
            now)).toList();
        String installation = snapshot.install() == UpdateCoordinator.InstallState.READY && snapshot.plan() != null
            ? "installation:" + snapshot.plan().releaseId()
            : "";
        if (!installation.isEmpty() && !state.shouldRemind(installation, now)) {
            installation = "";
        }
        return new Delivery(updates, notices, installation);
    }

    public static void delivered(Delivery delivery, NotificationStateStore state, long now) {
        var ids = new ArrayList<String>();
        delivery.updates().forEach(update -> ids.add(update.id()));
        delivery.notices().forEach(notice -> ids.add(notice.reminderId()));
        if (!delivery.installationId().isEmpty()) {
            ids.add(delivery.installationId());
        }
        if (!ids.isEmpty()) {
            state.remindedAll(ids, now);
        }
    }
}
