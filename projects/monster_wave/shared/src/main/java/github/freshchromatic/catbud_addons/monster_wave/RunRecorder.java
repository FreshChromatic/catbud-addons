package github.freshchromatic.catbud_addons.monster_wave;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class RunRecorder implements RunSink {

    private static final Gson              GSON      = new GsonBuilder().serializeNulls().disableHtmlEscaping()
        .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (value, type, context) -> new JsonPrimitive(value
            .toString())).create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'").withZone(
        ZoneOffset.UTC);

    private final Path                        path;
    private final long                        segmentLimit;
    private final ArrayBlockingQueue<Pending> queue;
    private final AtomicLong                  dropped = new AtomicLong();
    private final AtomicLong                  gap     = new AtomicLong();
    private final Thread                      worker;

    private volatile RunEvent terminal;
    private volatile String   error;
    private volatile boolean  finished;

    private record Pending(RunEvent event, long droppedBefore) {}

    RunRecorder(Path directory, UUID id, Instant now) {
        this(directory.resolve(FILE_TIME.format(now) + "-" + id + ".jsonl"), 8192, 16 * 1024 * 1024L);
    }

    RunRecorder(Path path, int capacity, long segmentLimit) {
        this.path = path;
        this.segmentLimit = segmentLimit;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.worker = new Thread(this::write, "catbud-monster-wave-recorder");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void accept(RunEvent event) {
        if (terminal != null || finished) {
            return;
        }
        long missing = gap.getAndSet(0);
        if (!queue.offer(new Pending(event, missing))) {
            gap.addAndGet(missing + 1);
            dropped.incrementAndGet();
        }
    }

    @Override
    public void finish(RunEvent event) {
        if (terminal == null) {
            terminal = event;
        }
    }

    @Override
    public String location() {
        return path.toString();
    }

    @Override
    public String error() {
        return error;
    }

    @Override
    public long droppedEvents() {
        return dropped.get();
    }

    boolean await(long millis) throws InterruptedException {
        worker.join(millis);
        return finished;
    }

    boolean finished() {
        return finished;
    }

    private void write() {
        try {
            Files.createDirectories(path.getParent());
            try (var output = new Segments()) {
                long lastFlush = System.nanoTime();
                while (true) {
                    Pending next = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (next != null) {
                        writeGap(output, next.event(), next.droppedBefore());
                        output.line(GSON.toJson(next.event()));
                    }
                    RunEvent end = terminal;
                    if (end != null && queue.isEmpty()) {
                        writeGap(output, end, gap.getAndSet(0));
                        JsonObject json = GSON.toJsonTree(end).getAsJsonObject();
                        json.addProperty("droppedEvents", dropped.get());
                        json.addProperty("complete", dropped.get() == 0);
                        output.line(GSON.toJson(json));
                        break;
                    }
                    if (System.nanoTime() - lastFlush >= 1_000_000_000L) {
                        output.flush();
                        lastFlush = System.nanoTime();
                    }
                }
            }
        } catch (Exception failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            queue.clear();
        } finally {
            finished = true;
        }
    }

    private void writeGap(Segments output, RunEvent before, long count) throws IOException {
        if (count == 0) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("runId", before.runId().toString());
        json.addProperty("type", "recording_gap");
        json.addProperty("beforeSequence", before.sequence());
        json.addProperty("sinceTriggerMs", before.sinceTriggerMs());
        json.addProperty("droppedEvents", count);
        output.line(GSON.toJson(json));
    }

    private final class Segments implements AutoCloseable {

        private BufferedWriter writer;
        private long           bytes;
        private int            part;

        void line(String line) throws IOException {
            long length = line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (writer == null || bytes > 0 && bytes + length > segmentLimit) {
                close();
                Path next = part == 0 ? path
                    : path.resolveSibling(path.getFileName().toString().replace(".jsonl", String.format(
                        java.util.Locale.ROOT, "-part-%05d.jsonl", part)));
                writer = Files.newBufferedWriter(next, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                part++;
                bytes = 0;
            }
            writer.write(line);
            writer.write('\n');
            bytes += length;
        }

        void flush() throws IOException {
            if (writer != null) {
                writer.flush();
            }
        }

        @Override
        public void close() throws IOException {
            if (writer != null) {
                writer.close();
                writer = null;
            }
        }
    }
}
