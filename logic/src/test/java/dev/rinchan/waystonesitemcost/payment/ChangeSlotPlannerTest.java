package dev.rinchan.waystonesitemcost.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rinchan.waystonesitemcost.payment.ChangeSlotPlanner.SimSlot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class ChangeSlotPlannerTest {
    private static final Map<String, Integer> EMERALD_CHAIN = Map.of(
        "minecraft:emerald", 1,
        "minecraft:emerald_block", 9
    );

    private static final Map<String, Integer> IRON_CHAIN = Map.of(
        "minecraft:iron_nugget", 1,
        "minecraft:iron_ingot", 9,
        "minecraft:iron_block", 81
    );

    private static List<SimSlot> fullInventoryWithFiller() {
        var inv = new ArrayList<SimSlot>(41);
        for (int i = 0; i < 36; i++) {
            inv.add(SimSlot.item("minecraft:cobblestone", 64));
        }
        // Armor 36..39 and offhand 40 empty — must NEVER receive change!
        for (int i = 36; i < 41; i++) {
            inv.add(SimSlot.empty());
        }
        return inv;
    }

    @Test
    void distinguishesInventoryFullForChangeFromInsufficientFunds() {
        var inv = fullInventoryWithFiller();
        // Put 2 emerald blocks in slot 0 (18 emeralds value); cost is 2 emeralds -> needs 1 block, 7 emerald change.
        // Slot 0 still has 1 emerald block left, so 0..35 has ZERO free slots! Armor/offhand are empty.
        inv.set(0, SimSlot.item("minecraft:emerald_block", 2));

        var outcome = ChangeSlotPlanner.simulateCharge(inv, "minecraft:emerald", 2, EMERALD_CHAIN, true, Map.of());
        assertEquals(PaymentStatus.INVENTORY_FULL_FOR_CHANGE, outcome.status());
        assertEquals(1, outcome.slotsNeededForChange());
        // Armor and offhand remain untouched
        for (int i = 36; i < 41; i++) {
            assertTrue(inv.get(i).isEmpty());
        }
        // Inventory was NOT partially mutated
        assertEquals(2, inv.get(0).count);

        // Now free 1 slot in 0..35 -> succeeds!
        inv.set(35, SimSlot.empty());
        var success = ChangeSlotPlanner.simulateCharge(inv, "minecraft:emerald", 2, EMERALD_CHAIN, true, Map.of());
        assertEquals(PaymentStatus.SUCCESS, success.status());
        assertEquals(1, inv.get(0).count);
        assertEquals("minecraft:emerald", inv.get(35).itemId);
        assertEquals(7, inv.get(35).count);
    }

    @Test
    void computesMultipleMissingSlotsForMultiDenominationChange() {
        var inv = fullInventoryWithFiller();
        // 2 iron blocks in slot 0; cost = 1 iron nugget -> 1 block deducted (81), change = 80 nuggets = 8 ingots + 8 nuggets -> needs 2 slots!
        inv.set(0, SimSlot.item("minecraft:iron_block", 2));

        var outcome = ChangeSlotPlanner.simulateCharge(inv, "minecraft:iron_nugget", 1, IRON_CHAIN, true, Map.of());
        assertEquals(PaymentStatus.INVENTORY_FULL_FOR_CHANGE, outcome.status());
        assertEquals(2, outcome.slotsNeededForChange());
    }

    @Test
    void deductsFromNestedBundleAndShulkerBoxWhenEnabledAndPlacesChangeInMainInventoryOnly() {
        var inv = new ArrayList<SimSlot>(41);
        for (int i = 0; i < 41; i++) inv.add(SimSlot.empty());
        // Bundle in slot 0 holding 1 emerald_block
        inv.set(0, SimSlot.container("minecraft:bundle", List.of(SimSlot.item("minecraft:emerald_block", 1))));
        // Shulker box in slot 1 holding 2 emeralds
        inv.set(1, SimSlot.container("minecraft:shulker_box", List.of(SimSlot.item("minecraft:emerald", 2))));

        // Disabled -> insufficient funds
        var disabled = ChangeSlotPlanner.simulateCharge(inv, "minecraft:emerald", 5, EMERALD_CHAIN, false, Map.of());
        assertEquals(PaymentStatus.INSUFFICIENT_FUNDS, disabled.status());

        // Enabled -> takes 2 emeralds from shulker + 1 emerald_block from bundle = 11 value, returns 6 emeralds change to slot 2!
        var enabled = ChangeSlotPlanner.simulateCharge(inv, "minecraft:emerald", 5, EMERALD_CHAIN, true, Map.of());
        assertEquals(PaymentStatus.SUCCESS, enabled.status());
        assertTrue(enabled.usedNestedContainer());
        assertTrue(enabled.usedConversion());
        assertTrue(inv.get(0).nested.get(0).isEmpty());
        assertTrue(inv.get(1).nested.get(0).isEmpty());
        assertEquals("minecraft:emerald", inv.get(2).itemId);
        assertEquals(6, inv.get(2).count);
    }

    @Test
    void fuzzInvariantConservationAndSlotSafetyAcross300Rounds() {
        var random = new Random(20261120L);
        String[] items = {"minecraft:iron_nugget", "minecraft:iron_ingot", "minecraft:iron_block"};

        for (int round = 0; round < 300; round++) {
            var inv = new ArrayList<SimSlot>(41);
            for (int slot = 0; slot < 41; slot++) {
                int roll = random.nextInt(100);
                if (slot >= 36 && slot <= 39) {
                    // Armor slots: either empty or armor
                    inv.add(roll < 50 ? SimSlot.empty() : SimSlot.item("minecraft:diamond_chestplate", 1, 1, false));
                } else if (roll < 35) {
                    inv.add(SimSlot.empty());
                } else if (roll < 65) {
                    inv.add(SimSlot.item("minecraft:stone", 1 + random.nextInt(64)));
                } else if (roll < 90) {
                    String item = items[random.nextInt(items.length)];
                    inv.add(SimSlot.item(item, 1 + random.nextInt(16)));
                } else {
                    // Nested shulker/bundle
                    var contents = new ArrayList<SimSlot>();
                    int n = 1 + random.nextInt(3);
                    for (int k = 0; k < n; k++) {
                        contents.add(SimSlot.item(items[random.nextInt(items.length)], 1 + random.nextInt(10)));
                    }
                    inv.add(SimSlot.container("minecraft:shulker_box", contents));
                }
            }

            boolean searchNested = random.nextBoolean();
            String currency = items[random.nextInt(items.length)];
            int costCount = 1 + random.nextInt(25);
            long requiredUnits = (long) costCount * IRON_CHAIN.get(currency);

            long beforeTotal = totalValue(inv, IRON_CHAIN, searchNested);
            var armorAndOffhandBefore = new ArrayList<SimSlot>();
            for (int i = 36; i < 41; i++) armorAndOffhandBefore.add(inv.get(i).copy());

            var snapshotForRetry = new ArrayList<SimSlot>(41);
            for (var s : inv) snapshotForRetry.add(s.copy());

            var outcome = ChangeSlotPlanner.simulateCharge(inv, currency, costCount, IRON_CHAIN, searchNested, Map.of());

            if (outcome.status() == PaymentStatus.SUCCESS) {
                assertTrue(beforeTotal >= requiredUnits, "Round " + round + ": succeeded despite insufficient funds");
                long afterTotal = totalValue(inv, IRON_CHAIN, searchNested);
                assertEquals(beforeTotal - requiredUnits, afterTotal, "Round " + round + ": total value conservation violated");

                long deductedValue = 0;
                for (var c : outcome.consumed()) deductedValue += (long) c.count() * IRON_CHAIN.get(c.itemId());
                long changeValue = 0;
                for (var ch : outcome.change()) changeValue += (long) ch.count() * IRON_CHAIN.get(ch.itemId());
                assertEquals(requiredUnits, deductedValue - changeValue, "Round " + round + ": deducted - change != required");

                // Armor 36..39 must NEVER be modified; offhand 40 may only shrink (never grow with change!)
                for (int i = 36; i <= 39; i++) {
                    assertEquals(armorAndOffhandBefore.get(i - 36).itemId, inv.get(i).itemId, "Armor slot polluted");
                    assertEquals(armorAndOffhandBefore.get(i - 36).count, inv.get(i).count, "Armor slot count changed");
                }
                assertTrue(inv.get(40).count <= armorAndOffhandBefore.get(4).count, "Offhand received change!");
            } else if (outcome.status() == PaymentStatus.INSUFFICIENT_FUNDS) {
                assertTrue(beforeTotal < requiredUnits, "Round " + round + ": reported insufficient despite enough funds");
            } else if (outcome.status() == PaymentStatus.INVENTORY_FULL_FOR_CHANGE) {
                assertTrue(beforeTotal >= requiredUnits, "Round " + round + ": reported full inventory despite insufficient funds");
                assertTrue(outcome.slotsNeededForChange() >= 1);
                // Verify freeing exactly slotsNeededForChange non-currency filler slots in 0..35 makes it succeed!
                int freed = 0;
                for (int i = 0; i < 36 && freed < outcome.slotsNeededForChange(); i++) {
                    var s = snapshotForRetry.get(i);
                    if (!s.isEmpty() && "minecraft:stone".equals(s.itemId)) {
                        snapshotForRetry.set(i, SimSlot.empty());
                        freed++;
                    }
                }
                if (freed == outcome.slotsNeededForChange()) {
                    var retry = ChangeSlotPlanner.simulateCharge(snapshotForRetry, currency, costCount, IRON_CHAIN, searchNested, Map.of());
                    assertEquals(PaymentStatus.SUCCESS, retry.status(),
                        "Round " + round + ": freeing exact slotsNeededForChange should succeed");
                }
            } else {
                assertFalse(true, "Unexpected status");
            }
        }
    }

    private static long totalValue(List<SimSlot> inv, Map<String, Integer> chain, boolean includeNested) {
        long sum = 0;
        for (var s : inv) {
            if (s.isEmpty()) continue;
            if (s.plain) {
                sum += (long) s.count * chain.getOrDefault(s.itemId, 0);
            }
            if (includeNested && s.nested != null) {
                for (var c : s.nested) {
                    if (!c.isEmpty() && c.plain) {
                        sum += (long) c.count * chain.getOrDefault(c.itemId, 0);
                    }
                }
            }
        }
        return sum;
    }
}
