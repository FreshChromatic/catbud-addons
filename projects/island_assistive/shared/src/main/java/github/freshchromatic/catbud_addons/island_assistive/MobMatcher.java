package github.freshchromatic.catbud_addons.island_assistive;

import java.util.List;

public record MobMatcher(List<MobWatchRule> rules) {

    public MobMatcher {
        rules = List.copyOf(rules);
    }

    public boolean matches(MobSnapshot mob) {
        return rules.stream().anyMatch(rule -> rule.matches(mob));
    }
}
