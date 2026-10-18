package github.freshchromatic.catbud_addons.monster_wave;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CleanupWait {

    private CleanupWait() {
    }

    static <T> T await(Future<T> task, Runnable refresh) throws InterruptedException, ExecutionException {
        while (true) {
            try {
                return task.get(16, TimeUnit.MILLISECONDS);
            } catch (TimeoutException pending) {
                refresh.run();
            }
        }
    }
}
