package github.freshchromatic.catbud_addons.update.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record InstallationPlan(String batchId, String releaseId, String minecraft, Path mods, List<Item> items) {

    public InstallationPlan {
        UUID.fromString(batchId);
        if (!releaseId.matches("v[0-9]+\\.[0-9]+\\.[0-9]+") || !List.of("1.21.11", "26.1.2", "26.2", "26.3").contains(
            minecraft)) {
            throw new IllegalArgumentException("Invalid installation release");
        }
        mods = mods.toAbsolutePath().normalize();
        items = List.copyOf(items);
        if (items.isEmpty() || items.size() > 4) {
            throw new IllegalArgumentException("Invalid installation batch size");
        }
        var names = new HashSet<String>();
        for (Item item : items) {
            if (!names.add(item.oldFile()) || !names.add(item.newFile())) {
                throw new IllegalArgumentException("Duplicate installation target");
            }
        }
    }

    public record Item(String oldFile, String oldHash, String newFile, String newHash, long size) {

        public Item {
            if (!safeName(oldFile) || !safeName(newFile) || !oldHash.matches("[0-9a-f]{64}") || !newHash.matches(
                "[0-9a-f]{64}") || size < 1 || size > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("Invalid installation item");
            }
        }

        private static boolean safeName(String value) {
            return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.+-]{0,180}\\.jar") && !value.contains("..");
        }
    }

    public static void requireRealDirectory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path current = absolute; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not supported in update paths");
            }
        }
        if (!Files.isDirectory(absolute) || !absolute.toRealPath().equals(absolute)) {
            throw new IOException("Update directory is not canonical");
        }
    }
}
