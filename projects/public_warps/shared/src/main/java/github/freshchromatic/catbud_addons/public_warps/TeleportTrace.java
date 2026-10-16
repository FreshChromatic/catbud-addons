package github.freshchromatic.catbud_addons.public_warps;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** Optional local protocol evidence. Call on the ordered cache executor. Never uploaded. */
public final class TeleportTrace {

    private static final Gson JSON = new Gson();
    private final Path        path;

    public TeleportTrace(Path path) {
        this.path = path;
    }

    public void append(long at, long operation, String event, Map<String, ?> details) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.exists(path) && Files.size(path) >= 1024 * 1024) {
            Files.move(path, path.resolveSibling(path.getFileName() + ".previous"),
                StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(path, JSON.toJson(Map.of("at", at, "operation", operation, "event", event, "details",
            details)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
