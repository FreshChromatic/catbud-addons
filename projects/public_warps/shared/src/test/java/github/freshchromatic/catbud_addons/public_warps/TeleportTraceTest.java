package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Map;

class TeleportTraceTest {

    @TempDir Path dir;

    @Test
    void eventTimeSurvivesBackgroundWritingAndRotationStaysBounded() throws Exception {
        Path path = dir.resolve("trace.jsonl");
        var trace = new TeleportTrace(path);
        trace.append(1000, 7, "command", Map.of("target", "Cat"));
        var entry = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals(1000, entry.get("at").getAsLong());
        Files.writeString(path, " ".repeat(1024 * 1024));
        trace.append(2000, 7, "boss_removed", Map.of());
        assertTrue(Files.exists(dir.resolve("trace.jsonl.previous")));
        assertTrue(Files.size(path) < 1024);
    }
}
