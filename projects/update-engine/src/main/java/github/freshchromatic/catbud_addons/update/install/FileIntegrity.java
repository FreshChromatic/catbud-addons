package github.freshchromatic.catbud_addons.update.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class FileIntegrity {

    private FileIntegrity() {
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int size = input.read(buffer); size != -1; size = input.read(buffer)) {
                    digest.update(buffer, 0, size);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static void require(Path path, String hash) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || !sha256(path).equals(hash)) {
            throw new IOException("File changed or is unavailable: " + path.getFileName());
        }
    }
}
