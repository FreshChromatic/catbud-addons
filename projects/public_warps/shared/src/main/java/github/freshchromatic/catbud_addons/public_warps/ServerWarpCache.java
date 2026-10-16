package github.freshchromatic.catbud_addons.public_warps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class ServerWarpCache {

    private ServerWarpCache() {
    }

    public static Path path(Path directory, UUID account, WarpServerScope scope) {
        return directory.resolve(scope.id).resolve(account + ".json");
    }

    public static WarpStore load(Path directory, UUID account, WarpServerScope scope, String address)
        throws IOException {
        Path target = path(directory, account, scope);
        Path legacy = directory.resolve(UUID.nameUUIDFromBytes((account + "@" + address.toLowerCase(
            java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8)) + ".json");
        boolean migrate = !Files.exists(target) && scope == WarpServerScope.CATBUD && Files.exists(legacy);
        WarpStore store = WarpStore.load(migrate ? legacy : target);
        if (store.serverId != null && !store.serverId.equals(scope.id)) {
            throw new IOException("Warp cache belongs to a different server");
        }
        store.serverId = scope.id;
        if (migrate) {
            store.save(target);
        }
        return store;
    }
}
