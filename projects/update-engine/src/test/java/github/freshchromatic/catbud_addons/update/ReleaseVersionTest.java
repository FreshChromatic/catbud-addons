package github.freshchromatic.catbud_addons.update;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseVersionTest {

    @Test
    void comparesNumericComponentsAndPrereleases() {
        String[] ordered = { "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.9.9", "1.10.0", "2.0.0" };
        for (int i = 1; i < ordered.length; i++) {
            assertTrue(ReleaseVersion.parse(ordered[i - 1]).compareTo(ReleaseVersion.parse(ordered[i])) < 0);
        }
        assertEquals(0, ReleaseVersion.parse("1.0.3+local").compareTo(ReleaseVersion.parse("1.0.3+remote")));
        assertTrue(ReleaseVersion.parse("1.0.3").compareTo(ReleaseVersion.parse("1.0.1")) > 0);
    }

    @Test
    void rejectsAmbiguousVersions() {
        for (String value : new String[] { "1.0", "v1.0.3", "01.0.3", "1.0.0-01", "1.0.3\n" }) {
            assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(value));
        }
    }
}
