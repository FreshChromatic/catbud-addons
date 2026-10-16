package github.freshchromatic.catbud_addons.public_warps;

import java.time.*;
import java.time.format.DateTimeFormatter;

/** Honor bounded server retry hints, while malformed hints retain normal exponential backoff. */
public final class RetryDelay {

    private RetryDelay() {
    }

    public static long milliseconds(int failures, String retryAfter, long now) {
        long delay = Math.min(1800000L, 30000L << Math.max(1, Math.min(6, failures)));
        if (retryAfter == null) {
            return delay;
        }
        try {
            long hint;
            if (retryAfter.matches("[0-9]{1,9}")) {
                hint = Long.parseLong(retryAfter) * 1000;
            } else {
                hint = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                    - now;
            }
            return Math.max(delay, Math.min(86400000L, Math.max(0, hint)));
        } catch (RuntimeException ignored) {
            return delay;
        }
    }
}
