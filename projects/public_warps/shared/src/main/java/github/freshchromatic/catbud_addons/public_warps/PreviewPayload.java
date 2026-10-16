package github.freshchromatic.catbud_addons.public_warps;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

public record PreviewPayload(String encoded, String digest, String reportEncoded) {

    public static PreviewPayload from(BlockSnapshot snapshot) throws Exception {
        return from(snapshot, false);
    }

    public static PreviewPayload from(BlockSnapshot snapshot, boolean shared) throws Exception {
        if (snapshot == null) {
            return new PreviewPayload(null, null, null);
        }
        String encoded = Base64.getEncoder().encodeToString(snapshot.encode());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.US_ASCII));
        String reportEncoded = encoded;
        if (shared) {
            try {
                reportEncoded = Base64.getEncoder().encodeToString(SharedPreview.from(snapshot).bytes());
            } catch (SharedPreview.LimitException oversized) {
                reportEncoded = null;
            }
        }
        return new PreviewPayload(encoded, HexFormat.of().formatHex(digest), reportEncoded);
    }
}
