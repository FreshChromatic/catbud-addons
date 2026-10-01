package github.freshchromatic.catbud_addons.magic_tower;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

final class TextKeys {
    private TextKeys() {}

    static boolean contains(Component component, String key) {
        if (component == null) return false;
        if (component.getContents() instanceof TranslatableContents translatable) {
            if (key.equals(translatable.getKey())) return true;
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component nested && contains(nested, key)) return true;
            }
        }
        for (Component sibling : component.getSiblings()) {
            if (contains(sibling, key)) return true;
        }
        return false;
    }
}
