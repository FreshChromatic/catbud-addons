package github.freshchromatic.catbud_addons.update;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class NoticeRepository {

    private final ReleaseHttpClient http;
    private final String            minecraft;
    private final Path              directory;
    private final Gson              gson  = new Gson();
    private volatile String         error = "";

    public NoticeRepository(ReleaseHttpClient http, String minecraft, Path directory) {
        this.http = http;
        this.minecraft = minecraft;
        this.directory = directory;
    }

    public String error() {
        return error;
    }

    public List<Notice> refresh(String server) {
        try {
            List<Notice> notices = fetch(server);
            String body = gson.toJson(notices);
            Files.createDirectories(directory);
            Path target = cache(server);
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            if (Files.isSymbolicLink(target) || Files.isSymbolicLink(temporary)) {
                throw new IOException("Invalid announcement cache path");
            }
            Files.writeString(temporary, body);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            error = "";
            return notices;
        } catch (Exception failed) {
            error = failed.getMessage() == null ? "Announcements unavailable" : failed.getMessage();
            try {
                return cached(server);
            } catch (Exception unavailable) {
                return List.of();
            }
        }
    }

    public List<Notice> cached(String server) throws IOException {
        Path path = cache(server);
        if (!Files.exists(path)) {
            return List.of();
        }
        if (Files.isSymbolicLink(path) || Files.size(path) > 4 * 1024 * 1024) {
            throw new IOException("Invalid cached announcements");
        }
        try {
            return List.of(gson.fromJson(Files.readString(path), Notice[].class));
        } catch (RuntimeException malformed) {
            throw new IOException("Cached announcements could not be read", malformed);
        }
    }

    private Path cache(String server) {
        if (!List.of("", "catbud", "catrealm").contains(server)) {
            throw new IllegalArgumentException("Invalid announcement server");
        }
        return directory.resolve("notices-" + (server.isEmpty() ? "global" : server) + ".json");
    }

    private List<Notice> fetch(String server) throws Exception {
        cache(server);
        var notices = new ArrayList<Notice>();
        var cursors = new java.util.HashSet<String>();
        String cursor = "";
        for (int page = 0; page < 32; page++) {
            String route = "notices?minecraft=" + minecraft + (server.isEmpty() ? "" : "&server=" + server) + (cursor
                .isEmpty() ? "" : "&cursor=" + cursor);
            var object = JsonParser.parseString(http.json(route, "", false).body()).getAsJsonObject();
            if (object.get("schemaVersion").getAsInt() != 1 || object.getAsJsonArray("notices").size() > 20) {
                throw new IOException("Invalid announcement response");
            }
            for (var item : object.getAsJsonArray("notices")) {
                notices.add(gson.fromJson(item, Notice.class));
            }
            cursor = object.get("nextCursor").getAsString();
            if (cursor.isEmpty()) {
                return List.copyOf(notices);
            }
            if (!cursor.matches("[0-9a-f-]{36}") || !cursors.add(cursor)) {
                throw new IOException("Invalid announcement pagination");
            }
        }
        throw new IOException("Announcement pagination exceeds limit");
    }
}
