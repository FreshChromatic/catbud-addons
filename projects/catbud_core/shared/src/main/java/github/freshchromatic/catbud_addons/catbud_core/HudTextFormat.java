package github.freshchromatic.catbud_addons.catbud_core;

import java.util.Locale;

public record HudTextFormat(boolean underline, LetterCase letterCase, String prefix, String suffix, boolean brackets,
    Weight weight) {

    public enum Weight {
        REGULAR, MEDIUM, SEMIBOLD, HEAVY
    }

    public HudTextFormat(boolean underline, LetterCase letterCase, String prefix, String suffix, boolean brackets) {
        this(underline, letterCase, prefix, suffix, brackets, Weight.REGULAR);
    }

    public enum LetterCase {
        ORIGINAL, UPPER, LOWER
    }

    public HudTextFormat {
        weight = weight == null ? Weight.REGULAR : weight;
        letterCase = letterCase == null ? LetterCase.ORIGINAL : letterCase;
        prefix = prefix == null ? "" : prefix.substring(0, Math.min(64, prefix.length()));
        suffix = suffix == null ? "" : suffix.substring(0, Math.min(64, suffix.length()));
    }

    public static HudTextFormat defaults() {
        return new HudTextFormat(false, null, "", "", false);
    }

    public String apply(String text) {
        String result = prefix + text + suffix;
        result = switch (letterCase) {
            case ORIGINAL -> result;
            case UPPER -> result.toUpperCase(Locale.ROOT);
            case LOWER -> result.toLowerCase(Locale.ROOT);
        };
        return brackets ? "[" + result + "]" : result;
    }
}
