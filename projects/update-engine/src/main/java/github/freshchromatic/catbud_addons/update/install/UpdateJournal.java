package github.freshchromatic.catbud_addons.update.install;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Properties;

public final class UpdateJournal {

    private final Path             directory;
    private final InstallationPlan plan;

    public UpdateJournal(Path directory, InstallationPlan plan) throws IOException {
        InstallationPlan.requireRealDirectory(directory);
        InstallationPlan.requireRealDirectory(plan.mods());
        this.directory = directory.toRealPath();
        this.plan = plan;
    }

    public Path directory() {
        return directory;
    }

    public InstallationPlan plan() {
        return plan;
    }

    public void create() throws IOException {
        Properties data = new Properties();
        data.setProperty("schema", "1");
        data.setProperty("batch", plan.batchId());
        data.setProperty("release", plan.releaseId());
        data.setProperty("minecraft", plan.minecraft());
        data.setProperty("mods", plan.mods().toString());
        data.setProperty("count", Integer.toString(plan.items().size()));
        for (int i = 0; i < plan.items().size(); i++) {
            var item = plan.items().get(i);
            data.setProperty(i + ".old", item.oldFile());
            data.setProperty(i + ".oldHash", item.oldHash());
            data.setProperty(i + ".new", item.newFile());
            data.setProperty(i + ".newHash", item.newHash());
            data.setProperty(i + ".size", Long.toString(item.size()));
        }
        Path path = directory.resolve("plan.properties");
        try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            data.store(output, null);
        }
        force(path);
        result("PREPARED", "");
    }

    public static UpdateJournal read(Path directory) throws IOException {
        InstallationPlan.requireRealDirectory(directory);
        Properties data = properties(directory.resolve("plan.properties"));
        try {
            if (!"1".equals(data.getProperty("schema"))) {
                throw new IllegalArgumentException("Unknown installation schema");
            }
            int count = Integer.parseInt(data.getProperty("count"));
            if (count < 1 || count > 4) {
                throw new IllegalArgumentException("Invalid installation batch size");
            }
            var items = new ArrayList<InstallationPlan.Item>();
            for (int i = 0; i < count; i++) {
                items.add(new InstallationPlan.Item(data.getProperty(i + ".old"), data.getProperty(i + ".oldHash"), data
                    .getProperty(i + ".new"), data.getProperty(i + ".newHash"), Long.parseLong(data.getProperty(i
                        + ".size"))));
            }
            return new UpdateJournal(directory, new InstallationPlan(data.getProperty("batch"), data.getProperty(
                "release"), data.getProperty("minecraft"), Path.of(data.getProperty("mods")), items));
        } catch (RuntimeException error) {
            throw new IOException("Invalid installation journal", error);
        }
    }

    public boolean cancelled() {
        return Files.exists(directory.resolve("cancel"));
    }

    public void result(String status, String detail) throws IOException {
        Properties data = new Properties();
        data.setProperty("status", status);
        data.setProperty("detail", detail);
        data.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
        Path target = directory.resolve("result.properties");
        Path temp = directory.resolve("result.tmp");
        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(temp)) {
            throw new IOException("Invalid journal file");
        }
        try (var output = Files.newOutputStream(temp)) {
            data.store(output, null);
        }
        force(temp);
        move(temp, target, true);
    }

    public static Properties properties(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || Files.size(file) > 32 * 1024) {
            throw new IOException("Invalid journal size or path");
        }
        Properties data = new Properties();
        try (var input = Files.newInputStream(file)) {
            data.load(input);
        }
        return data;
    }

    static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static void move(Path from, Path to, boolean replace) throws IOException {
        if (replace) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            // ATOMIC_MOVE may replace an existing target, so never use it for a new mod or backup.
            Files.move(from, to);
        }
    }
}
