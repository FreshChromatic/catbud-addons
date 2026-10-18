package github.freshchromatic.catbud_addons.magic_tower;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

final class TowerCoinMatcher {

    private static final Identifier MODEL = Identifier.fromNamespaceAndPath("minecraft", "custom/coin_of_tower");

    private TowerCoinMatcher() {
    }

    static boolean matches(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.CLOCK) || !MODEL.equals(stack.get(DataComponents.ITEM_MODEL))) {
            return false;
        }
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        return custom != null && custom.copyTag().getCompoundOrEmpty("PublicBukkitValues").getStringOr("UniqueKey", "")
            .equals("coin_of_tower");
    }
}
