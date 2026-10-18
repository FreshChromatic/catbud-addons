package github.freshchromatic.catbud_addons.update;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;

public record ReleaseVersion(BigInteger major, BigInteger minor, BigInteger patch, List<String> prerelease) implements
    Comparable<ReleaseVersion> {

    private static final Pattern FORMAT = Pattern.compile(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    public ReleaseVersion {
        prerelease = List.copyOf(prerelease);
    }

    public static ReleaseVersion parse(String value) {
        if (value == null || value.length() > 128) {
            throw new IllegalArgumentException("Invalid release version");
        }
        var match = FORMAT.matcher(value);
        if (!match.matches()) {
            throw new IllegalArgumentException("Invalid release version: " + value);
        }
        List<String> suffix = match.group(4) == null ? List.of() : List.of(match.group(4).split("\\."));
        for (String part : suffix) {
            if (part.matches("[0-9]+") && part.length() > 1 && part.startsWith("0")) {
                throw new IllegalArgumentException("Invalid numeric prerelease identifier");
            }
        }
        return new ReleaseVersion(new BigInteger(match.group(1)), new BigInteger(match.group(2)), new BigInteger(match
            .group(3)), suffix);
    }

    public boolean stable() {
        return prerelease.isEmpty();
    }

    @Override
    public int compareTo(ReleaseVersion other) {
        int result = major.compareTo(other.major);
        if (result == 0) {
            result = minor.compareTo(other.minor);
        }
        if (result == 0) {
            result = patch.compareTo(other.patch);
        }
        if (result != 0) {
            return result;
        }
        if (stable() || other.stable()) {
            return Boolean.compare(stable(), other.stable());
        }
        for (int i = 0; i < Math.min(prerelease.size(), other.prerelease.size()); i++) {
            String left = prerelease.get(i);
            String right = other.prerelease.get(i);
            boolean leftNumber = left.matches("[0-9]+");
            boolean rightNumber = right.matches("[0-9]+");
            if (leftNumber && rightNumber) {
                result = new BigInteger(left).compareTo(new BigInteger(right));
            } else if (leftNumber != rightNumber) {
                result = leftNumber ? -1 : 1;
            } else {
                result = left.compareTo(right);
            }
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }
}
