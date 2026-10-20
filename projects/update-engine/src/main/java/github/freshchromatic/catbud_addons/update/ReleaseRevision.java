package github.freshchromatic.catbud_addons.update;

public record ReleaseRevision(String version, int revision) {

    public ReleaseRevision {
        if (version == null || !version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
            || revision < 1) {
            throw new IllegalArgumentException("Invalid release revision");
        }
    }

    public static ReleaseRevision parse(String version, String tag) {
        String base = "v" + version;
        if (base.equals(tag)) {
            return new ReleaseRevision(version, 1);
        }
        if (tag == null || !tag.startsWith(base + "-r")) {
            throw new IllegalArgumentException("Release tag does not match version");
        }
        String suffix = tag.substring(base.length() + 2);
        if (!suffix.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid release revision suffix");
        }
        int revision = Integer.parseInt(suffix);
        if (revision < 2) {
            throw new IllegalArgumentException("The first revision uses the base version tag");
        }
        return new ReleaseRevision(version, revision);
    }

    public static ReleaseRevision installed(String version, String module, String minecraft, String file) {
        String prefix = "catbud-" + module + "-" + minecraft + "-";
        if (!file.startsWith(prefix) || !file.endsWith(".jar")) {
            return new ReleaseRevision(version, 1);
        }
        return parse(version, "v" + file.substring(prefix.length(), file.length() - 4));
    }

    public String tag() {
        return "v" + key();
    }

    public String key() {
        return version + (revision == 1 ? "" : "-r" + revision);
    }

    public String file(String module, String minecraft) {
        return "catbud-" + module + "-" + minecraft + "-" + key() + ".jar";
    }

    public String displayVersion() {
        return version + (revision == 1 ? "" : " (r" + revision + ")");
    }
}
