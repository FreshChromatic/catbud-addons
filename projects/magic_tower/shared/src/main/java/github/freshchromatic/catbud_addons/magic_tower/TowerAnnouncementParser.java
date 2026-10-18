package github.freshchromatic.catbud_addons.magic_tower;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.OptionalInt;
import java.util.regex.Pattern;

final class TowerAnnouncementParser {

    static final String          FLOOR      = "plugins.magic_tower.layer_is";
    private static final String  PRESENT    = "plugins.magic_tower.evacuation_room_appeared";
    private static final String  ABSENT     = "plugins.magic_tower.no_evacuation_room_appeared";
    private static final Pattern FORMATTING = Pattern.compile("§[0-9A-FK-ORa-fk-or]");

    record Announcement(OptionalInt floor, TowerSession.EvacuationStatus evacuation) {}

    private TowerAnnouncementParser() {
    }

    static Announcement parse(Component text) {
        return parse(text, Language.getInstance().getOrDefault(FLOOR), Language.getInstance().getOrDefault(PRESENT),
            Language.getInstance().getOrDefault(ABSENT));
    }

    static Announcement parse(Component text, String floorTemplate, String present, String absent) {
        TowerSession.EvacuationStatus evacuation = TowerSession.EvacuationStatus.UNKNOWN;
        if (matches(text, PRESENT, present)) {
            evacuation = TowerSession.EvacuationStatus.PRESENT;
        } else if (matches(text, ABSENT, absent)) {
            evacuation = TowerSession.EvacuationStatus.ABSENT;
        }
        OptionalInt floor = floorArgument(text);
        if (floor.isEmpty() && text != null && !floorTemplate.equals(FLOOR)) {
            String template = clean(floorTemplate);
            int marker = template.indexOf("%s");
            int markerLength = 2;
            if (marker < 0) {
                marker = template.indexOf("%1$s");
                markerLength = 4;
            }
            if (marker >= 0) {
                String regex = Pattern.quote(template.substring(0, marker)) + "([+-]?\\d+)" + Pattern.quote(template
                    .substring(marker + markerLength));
                var matcher = Pattern.compile(regex).matcher(clean(text.getString()));
                if (matcher.matches()) {
                    floor = integer(matcher.group(1));
                }
            }
        }
        return new Announcement(floor, evacuation);
    }

    private static OptionalInt floorArgument(Component text) {
        if (text == null) {
            return OptionalInt.empty();
        }
        if (text.getContents() instanceof TranslatableContents translation) {
            if (FLOOR.equals(translation.getKey()) && translation.getArgs().length == 1) {
                Object argument = translation.getArgs()[0];
                return integer(argument instanceof Component nested ? nested.getString() : String.valueOf(argument));
            }
            for (Object argument : translation.getArgs()) {
                if (argument instanceof Component nested) {
                    OptionalInt result = floorArgument(nested);
                    if (result.isPresent()) {
                        return result;
                    }
                }
            }
        }
        for (Component sibling : text.getSiblings()) {
            OptionalInt result = floorArgument(sibling);
            if (result.isPresent()) {
                return result;
            }
        }
        return OptionalInt.empty();
    }

    private static OptionalInt integer(String value) {
        String cleaned = clean(value);
        if (!cleaned.matches("[+-]?\\d+")) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(cleaned));
        } catch (NumberFormatException error) {
            return OptionalInt.empty();
        }
    }

    private static boolean matches(Component text, String key, String localized) {
        return TextKeys.contains(text, key) || text != null && (clean(text.getString()).equals(key) || !localized
            .equals(key) && clean(text.getString()).equals(clean(localized)));
    }

    private static String clean(String text) {
        return FORMATTING.matcher(text).replaceAll("").strip();
    }
}
