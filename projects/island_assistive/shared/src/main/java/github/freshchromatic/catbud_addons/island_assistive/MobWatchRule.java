package github.freshchromatic.catbud_addons.island_assistive;

import java.util.Objects;
import java.util.regex.Pattern;

public record MobWatchRule(String entityType, NameMatch nameMatch, String name) {

    private static final Pattern STYLE = Pattern.compile("§[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public enum NameMatch {
        ANY, EXACT, CONTAINS
    }

    public MobWatchRule {
        entityType = Objects.requireNonNull(entityType, "entityType").strip();
        nameMatch = Objects.requireNonNull(nameMatch, "nameMatch");
        name = normalize(Objects.requireNonNull(name, "name"));
        if (!entityType.isEmpty() && !entityType.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("island.watchlist.invalid_type");
        }
        if (nameMatch == NameMatch.ANY) {
            name = "";
        }
        boolean missingTarget = entityType.isEmpty() && nameMatch == NameMatch.ANY;
        boolean missingName = nameMatch != NameMatch.ANY && name.isEmpty();
        if (missingTarget || missingName) {
            throw new IllegalArgumentException("island.watchlist.missing_target");
        }
    }

    public boolean matches(MobSnapshot mob) {
        if (!entityType.isEmpty() && !entityType.equals(mob.entityType())) {
            return false;
        }
        String customName = normalize(mob.customName());
        return switch (nameMatch) {
            case ANY -> true;
            case EXACT -> customName.equals(name);
            case CONTAINS -> customName.contains(name);
        };
    }

    public static String normalize(String text) {
        return WHITESPACE.matcher(STYLE.matcher(text).replaceAll("")).replaceAll(" ").strip();
    }
}
