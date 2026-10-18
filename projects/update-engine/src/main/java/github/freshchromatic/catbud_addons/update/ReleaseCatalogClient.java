package github.freshchromatic.catbud_addons.update;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ReleaseCatalogClient {

    public record Catalog(String state, ReleaseManifest manifest, long checkedAt) {}

    private final ReleaseHttpClient http;
    private final ManifestVerifier  verifier;
    private final String            minecraft;
    private final Path              cache;
    private final Gson              gson = new Gson();
    private String                  body = "";
    private String                  etag = "";

    public ReleaseCatalogClient(ReleaseHttpClient http, ManifestVerifier verifier, String minecraft, Path cache) {
        this.http = http;
        this.verifier = verifier;
        this.minecraft = minecraft;
        this.cache = cache;
    }

    public Catalog cached() throws Exception {
        if (!Files.exists(cache)) {
            return null;
        }
        if (Files.isSymbolicLink(cache) || Files.size(cache) > 512 * 1024) {
            throw new IOException("Invalid cached release catalog");
        }
        var cached = parse(Files.readString(cache));
        return new Catalog(cached.state(), cached.manifest(), Files.getLastModifiedTime(cache).toMillis());
    }

    public Catalog check(boolean fresh) throws Exception {
        var response = http.json("catalog?minecraft=" + minecraft + "&channel=stable", etag, fresh);
        String received = response.unchanged() ? body : response.body();
        if (received.isEmpty()) {
            throw new IOException("Missing cached update response");
        }
        Catalog catalog = parse(received);
        body = received;
        etag = response.etag();
        Files.createDirectories(cache.getParent());
        Path temporary = cache.resolveSibling(cache.getFileName() + ".tmp");
        if (Files.isSymbolicLink(cache) || Files.isSymbolicLink(temporary)) {
            throw new IOException("Invalid release cache path");
        }
        Files.writeString(temporary, body);
        Files.move(temporary, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return catalog;
    }

    private Catalog parse(String body) throws Exception {
        var object = JsonParser.parseString(body).getAsJsonObject();
        if (object.get("schemaVersion").getAsInt() != 1 || !object.get("minecraft").getAsString().equals(minecraft)) {
            throw new IOException("Unsupported catalog response");
        }
        String state = object.get("state").getAsString();
        ReleaseManifest manifest = null;
        if (state.equals("available")) {
            manifest = verifier.verify(gson.fromJson(object.get("envelope"), ManifestVerifier.Envelope.class));
        } else if (!List.of("no_compatible_release", "release_unavailable").contains(state)) {
            throw new IOException("Unsupported catalog state");
        }
        return new Catalog(state, manifest, object.get("checkedAt").getAsLong());
    }

}
