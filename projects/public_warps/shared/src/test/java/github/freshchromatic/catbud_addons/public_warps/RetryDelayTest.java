package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.time.format.DateTimeFormatter;

class RetryDelayTest {

    @Test
    void serverHintPostponesRetryAndMalformedHintsKeepBackoff() {
        long now = 1800000000000L;
        assertEquals(60000, RetryDelay.milliseconds(1, "invalid", now));
        assertEquals(120000, RetryDelay.milliseconds(1, "120", now));
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 600000).atZone(
            ZoneOffset.UTC));
        assertEquals(600000, RetryDelay.milliseconds(1, date, now));
        assertEquals(86400000, RetryDelay.milliseconds(1, "999999999", now));
        assertEquals(1800000, RetryDelay.milliseconds(20, null, now));
    }
}
