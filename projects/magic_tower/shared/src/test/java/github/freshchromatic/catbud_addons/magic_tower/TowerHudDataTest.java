package github.freshchromatic.catbud_addons.magic_tower;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;

class TowerHudDataTest {

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try {
            var initializers = net.minecraft.core.registries.BuiltInRegistries.class.getField(
                "DATA_COMPONENT_INITIALIZERS").get(null);
            var lookup = java.util.Arrays.stream(net.minecraft.data.registries.VanillaRegistries.class.getMethods())
                .filter(method -> method.getName().equals("createLookup") || method.getName().equals(
                    "createWorldLookup")).filter(method -> method.getParameterCount() == 0).findFirst().orElseThrow()
                .invoke(null);
            var pending = (List<?>) initializers.getClass().getMethod("build",
                net.minecraft.core.HolderLookup.Provider.class).invoke(initializers, lookup);
            var apply = Class.forName("net.minecraft.core.component.DataComponentInitializers$PendingComponents")
                .getMethod("apply");
            for (Object entry : pending) {
                apply.invoke(entry);
            }
        } catch (NoSuchFieldException legacy) {
            assertNotNull(Items.CLOCK);
        }
    }

    static ItemStack coin(int count, int source) {
        ItemStack stack = new ItemStack(Items.CLOCK, count);
        stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("minecraft", "custom/coin_of_tower"));
        stack.set(DataComponents.MAX_STACK_SIZE, 16);
        CompoundTag values = new CompoundTag();
        values.putString("UniqueKey", "coin_of_tower");
        values.putIntArray("Source", new int[] { source, 2, 3, 4 });
        CompoundTag custom = new CompoundTag();
        custom.put("PublicBukkitValues", values);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        return stack;
    }

    private static TowerCoinInventory.Snapshot count(SimpleContainer inventory) {
        return TowerCoinInventory.count(inventory, IntStream.range(0, 36).mapToObj(inventory::getItem).toList());
    }

    @Test
    void countsPartialStacksAndEmptySlotsWithoutCountingOtherItems() {
        var inventory = new SimpleContainer(36);
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, new ItemStack(Items.STONE));
        }
        inventory.setItem(0, coin(10, 1));
        inventory.setItem(1, coin(16, 1));
        inventory.setItem(2, coin(5, 1));
        inventory.setItem(3, ItemStack.EMPTY);
        inventory.setItem(4, ItemStack.EMPTY);
        var result = count(inventory);
        assertEquals(31, result.count());
        assertEquals(List.of(49), result.capacities());
        assertFalse(result.estimated());
        inventory.setItem(3, new ItemStack(Items.CLOCK));
        assertEquals(List.of(33), count(inventory).capacities());
    }

    @Test
    void distinctSourceVariantsDoNotSharePartialStackCapacityOrDoubleCountEmptySlots() {
        var inventory = new SimpleContainer(36);
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, new ItemStack(Items.STONE));
        }
        inventory.setItem(0, coin(10, 1));
        inventory.setItem(1, coin(5, 2));
        inventory.setItem(2, ItemStack.EMPTY);
        assertEquals(15, count(inventory).count());
        assertEquals(List.of(22, 27), count(inventory).capacities());
        inventory.setItem(2, new ItemStack(Items.STONE));
        assertEquals(List.of(6, 11), count(inventory).capacities());
    }

    @Test
    void identityRequiresCustomDataAndCapacityWithoutSampleIsEstimated() {
        ItemStack fake = new ItemStack(Items.CLOCK);
        fake.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("minecraft", "custom/coin_of_tower"));
        fake.set(DataComponents.ITEM_NAME, Component.translatable("items.coin_of_tower"));
        assertFalse(TowerCoinMatcher.matches(fake));
        assertFalse(TowerCoinMatcher.matches(new ItemStack(Items.CLOCK)));
        assertTrue(TowerCoinMatcher.matches(coin(1, 1)));
        var inventory = new SimpleContainer(36);
        assertEquals(List.of(576), count(inventory).capacities());
        assertTrue(count(inventory).estimated());
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, fake.copy());
        }
        assertEquals(List.of(0), count(inventory).capacities());
        inventory.setItem(0, coin(16, 1));
        assertEquals(List.of(0), count(inventory).capacities());
    }

    @Test
    void parserReadsNestedArgumentsAndExactLocalizedTemplates() {
        Component nested = Component.translatable("wrapper", Component.translatable(TowerAnnouncementParser.FLOOR,
            Component.literal("12")));
        assertEquals(OptionalInt.of(12), TowerAnnouncementParser.parse(nested).floor());
        assertEquals(OptionalInt.of(7), TowerAnnouncementParser.parse(Component.literal("§a第 7 層"), "第 %s 層", "有", "無")
            .floor());
        assertTrue(TowerAnnouncementParser.parse(Component.literal("聊天第 7 層"), "第 %s 層", "有", "無").floor().isEmpty());
        assertTrue(TowerAnnouncementParser.parse(Component.translatable(TowerAnnouncementParser.FLOOR, "12x")).floor()
            .isEmpty());
        assertTrue(TowerAnnouncementParser.parse(Component.translatable(TowerAnnouncementParser.FLOOR, "2147483648"))
            .floor().isEmpty());
    }

    private static TowerAnnouncementParser.Announcement floor(int number) {
        return new TowerAnnouncementParser.Announcement(OptionalInt.of(number), TowerSession.EvacuationStatus.UNKNOWN);
    }

    private static TowerAnnouncementParser.Announcement evacuation(TowerSession.EvacuationStatus status) {
        return new TowerAnnouncementParser.Announcement(OptionalInt.empty(), status);
    }

    @Test
    void floorStateHandlesEitherAnnouncementOrderAndLateWaitingBar() {
        var state = new TowerFloorState();
        state.announce(evacuation(TowerSession.EvacuationStatus.PRESENT), 100);
        state.announce(floor(1), 200);
        state.waiting(250);
        state.entered(300);
        assertEquals(OptionalInt.of(1), state.floor());
        assertEquals(TowerSession.EvacuationStatus.PRESENT, state.evacuation());
        state.waiting(3000);
        assertTrue(state.floor().isEmpty());
        assertEquals(TowerSession.EvacuationStatus.UNKNOWN, state.evacuation());
        state.announce(floor(2), 3100);
        state.announce(evacuation(TowerSession.EvacuationStatus.ABSENT), 3200);
        state.announce(floor(2), 3300);
        assertEquals(OptionalInt.of(2), state.floor());
        assertEquals(TowerSession.EvacuationStatus.ABSENT, state.evacuation());
        state.announce(floor(3), 3400);
        assertEquals(TowerSession.EvacuationStatus.UNKNOWN, state.evacuation());
        state.reset();
        assertTrue(state.floor().isEmpty());
        assertEquals(TowerSession.EvacuationStatus.UNKNOWN, state.evacuation());
    }

    @Test
    void repeatedWaitingSignalsKeepPendingAnnouncementAndDoNotStartAnotherTransition() {
        var state = new TowerFloorState();
        assertTrue(state.waiting(1000));
        state.announce(evacuation(TowerSession.EvacuationStatus.PRESENT), 1200);
        assertFalse(state.waiting(1300));
        assertFalse(state.waiting(3000));
        state.announce(floor(4), 3100);
        assertEquals(OptionalInt.of(4), state.floor());
        assertEquals(TowerSession.EvacuationStatus.PRESENT, state.evacuation());
    }

    @Test
    void automaticAndForcedRunningShareActivityAndAnnouncements() throws Exception {
        var session = new TowerSession();
        session.onMessage(Component.translatable(TowerAnnouncementParser.FLOOR, 12), "Chat");
        assertTrue(session.floor().isEmpty());
        var phase = TowerSession.class.getDeclaredField("phase");
        phase.setAccessible(true);
        phase.set(session, java.util.Arrays.stream(phase.getType().getEnumConstants()).filter(e -> e.toString().equals(
            "ACTIVE")).findFirst().orElseThrow());
        assertTrue(session.isActive());
        phase.set(session, java.util.Arrays.stream(phase.getType().getEnumConstants()).filter(e -> e.toString().equals(
            "IDLE")).findFirst().orElseThrow());
        assertFalse(session.isActive());
        var mode = TowerSession.class.getDeclaredField("manualMode");
        mode.setAccessible(true);
        mode.set(session, java.util.Arrays.stream(mode.getType().getEnumConstants()).filter(e -> e.toString().equals(
            "FORCED")).findFirst().orElseThrow());
        assertTrue(session.isActive());
        assertTrue(session.floor().isEmpty());
        assertEquals(TowerSession.EvacuationStatus.UNKNOWN, session.evacuationStatus());
        session.onMessage(Component.translatable(TowerAnnouncementParser.FLOOR, 12), "Title");
        session.onMessage(Component.translatable("plugins.magic_tower.evacuation_room_appeared"), "Subtitle");
        assertEquals(OptionalInt.of(12), session.floor());
        assertEquals(TowerSession.EvacuationStatus.PRESENT, session.evacuationStatus());
        session.onMessage(Component.translatable("plugins.magic_tower.no_evacuation_room_appeared"), "Chat");
        assertEquals(TowerSession.EvacuationStatus.PRESENT, session.evacuationStatus());
        session.forceStop();
        assertFalse(session.isActive());
        assertTrue(session.floor().isEmpty());
        assertEquals(TowerSession.EvacuationStatus.UNKNOWN, session.evacuationStatus());
        session.resetConnection();
        assertFalse(session.isActive());
    }
}
