package github.freshchromatic.catbud_addons.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

public final class LauncherIntegration {

    private final boolean guarded;
    private final String  command;

    public LauncherIntegration(Path game, Path config, byte[] helper) throws IOException {
        Path updates = config.resolve("updates");
        Files.createDirectories(updates);
        Files.createDirectories(game.resolve("mods"));
        Path mods = game.resolve("mods").toRealPath();
        Path launcher = config.resolve("update-launcher.jar");
        Path temporary = config.resolve("update-launcher.tmp");
        if (Files.isSymbolicLink(launcher) || Files.isSymbolicLink(temporary)) {
            throw new IOException("Invalid launcher integration path");
        }
        Files.write(temporary, helper);
        Files.move(temporary, launcher, StandardCopyOption.REPLACE_EXISTING);
        Path token = updates.resolve("launch-ready");
        boolean accepted = false;
        if (Files.exists(token) && !Files.isSymbolicLink(token) && Files.size(token) < 4096) {
            List<String> lines = Files.readAllLines(token);
            Files.delete(token);
            try {
                long issued = Long.parseLong(lines.get(1));
                accepted = lines.size() == 2 && Path.of(lines.getFirst()).equals(mods) && issued <= System
                    .currentTimeMillis() && issued >= System.currentTimeMillis() - 300000;
            } catch (RuntimeException malformed) {
                accepted = false;
            }
        }
        guarded = accepted;
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        command = quote(java.toString(), windows) + " -jar " + quote(launcher.toString(), windows) + " guard " + quote(
            mods.toString(), windows) + " " + quote(updates.toRealPath().toString(), windows);
    }

    public boolean guarded() {
        return guarded;
    }

    public String command() {
        return command;
    }

    private static String quote(String value, boolean windows) {
        return windows ? "\"" + value + "\"" : "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
