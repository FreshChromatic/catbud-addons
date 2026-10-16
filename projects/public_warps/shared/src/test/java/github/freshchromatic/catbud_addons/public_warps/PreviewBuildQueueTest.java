package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

class PreviewBuildQueueTest {

    private static final class Work implements PreviewBuildQueue.Task<Integer> {

        private final AtomicLong clock;
        private final int        total;
        private int              steps;
        private int              cancellations;

        Work(AtomicLong clock, int total) {
            this.clock = clock;
            this.total = total;
        }

        @Override
        public boolean advance() {
            steps++;
            clock.addAndGet(500000);
            return steps == total;
        }

        @Override
        public Integer result() {
            assertEquals(total, steps);
            return steps;
        }

        @Override
        public void cancel() {
            cancellations++;
        }
    }

    @Test
    void abandonedTasksReleaseResourcesExactlyOnce() {
        var clock = new AtomicLong();
        var queue = new PreviewBuildQueue<String, Integer>(clock::get, (key, value) -> fail());
        var invisible = new Work(clock, 100);
        queue.beginFrame();
        queue.request("invisible", () -> invisible);
        queue.endFrame();
        queue.beginFrame();
        queue.endFrame();
        queue.clear();
        assertEquals(1, invisible.cancellations);

        var cleared = new Work(clock, 100);
        queue.beginFrame();
        queue.request("cleared", () -> cleared);
        queue.endFrame();
        queue.clear();
        queue.clear();
        assertEquals(1, cleared.cancellations);
    }

    @Test
    void multipleDrawsShareOneFrameBudgetAndPublishOnlyACompleteResult() {
        var clock = new AtomicLong();
        var published = new ArrayList<Integer>();
        var queue = new PreviewBuildQueue<String, Integer>(clock::get, (key, value) -> published.add(value));
        var work = new Work(clock, 10);
        for (int frame = 0; frame < 3; frame++) {
            queue.beginFrame();
            queue.request("scene", () -> work);
            queue.request("scene", () -> work);
            queue.endFrame();
            assertEquals(Math.min((frame + 1) * 4, 10), work.steps);
            assertEquals(frame == 2 ? List.of(10) : List.of(), published);
        }
    }

    @Test
    void detailRequestHasPriorityAndInvisibleOrClearedWorkCannotPublish() {
        var clock = new AtomicLong();
        var published = new ArrayList<String>();
        var queue = new PreviewBuildQueue<String, Integer>(clock::get, (key, value) -> published.add(key));
        var detail = new Work(clock, 10);
        queue.beginFrame();
        queue.request("tile", () -> new Work(clock, 1));
        queue.request("detail", () -> detail);
        queue.endFrame();
        assertEquals(4, detail.steps);
        queue.beginFrame();
        queue.request("replacement", () -> new Work(clock, 1));
        queue.endFrame();
        assertEquals(List.of("replacement"), published);
        queue.beginFrame();
        queue.request("unfinished", () -> new Work(clock, 100));
        queue.endFrame();
        queue.clear();
        assertFalse(queue.hasWork());
        queue.endFrame();
        assertEquals(List.of("replacement"), published);
    }
}
