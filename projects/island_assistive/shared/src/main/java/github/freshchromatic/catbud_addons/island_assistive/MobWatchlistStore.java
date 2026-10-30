package github.freshchromatic.catbud_addons.island_assistive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class MobWatchlistStore {

    public static final List<MobWatchRule> DEFAULT_RULES = List.of(new MobWatchRule("minecraft:zombie_villager",
        MobWatchRule.NameMatch.ANY, ""));

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path              path;
    private final Predicate<String> knownType;
    private List<MobWatchRule>      rules = DEFAULT_RULES;
    private boolean                 loadFailed;

    public MobWatchlistStore(Path path, Predicate<String> knownType) {
        this.path = path;
        this.knownType = knownType;
        if (!Files.exists(path)) {
            return;
        }
        try {
            var document = Objects.requireNonNull(GSON.fromJson(Files.readString(path), Document.class));
            if (document.version() != 1) {
                throw new IllegalArgumentException("Unsupported watchlist version");
            }
            rules = List.copyOf(document.rules());
            rules.forEach(this::validate);
        } catch (IOException | RuntimeException error) {
            loadFailed = true;
            rules = DEFAULT_RULES;
            LoggerFactory.getLogger(MobWatchlistStore.class).warn("Cannot load watchlist {}", path, error);
        }
    }

    public void validate(MobWatchRule rule) {
        if (!rule.entityType().isEmpty() && !knownType.test(rule.entityType())) {
            throw new IllegalArgumentException("island.watchlist.unknown_type");
        }
    }

    public List<MobWatchRule> rules() {
        return rules;
    }

    public boolean loadFailed() {
        return loadFailed;
    }

    public void save(List<MobWatchRule> draft) throws IOException {
        List<MobWatchRule> next = List.copyOf(draft);
        next.forEach(this::validate);
        Files.createDirectories(path.getParent());
        if (loadFailed && Files.exists(path)) {
            Files.copy(path, path.resolveSibling(path.getFileName() + ".invalid-" + System.currentTimeMillis()));
        }
        Path temporary = Files.createTempFile(path.getParent(), ".watchlist-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(new Document(1, next)));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        rules = next;
        loadFailed = false;
    }

    private record Document(int version, List<MobWatchRule> rules) {}
}
