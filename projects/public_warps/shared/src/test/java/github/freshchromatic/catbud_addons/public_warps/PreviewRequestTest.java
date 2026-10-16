package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PreviewRequestTest {

    @Test
    void inFlightDownloadCannotOverwriteNewCaptureOrNewSharedVersion() {
        var download = new PreviewRequest(null, "old");
        assertTrue(download.matches(null, "old"));
        assertFalse(download.matches("new local capture", "old"));
        assertFalse(download.matches(null, "new"));
        assertFalse(download.matches(null, null));
        var localRead = new PreviewRequest("old local capture", null);
        assertFalse(localRead.matches("new local capture", null));
        assertTrue(localRead.matches("old local capture", "shared version"));
    }
}
