package github.freshchromatic.catbud_addons.update;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class JarMetadataTest {

    @TempDir Path directory;

    @Test
    void unrelatedModsAcceptDependencyAlternativesAndManyEmbeddedLibraries() throws Exception {
        var nested = new LinkedHashMap<String, byte[]>();
        for (int index = 0; index < 40; index++) {
            nested.put("META-INF/jars/library-" + index + ".jar", archive("library_" + index, Map.of(), Map.of()));
        }
        Files.write(directory.resolve("fabric-api.jar"), archive("fabric-api", Map.of("minecraft", List.of("26.2",
            "26.3")), nested));
        Files.write(directory.resolve("flashback.jar"), archive("flashback", Map.of("java", List.of(">=25", ">=26")),
            Map.of("META-INF/jars/plain.jar", plainLibrary())));
        var inventory = InstalledAddonInventory.scan(directory, List.of());
        assertTrue(inventory.problems().isEmpty(), inventory.problems().toString());
        assertTrue(inventory.jars().isEmpty());
        assertThrows(IOException.class, () -> JarMetadata.read(directory.resolve("fabric-api.jar")));
        assertThrows(IOException.class, () -> JarMetadata.read(directory.resolve("flashback.jar")));
    }

    @Test
    void largeUnrelatedBinaryArchiveDoesNotUseTheCatbudArtifactSizeLimit() throws Exception {
        Path binary = directory.resolve("watermedia_binaries.jar");
        writeLargeArchive(binary, archive("watermedia_binaries", Map.of(), Map.of()));
        assertTrue(Files.size(binary) > ReleaseManifest.MAX_ARTIFACT_BYTES);
        assertTrue(InstalledAddonInventory.scan(directory, List.of()).problems().isEmpty());
        assertThrows(IOException.class, () -> JarMetadata.read(binary));
    }

    @Test
    void unrelatedParentCannotHideCatbudInsideItsEmbeddedLibraries() throws Exception {
        var nested = new LinkedHashMap<String, byte[]>();
        for (int index = 0; index < 20; index++) {
            nested.put("META-INF/jars/library-" + index + ".jar", plainLibrary());
        }
        nested.put("META-INF/jars/core.jar", archive("catbud_core", Map.of(), Map.of()));
        Files.write(directory.resolve("parent.jar"), archive("third_party", Map.of(), nested));
        var inventory = InstalledAddonInventory.scan(directory, List.of("catbud_core"));
        assertTrue(inventory.problems().stream().anyMatch(problem -> problem.contains("unsupported parent")));
        assertTrue(inventory.jars().isEmpty());
    }

    @Test
    void installedCatbudArchivesKeepStrictLimitsAndBrokenArchivesRemainBlocked() throws Exception {
        writeLargeArchive(directory.resolve("core.jar"), archive("catbud_core", Map.of(), Map.of()));
        Files.writeString(directory.resolve("broken.jar"), "not a zip");
        var inventory = InstalledAddonInventory.scan(directory, List.of("catbud_core"));
        assertEquals(3, inventory.problems().size());
        assertTrue(inventory.problems().stream().anyMatch(problem -> problem.contains("Unsupported addon archive")));
        assertTrue(inventory.problems().stream().anyMatch(problem -> problem.contains("broken.jar:")));
    }

    private static void writeLargeArchive(Path target, byte[] archive) throws IOException {
        try (var channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(ReleaseManifest.MAX_ARTIFACT_BYTES + 1);
            Channels.newOutputStream(channel).write(archive);
        }
    }

    private static byte[] archive(String id, Map<String, Object> depends, Map<String, byte[]> nested)
        throws IOException {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("schemaVersion", 1);
        metadata.put("id", id);
        metadata.put("version", "1.0.0");
        metadata.put("depends", depends);
        metadata.put("jars", nested.keySet().stream().map(file -> Map.of("file", file)).toList());
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(new Gson().toJson(metadata).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (var entry : nested.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] plainLibrary() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("library.txt"));
            zip.write(1);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
