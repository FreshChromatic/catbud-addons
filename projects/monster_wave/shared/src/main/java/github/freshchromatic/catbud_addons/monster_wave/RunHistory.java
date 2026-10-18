package github.freshchromatic.catbud_addons.monster_wave;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

final class RunHistory {

    record Latest(String path, boolean complete, String reason, Long totalDurationMs, Long activeDurationMs,
        String error) {}

    private RunHistory() {
    }

    static Latest latest(Path directory) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        Path latest = null;
        try (var files = Files.list(directory)) {
            latest = files.filter(path -> path.getFileName().toString().endsWith(".jsonl")).max(Comparator.comparing(
                path -> path.getFileName().toString().replace(".jsonl", "-part-00000"))).orElse(null);
            if (latest == null) {
                return null;
            }
            String tail = tail(latest);
            String[] lines = tail.stripTrailing().split("\n");
            JsonObject last = JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();
            if (!last.has("type") || !last.get("type").getAsString().equals("run_ended")) {
                return new Latest(latest.toString(), false, null, null, null, null);
            }
            var data = last.getAsJsonObject("data");
            boolean complete = last.has("complete") && last.get("complete").getAsBoolean();
            return new Latest(latest.toString(), complete, data.get("reason").getAsString(), data.get("totalDurationMs")
                .getAsLong(), data.get("activeDurationMs").isJsonNull() ? null
                    : data.get("activeDurationMs").getAsLong(), null);
        } catch (Exception failure) {
            return new Latest(latest == null ? directory.toString() : latest.toString(), false, null, null, null,
                failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static String tail(Path path) throws IOException {
        try (SeekableByteChannel input = Files.newByteChannel(path)) {
            int size = (int) Math.min(input.size(), 64 * 1024);
            var bytes = ByteBuffer.allocate(size);
            input.position(input.size() - size);
            while (bytes.hasRemaining()) {
                if (input.read(bytes) < 0) {
                    break;
                }
            }
            bytes.flip();
            return StandardCharsets.UTF_8.decode(bytes).toString();
        }
    }
}
