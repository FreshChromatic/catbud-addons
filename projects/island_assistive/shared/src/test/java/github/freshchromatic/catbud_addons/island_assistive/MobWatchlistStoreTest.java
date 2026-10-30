package github.freshchromatic.catbud_addons.island_assistive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class MobWatchlistStoreTest {

    @TempDir Path temporary;

    @Test
    void absentFileUsesOnlyZombieVillagersIncludingNamedVillagers() {
        var store = store(temporary.resolve("watchlist.json"));
        assertFalse(store.loadFailed());
        assertEquals(MobWatchlistStore.DEFAULT_RULES, store.rules());
        assertTrue(new MobMatcher(store.rules()).matches(MobAlertSessionTest.mob(1, 256)));
        assertEquals("minecraft:zombie_villager", store.rules().getFirst().entityType());
        assertEquals(MobWatchRule.NameMatch.ANY, store.rules().getFirst().nameMatch());
    }

    @Test
    void savedEmptyListStaysEmptyAfterReload() throws Exception {
        Path path = temporary.resolve("watchlist.json");
        store(path).save(List.of());
        assertEquals(List.of(), store(path).rules());
    }

    @Test
    void savesTypedRulesAndReloadsNamesAndModes() throws Exception {
        Path path = temporary.resolve("watchlist.json");
        var rules = List.of(new MobWatchRule("", MobWatchRule.NameMatch.CONTAINS, "稀有 村民"));
        store(path).save(rules);
        assertEquals(rules, store(path).rules());
    }

    @Test
    void corruptFileIsReportedAndBackedUpBeforeReplacement() throws Exception {
        Path path = temporary.resolve("watchlist.json");
        Files.writeString(path, "{broken");
        var store = store(path);
        assertTrue(store.loadFailed());
        assertEquals("{broken", Files.readString(path));
        store.save(List.of());
        assertFalse(store.loadFailed());
        try (var files = Files.list(temporary)) {
            Path backup = files.filter(file -> file.getFileName().toString().startsWith("watchlist.json.invalid-"))
                .findFirst().orElseThrow();
            assertEquals("{broken", Files.readString(backup));
        }
    }

    @Test
    void rejectsInvalidTypesAndPreservesLastSavedRules() throws Exception {
        Path path = temporary.resolve("watchlist.json");
        var store = store(path);
        store.save(MobWatchlistStore.DEFAULT_RULES);
        assertThrows(IllegalArgumentException.class, () -> store.save(List.of(new MobWatchRule("minecraft:not_a_mob",
            MobWatchRule.NameMatch.ANY, ""))));
        assertEquals(MobWatchlistStore.DEFAULT_RULES, store.rules());
        assertEquals(MobWatchlistStore.DEFAULT_RULES, store(path).rules());
    }

    @Test
    void failedWriteDoesNotPublishDraft() throws Exception {
        Path parent = temporary.resolve("not-a-directory");
        Files.writeString(parent, "occupied");
        var store = store(parent.resolve("watchlist.json"));
        assertThrows(IOException.class, () -> store.save(List.of()));
        assertEquals(MobWatchlistStore.DEFAULT_RULES, store.rules());
    }

    @Test
    void invalidDocumentShapeAndVersionFallBackWithWarning() throws Exception {
        Path path = temporary.resolve("watchlist.json");
        for (String json : List.of("null", "[]", "{}", "{\"version\":2,\"rules\":[]}",
            "{\"version\":1,\"rules\":[{\"entityType\":\"minecraft:zombie_villager\"}]}")) {
            Files.writeString(path, json);
            assertTrue(store(path).loadFailed(), json);
        }
    }

    @Test
    void ruleMatchesNormalizedNamesAndRequiresBothTypeAndName() {
        var rule = new MobWatchRule("minecraft:zombie_villager", MobWatchRule.NameMatch.CONTAINS, "§a稀有 村民");
        var mob = new MobSnapshot(new UUID(0, 1), "minecraft:zombie_villager", "§6超級 稀有\t村民", "display", 0, 64, 0, 10);
        assertTrue(rule.matches(mob));
        assertFalse(new MobWatchRule("minecraft:zombie", rule.nameMatch(), rule.name()).matches(mob));
        assertFalse(new MobWatchRule(rule.entityType(), MobWatchRule.NameMatch.EXACT, rule.name()).matches(mob));
        assertThrows(IllegalArgumentException.class, () -> new MobWatchRule("", MobWatchRule.NameMatch.ANY, ""));
        assertThrows(IllegalArgumentException.class, () -> new MobWatchRule("minecraft:zombie_villager",
            MobWatchRule.NameMatch.EXACT, " "));
        assertThrows(IllegalArgumentException.class, () -> new MobWatchRule("bad id", MobWatchRule.NameMatch.ANY, ""));
    }

    private static MobWatchlistStore store(Path path) {
        return new MobWatchlistStore(path, type -> type.equals("minecraft:zombie_villager"));
    }
}
