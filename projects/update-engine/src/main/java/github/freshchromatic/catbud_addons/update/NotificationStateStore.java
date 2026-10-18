package github.freshchromatic.catbud_addons.update;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public final class NotificationStateStore {

    private static final class State {

        boolean              automatic     = true;
        boolean              toasts        = true;
        Map<String, Integer> read          = new HashMap<>();
        Map<String, Long>    reminded      = new HashMap<>();
        Map<String, Long>    snoozed       = new HashMap<>();
        Map<String, String>  skipped       = new HashMap<>();
        Map<String, Long>    noticeExpires = new HashMap<>();
    }

    private final Path file;
    private final Gson gson          = new Gson();
    private State      state         = new State();
    private String     error         = "";
    private boolean    originalValid = true;

    public NotificationStateStore(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try {
                state = load(file);
            } catch (Exception failed) {
                originalValid = false;
                try {
                    state = load(file.resolveSibling(file.getFileName() + ".backup"));
                    error = "Notification settings restored from backup";
                } catch (Exception unavailable) {
                    error = "Notification settings could not be loaded";
                }
            }
        }
    }

    private State load(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > 256 * 1024) {
            throw new IOException("Invalid notification settings file");
        }
        State loaded = gson.fromJson(Files.readString(path), State.class);
        if (loaded == null || loaded.read == null || loaded.reminded == null || loaded.snoozed == null
            || loaded.skipped == null || loaded.noticeExpires == null) {
            throw new IOException("Invalid notification settings data");
        }
        if (loaded.read.values().stream().anyMatch(value -> value == null || value < 0) || loaded.reminded.values()
            .stream().anyMatch(java.util.Objects::isNull) || loaded.snoozed.values().stream().anyMatch(
                java.util.Objects::isNull) || loaded.noticeExpires.values().stream().anyMatch(java.util.Objects::isNull)
            || loaded.skipped.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IOException("Invalid notification state values");
        }
        return loaded;
    }

    public synchronized boolean automatic() {
        return state.automatic;
    }

    public synchronized void remember(java.util.List<Notice> notices, long now) {
        for (Notice notice : notices) {
            state.noticeExpires.put(notice.id(), notice.expiresAt());
        }
        var expired = state.noticeExpires.entrySet().stream().filter(entry -> entry.getValue() < now - 90L * 86400000)
            .map(Map.Entry::getKey).toList();
        for (String id : expired) {
            state.read.remove(id);
            state.noticeExpires.remove(id);
            state.reminded.keySet().removeIf(key -> key.startsWith("notice:" + id + ":"));
        }
        state.snoozed.entrySet().removeIf(entry -> entry.getValue() <= now);
        save();
    }

    public synchronized boolean toasts() {
        return state.toasts;
    }

    public synchronized void toggleAutomatic() {
        state.automatic = !state.automatic;
        save();
    }

    public synchronized void toggleToasts() {
        state.toasts = !state.toasts;
        save();
    }

    public synchronized boolean unread(Notice notice) {
        int read = state.read.getOrDefault(notice.id(), 0);
        return read == 0 || (notice.notifyOnRevision() && read < notice.revision());
    }

    public synchronized void read(Notice notice) {
        state.read.put(notice.id(), notice.revision());
        save();
    }

    public synchronized boolean shouldRemind(String id, long now) {
        return !state.reminded.containsKey(id) && state.snoozed.getOrDefault(id, 0L) <= now;
    }

    public synchronized void reminded(String id, long now) {
        state.reminded.put(id, now);
        save();
    }

    public synchronized void remindedAll(java.util.List<String> ids, long now) {
        ids.forEach(id -> state.reminded.put(id, now));
        save();
    }

    public synchronized void readAll(java.util.List<Notice> notices) {
        notices.forEach(notice -> state.read.put(notice.id(), notice.revision()));
        save();
    }

    public synchronized void snooze(String id, long now) {
        state.reminded.remove(id);
        state.snoozed.put(id, now + 86400000);
        save();
    }

    public synchronized void skip(String module, String version) {
        state.skipped.put(module, version);
        save();
    }

    public synchronized boolean skipped(String module, String version) {
        return version.equals(state.skipped.get(module));
    }

    public synchronized String error() {
        return error;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent())) {
                throw new IOException("Invalid notification settings path");
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Path backup = file.resolveSibling(file.getFileName() + ".backup");
            if (Files.isSymbolicLink(temporary) || Files.isSymbolicLink(backup)) {
                throw new IOException("Invalid notification settings temporary path");
            }
            String serialized = gson.toJson(state);
            if (serialized.length() > 256 * 1024) {
                throw new IOException("Notification settings exceed size limit");
            }
            Files.writeString(temporary, serialized);
            if (Files.exists(file) && originalValid) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            error = "";
            originalValid = true;
        } catch (IOException failed) {
            error = "Notification settings could not be saved";
        }
    }
}
