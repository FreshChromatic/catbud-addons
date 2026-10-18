package github.freshchromatic.catbud_addons.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public record JarMetadata(String modId, String version, Map<String, String> requires,
    List<ReleaseManifest.ProvidedMod> provides) {

    public JarMetadata {
        requires = Map.copyOf(requires);
        provides = List.copyOf(provides);
    }

    public static JarMetadata read(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > ReleaseManifest.MAX_ARTIFACT_BYTES) {
            throw new IOException("Unsupported addon archive: " + path.getFileName());
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                throw new IOException("Archive has no fabric.mod.json");
            }
            JsonObject root;
            try (var stream = zip.getInputStream(entry)) {
                root = metadata(stream);
            }
            var provided = new ArrayList<ReleaseManifest.ProvidedMod>();
            addProvided(root, provided);
            if (root.has("jars")) {
                if (root.getAsJsonArray("jars").size() > 16) {
                    throw new IOException("Too many embedded archives");
                }
                for (var nested : root.getAsJsonArray("jars")) {
                    var file = zip.getEntry(nested.getAsJsonObject().get("file").getAsString());
                    if (file == null) {
                        throw new IOException("Missing embedded archive");
                    }
                    try (var input = zip.getInputStream(file)) {
                        readNested(bounded(input, (int) ReleaseManifest.MAX_ARTIFACT_BYTES), provided, 0);
                    }
                }
            }
            Map<String, String> requires = new LinkedHashMap<>();
            if (root.has("depends")) {
                for (var dependency : root.getAsJsonObject("depends").entrySet()) {
                    requires.put(dependency.getKey(), dependency.getValue().getAsString());
                }
            }
            return new JarMetadata(root.get("id").getAsString(), root.get("version").getAsString(), requires, provided);
        } catch (RuntimeException error) {
            throw new IOException("Invalid addon archive metadata", error);
        }
    }

    private static void readNested(byte[] bytes, List<ReleaseManifest.ProvidedMod> provided, int depth)
        throws IOException {
        if (depth > 2) {
            throw new IOException("Embedded archive nesting exceeds limit");
        }
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            JsonObject root = null;
            var entries = new LinkedHashMap<String, byte[]>();
            int count = 0;
            long size = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++count > 10000) {
                    throw new IOException("Embedded archive entry limit exceeded");
                }
                byte[] contents = bounded(zip, entry.getName().equals("fabric.mod.json") ? 128 * 1024
                    : (int) ReleaseManifest.MAX_ARTIFACT_BYTES);
                size += contents.length;
                if (size > 128L * 1024 * 1024) {
                    throw new IOException("Embedded archive expansion limit exceeded");
                }
                if (entry.getName().equals("fabric.mod.json")) {
                    root = metadata(new ByteArrayInputStream(contents));
                } else if (entry.getName().endsWith(".jar")) {
                    if (entries.size() >= 16) {
                        throw new IOException("Embedded archive size limit exceeded");
                    }
                    entries.put(entry.getName(), contents);
                }
            }
            if (root == null) {
                throw new IOException("Embedded archive has no metadata");
            }
            addProvided(root, provided);
            if (root.has("jars")) {
                for (var nested : root.getAsJsonArray("jars")) {
                    byte[] contents = entries.get(nested.getAsJsonObject().get("file").getAsString());
                    if (contents == null) {
                        throw new IOException("Missing embedded dependency");
                    }
                    readNested(contents, provided, depth + 1);
                }
            }
        }
    }

    private static void addProvided(JsonObject root, List<ReleaseManifest.ProvidedMod> provided) {
        String id = root.get("id").getAsString();
        if (AddonModule.forMod(id).isPresent()) {
            provided.add(new ReleaseManifest.ProvidedMod(id, root.get("version").getAsString()));
        }
    }

    private static JsonObject metadata(InputStream input) throws IOException {
        return JsonParser.parseString(new String(bounded(input, 128 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] bounded(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw new IOException("Archive entry size limit exceeded");
        }
        return bytes;
    }
}
