package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class BlockMiningTargetStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;

    private BlockMiningTarget target;
    private boolean           loadFailed;

    public BlockMiningTargetStore(Path path) {
        this.path = path;
        if (!Files.exists(path)) {
            return;
        }
        try {
            var document = Objects.requireNonNull(GSON.fromJson(Files.readString(path), Document.class));
            if (document.version() != 1) {
                throw new IllegalArgumentException("Unsupported mining target version");
            }
            target = Objects.requireNonNull(document.target());
        } catch (IOException | RuntimeException error) {
            loadFailed = true;
            LoggerFactory.getLogger(BlockMiningTargetStore.class).warn("Cannot load mining target {}", path, error);
        }
    }

    public BlockMiningTarget target() {
        return target;
    }

    public boolean loadFailed() {
        return loadFailed;
    }

    public void save(BlockMiningTarget next) throws IOException {
        Objects.requireNonNull(next);
        Files.createDirectories(path.getParent());
        if (loadFailed) {
            Files.copy(path, path.resolveSibling(path.getFileName() + ".invalid-" + System.currentTimeMillis()));
        }
        Path temporary = Files.createTempFile(path.getParent(), ".mining-target-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(new Document(1, next)));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        target = next;
        loadFailed = false;
    }

    private record Document(int version, BlockMiningTarget target) {}
}
