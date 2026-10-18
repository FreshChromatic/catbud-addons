package github.freshchromatic.catbud_addons.magic_tower;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

final class TowerCoinInventory {

    record Snapshot(int count, List<Integer> capacities, boolean estimated) {

        Snapshot {
            capacities = List.copyOf(capacities);
        }
    }

    private Snapshot        snapshot = new Snapshot(0, List.of(0), true);
    private List<ItemStack> previous = List.of();

    Snapshot snapshot() {
        return snapshot;
    }

    void reset() {
        previous = List.of();
        snapshot = new Snapshot(0, List.of(0), true);
    }

    void tick(Container inventory) {
        List<ItemStack> stacks = new ArrayList<>(36);
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            stacks.add(inventory.getItem(slot));
        }
        boolean unchanged = stacks.size() == previous.size();
        for (int slot = 0; unchanged && slot < stacks.size(); slot++) {
            unchanged = ItemStack.matches(stacks.get(slot), previous.get(slot));
        }
        if (!unchanged) {
            snapshot = count(inventory, stacks);
            previous = stacks.stream().map(ItemStack::copy).toList();
        }
    }

    static Snapshot count(Container inventory, List<ItemStack> stacks) {
        int count = 0;
        List<ItemStack> variants = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (TowerCoinMatcher.matches(stack)) {
                count += stack.getCount();
                if (variants.stream().noneMatch(variant -> ItemStack.isSameItemSameComponents(variant, stack))) {
                    variants.add(stack);
                }
            }
        }
        if (variants.isEmpty()) {
            int empty = (int) stacks.stream().filter(ItemStack::isEmpty).count();
            return new Snapshot(0, List.of(empty * Math.min(16, inventory.getMaxStackSize())), true);
        }
        List<Integer> capacities = new ArrayList<>();
        for (ItemStack variant : variants) {
            int capacity = 0;
            for (int slot = 0; slot < stacks.size(); slot++) {
                ItemStack stack = stacks.get(slot);
                if (!inventory.canPlaceItem(slot, variant)) {
                    continue;
                }
                int limit = inventory.getMaxStackSize(variant);
                if (stack.isEmpty()) {
                    capacity += Math.min(limit, variant.getMaxStackSize());
                } else if (ItemStack.isSameItemSameComponents(variant, stack)) {
                    capacity += Math.max(0, Math.min(limit, stack.getMaxStackSize()) - stack.getCount());
                }
            }
            capacities.add(capacity);
        }
        return new Snapshot(count, capacities, false);
    }
}
