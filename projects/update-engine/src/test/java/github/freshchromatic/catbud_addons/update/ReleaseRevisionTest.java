package github.freshchromatic.catbud_addons.update;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseRevisionTest {

    @Test
    void keepsTheModVersionSeparateFromItsReleaseRevision() {
        assertEquals(new ReleaseRevision("1.0.2", 1), ReleaseRevision.parse("1.0.2", "v1.0.2"));
        var revision = ReleaseRevision.parse("1.0.2", "v1.0.2-r2");
        assertEquals("1.0.2", revision.version());
        assertEquals(2, revision.revision());
        assertEquals("v1.0.2-r2", revision.tag());
        assertEquals("1.0.2 (r2)", revision.displayVersion());
        assertEquals(revision, ReleaseRevision.installed("1.0.2", "core", "26.2", revision.file("core", "26.2")));
        assertEquals(1, ReleaseRevision.installed("1.0.2", "core", "26.2", "renamed.jar").revision());
    }

    @Test
    void rejectsAmbiguousRevisionTagsAndVersionMismatches() {
        for (String tag : new String[] { "v1.0.3-r2", "v1.0.2-r0", "v1.0.2-r1", "v1.0.2-r02", "v1.0.2-r-2",
            "v1.0.2-r2147483648", "v1.0.2-r2/other", "v1.0.2-rc1", "v1.0.2+r2" }) {
            assertThrows(IllegalArgumentException.class, () -> ReleaseRevision.parse("1.0.2", tag));
        }
        assertThrows(IllegalArgumentException.class, () -> new ReleaseRevision("1.0.2", 0));
        assertThrows(IllegalArgumentException.class, () -> new ReleaseRevision("01.0.2", 2));
    }
}
