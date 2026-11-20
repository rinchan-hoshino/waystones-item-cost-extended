package dev.rinchan.waystonesitemcost.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rinchan.waystonesitemcost.payment.ChangeSlotPlanner.SimSlot;
import dev.rinchan.waystonesitemcost.payment.CraftingDenominations.Recipe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Property-based boundary bombardment (>= 1,000 fixed-seed rounds) verifying:
 * 1. Total value conservation: beforeTotal - afterTotal == requiredUnits on every SUCCESS.
 * 2. Zero money swallowing: inventory is byte-for-byte unchanged on INVENTORY_FULL_FOR_CHANGE
 *    and INSUFFICIENT_FUNDS, and freeing exact slotsNeededForChange unblocks payment.
 * 3. Arbitrary reversible crafting denomination graphs (1/9/81 and non-standard ratios) with
 *    mixed main inventory, Shulker Boxes, Bundles, armor, offhand, and non-plain items.
 */
final class PaymentConservationPropertyTest {
    private static final int PAYMENT_ROUNDS = 1500;
    private static final int GRAPH_ROUNDS = 1000;
    private static final long PAYMENT_SEED = 0x57494345_2026L;
    private static final long GRAPH_SEED = 0x57494345_1000L;

    @Test
    void totalValueConservationAndZeroMoneySwallowingAcross1500RandomGraphs() {
        var random = new Random(PAYMENT_SEED);
        int successCount = 0;
        int fullForChangeCount = 0;
        int insufficientCount = 0;
        int nestedUsedCount = 0;
        int nonStandardGraphCount = 0;

        for (int round = 0; round < PAYMENT_ROUNDS; round++) {
            var graph = generateRandomGraph(random, round);
            if (graph.nonStandard) {
                nonStandardGraphCount++;
            }

            var resolvedOpt = CraftingDenominations.resolve(graph.baseItem, graph.recipes);
            assertTrue(resolvedOpt.isPresent(), "Round " + round + ": valid reversible graph failed to resolve");
            var unitValues = resolvedOpt.get();
            assertTrue(unitValues.containsValue(1), "Round " + round + ": resolved graph must include unit=1");

            // Verify every reversible edge preserves exact integer ratio
            var itemIds = new ArrayList<>(unitValues.keySet());
            var maxStackSizes = new HashMap<String, Integer>();
            for (String itemId : itemIds) {
                int maxStack = random.nextInt(5) == 0 ? 16 : 64;
                maxStackSizes.put(itemId, maxStack);
            }

            boolean tightInventory = random.nextInt(100) < 38;
            var inv = generateRandomInventory(random, itemIds, maxStackSizes, tightInventory);
            var originalSnapshot = deepCopy(inv);

            boolean searchNested = random.nextBoolean();
            String currency = itemIds.get(random.nextInt(itemIds.size()));
            int costCount = random.nextInt(20) == 0 ? 0 : 1 + random.nextInt(32);
            long requiredUnits = (long) costCount * unitValues.get(currency);

            long beforeTotalAll = totalValue(inv, unitValues, true);
            long beforeTotalSearchable = totalValue(inv, unitValues, searchNested);
            long beforeNestedOnly = beforeTotalAll - totalValue(inv, unitValues, false);

            var outcome = ChangeSlotPlanner.simulateCharge(
                inv, currency, costCount, unitValues, searchNested, maxStackSizes
            );

            switch (outcome.status()) {
                case SUCCESS -> {
                    successCount++;
                    if (outcome.usedNestedContainer()) {
                        nestedUsedCount++;
                    }
                    assertTrue(beforeTotalSearchable >= requiredUnits,
                        "Round " + round + ": succeeded despite insufficient searchable funds");

                    long afterTotalAll = totalValue(inv, unitValues, true);
                    long afterTotalSearchable = totalValue(inv, unitValues, searchNested);
                    long afterNestedOnly = afterTotalAll - totalValue(inv, unitValues, false);

                    // Core invariant: total value conservation
                    assertEquals(beforeTotalAll - requiredUnits, afterTotalAll,
                        "Round " + round + ": TOTAL value conservation violated (all slots)");
                    assertEquals(beforeTotalSearchable - requiredUnits, afterTotalSearchable,
                        "Round " + round + ": searchable value conservation violated");

                    if (!searchNested) {
                        assertEquals(beforeNestedOnly, afterNestedOnly,
                            "Round " + round + ": nested containers modified while searchNested=false");
                    }

                    // Consumed - Change == Required
                    long consumedValue = 0;
                    for (var c : outcome.consumed()) {
                        consumedValue += (long) c.count() * unitValues.get(c.itemId());
                    }
                    long changeValue = 0;
                    for (var ch : outcome.change()) {
                        changeValue += (long) ch.count() * unitValues.get(ch.itemId());
                    }
                    assertEquals(requiredUnits, consumedValue - changeValue,
                        "Round " + round + ": consumedValue - changeValue != requiredUnits");

                    // Armor 36..39 untouched, offhand 40 never receives change
                    for (int i = 36; i <= 39; i++) {
                        assertSlotEquals(originalSnapshot.get(i), inv.get(i),
                            "Round " + round + ": armor slot " + i + " mutated");
                    }
                    assertTrue(inv.get(40).count <= originalSnapshot.get(40).count,
                        "Round " + round + ": offhand received change");

                    // Stack size bounds and filler/non-plain preservation
                    for (int i = 0; i < 36; i++) {
                        var s = inv.get(i);
                        if (!s.isEmpty()) {
                            assertTrue(s.count > 0 && s.count <= s.maxStackSize,
                                "Round " + round + ": slot " + i + " exceeded maxStackSize");
                        }
                    }
                    assertNonCurrencyUntouched(originalSnapshot, inv, unitValues, round);
                }
                case INVENTORY_FULL_FOR_CHANGE -> {
                    fullForChangeCount++;
                    assertTrue(beforeTotalSearchable >= requiredUnits,
                        "Round " + round + ": INVENTORY_FULL_FOR_CHANGE with insufficient funds");
                    assertTrue(outcome.slotsNeededForChange() >= 1,
                        "Round " + round + ": INVENTORY_FULL_FOR_CHANGE must need >= 1 slot");

                    // Core invariant: ZERO money swallowed on full-inventory rejection
                    assertInventoryEquals(originalSnapshot, inv,
                        "Round " + round + ": inventory mutated on INVENTORY_FULL_FOR_CHANGE (swallowed money!)");

                    // Freeing exact slotsNeededForChange filler slots in 0..35 must unblock payment
                    var retryInv = deepCopy(originalSnapshot);
                    int freed = 0;
                    for (int i = 0; i < 36 && freed < outcome.slotsNeededForChange(); i++) {
                        var s = retryInv.get(i);
                        if (!s.isEmpty() && s.plain && !unitValues.containsKey(s.itemId)) {
                            retryInv.set(i, SimSlot.empty());
                            freed++;
                        }
                    }
                    if (freed == outcome.slotsNeededForChange()) {
                        long retryBefore = totalValue(retryInv, unitValues, true);
                        var retryOutcome = ChangeSlotPlanner.simulateCharge(
                            retryInv, currency, costCount, unitValues, searchNested, maxStackSizes
                        );
                        assertEquals(PaymentStatus.SUCCESS, retryOutcome.status(),
                            "Round " + round + ": freeing exact slotsNeededForChange=" + freed + " must succeed");
                        assertEquals(retryBefore - requiredUnits, totalValue(retryInv, unitValues, true),
                            "Round " + round + ": retry after freeing slots violated value conservation");
                    }
                }
                case INSUFFICIENT_FUNDS -> {
                    insufficientCount++;
                    assertTrue(beforeTotalSearchable < requiredUnits,
                        "Round " + round + ": INSUFFICIENT_FUNDS despite having enough funds");
                    assertInventoryEquals(originalSnapshot, inv,
                        "Round " + round + ": inventory mutated on INSUFFICIENT_FUNDS");
                }
            }
        }

        // Ensure all three major branches and nested containers were meaningfully exercised
        assertTrue(successCount > 200, "Expected >200 SUCCESS rounds, got " + successCount);
        assertTrue(fullForChangeCount > 30, "Expected >30 INVENTORY_FULL_FOR_CHANGE rounds, got " + fullForChangeCount);
        assertTrue(insufficientCount > 100, "Expected >100 INSUFFICIENT_FUNDS rounds, got " + insufficientCount);
        assertTrue(nestedUsedCount > 50, "Expected >50 nested container rounds, got " + nestedUsedCount);
        assertTrue(nonStandardGraphCount > 500, "Expected >500 non-standard ratio graphs, got " + nonStandardGraphCount);
    }

    @Test
    void randomDenominationGraphResolutionInvariantsAcross1000Rounds() {
        var random = new Random(GRAPH_SEED);
        int conflictCount = 0;
        int validCount = 0;

        for (int round = 0; round < GRAPH_ROUNDS; round++) {
            int tiers = 2 + random.nextInt(4); // 2..5 denominations
            var names = new ArrayList<String>(tiers);
            for (int t = 0; t < tiers; t++) {
                names.add("test:tier_" + t);
            }

            var recipes = new ArrayList<Recipe>();
            var expectedRatios = new ArrayList<int[]>();
            for (int t = 0; t < tiers - 1; t++) {
                int ratio = switch (random.nextInt(6)) {
                    case 0 -> 9;
                    case 1 -> 4;
                    case 2 -> 3;
                    case 3 -> 5;
                    case 4 -> 6;
                    default -> 2 + random.nextInt(8);
                };
                recipes.add(new Recipe(names.get(t), ratio, names.get(t + 1), 1));
                recipes.add(new Recipe(names.get(t + 1), 1, names.get(t), ratio));
                expectedRatios.add(new int[]{t, t + 1, ratio});
            }

            // Add irreversible noise recipes that must be ignored
            recipes.add(new Recipe(names.get(0), 2, "test:one_way_scrap", 1));
            recipes.add(new Recipe("test:unrelated_a", 4, "test:unrelated_b", 1));
            recipes.add(new Recipe("test:unrelated_b", 1, "test:unrelated_a", 4));

            boolean injectConflict = random.nextInt(5) == 0 && tiers >= 3;
            if (injectConflict) {
                // Contradictory reversible shortcut between tier 0 and tier 2
                recipes.add(new Recipe(names.get(0), 7, names.get(2), 1));
                recipes.add(new Recipe(names.get(2), 1, names.get(0), 7));
                // Only truly conflicts if product of first two ratios != 7
                int actualProduct = expectedRatios.get(0)[2] * expectedRatios.get(1)[2];
                if (actualProduct == 7) {
                    injectConflict = false;
                }
            }

            String queryBase = names.get(random.nextInt(tiers));
            var resolved = CraftingDenominations.resolve(queryBase, recipes);

            if (injectConflict) {
                conflictCount++;
                assertTrue(resolved.isEmpty(), "Round " + round + ": conflicting cycle must return empty");
            } else {
                validCount++;
                assertTrue(resolved.isPresent(), "Round " + round + ": consistent graph must resolve");
                var units = resolved.get();
                assertEquals(tiers, units.size(), "Round " + round + ": unexpected denomination count");
                assertEquals(1, units.get(names.get(0)), "Round " + round + ": smallest tier must equal 1");
                assertFalse(units.containsKey("test:one_way_scrap"));
                assertFalse(units.containsKey("test:unrelated_a"));
                for (var edge : expectedRatios) {
                    int low = units.get(names.get(edge[0]));
                    int high = units.get(names.get(edge[1]));
                    assertEquals(low * edge[2], high, "Round " + round + ": ratio mismatch on edge");
                }
            }
        }

        assertTrue(conflictCount > 100, "Expected >100 conflict rounds, got " + conflictCount);
        assertTrue(validCount > 700, "Expected >700 valid rounds, got " + validCount);
    }

    private record DenomGraph(String baseItem, List<Recipe> recipes, boolean nonStandard) {}

    private static DenomGraph generateRandomGraph(Random random, int round) {
        int kind = round % 5;
        var recipes = new ArrayList<Recipe>();
        if (kind == 0) {
            // Standard 1/9/81 (iron/gold)
            recipes.add(new Recipe("minecraft:iron_nugget", 9, "minecraft:iron_ingot", 1));
            recipes.add(new Recipe("minecraft:iron_ingot", 1, "minecraft:iron_nugget", 9));
            recipes.add(new Recipe("minecraft:iron_ingot", 9, "minecraft:iron_block", 1));
            recipes.add(new Recipe("minecraft:iron_block", 1, "minecraft:iron_ingot", 9));
            return new DenomGraph("minecraft:iron_ingot", recipes, false);
        }
        if (kind == 1) {
            // Standard 1/9 (emerald)
            recipes.add(new Recipe("minecraft:emerald", 9, "minecraft:emerald_block", 1));
            recipes.add(new Recipe("minecraft:emerald_block", 1, "minecraft:emerald", 9));
            return new DenomGraph("minecraft:emerald", recipes, false);
        }
        if (kind == 2) {
            // Non-standard 1/4/16 (glowstone/quartz-style 2x2 compression)
            recipes.add(new Recipe("custom:shard", 4, "custom:gem", 1));
            recipes.add(new Recipe("custom:gem", 1, "custom:shard", 4));
            recipes.add(new Recipe("custom:gem", 4, "custom:block", 1));
            recipes.add(new Recipe("custom:block", 1, "custom:gem", 4));
            return new DenomGraph("custom:gem", recipes, true);
        }
        if (kind == 3) {
            // Non-standard mixed 1/3/12/48 or 1/5/25
            int r1 = random.nextBoolean() ? 3 : 5;
            int r2 = random.nextBoolean() ? 4 : 5;
            recipes.add(new Recipe("custom:bit", r1, "custom:coin", 1));
            recipes.add(new Recipe("custom:coin", 1, "custom:bit", r1));
            recipes.add(new Recipe("custom:coin", r2, "custom:bar", 1));
            recipes.add(new Recipe("custom:bar", 1, "custom:coin", r2));
            // Add one-way distraction recipe
            recipes.add(new Recipe("custom:coin", 2, "custom:dust", 1));
            return new DenomGraph("custom:coin", recipes, true);
        }
        // Kind 4: Fully random 2..4-tier chain with ratios from {2, 3, 4, 5, 6, 8, 9}
        int[] ratioPool = {2, 3, 4, 5, 6, 8, 9};
        int tiers = 2 + random.nextInt(3);
        for (int t = 0; t < tiers - 1; t++) {
            int r = ratioPool[random.nextInt(ratioPool.length)];
            recipes.add(new Recipe("dyn:d" + t, r, "dyn:d" + (t + 1), 1));
            recipes.add(new Recipe("dyn:d" + (t + 1), 1, "dyn:d" + t, r));
        }
        return new DenomGraph("dyn:d" + random.nextInt(tiers), recipes, true);
    }

    private static List<SimSlot> generateRandomInventory(
        Random random,
        List<String> itemIds,
        Map<String, Integer> maxStackSizes,
        boolean tightInventory
    ) {
        var inv = new ArrayList<SimSlot>(41);
        for (int slot = 0; slot < 41; slot++) {
            if (slot >= 36 && slot <= 39) {
                inv.add(random.nextBoolean()
                    ? SimSlot.empty()
                    : SimSlot.item("minecraft:diamond_chestplate", 1, 1, false));
                continue;
            }
            if (slot == 40) {
                int offRoll = random.nextInt(100);
                if (offRoll < 50) {
                    inv.add(SimSlot.empty());
                } else if (offRoll < 80) {
                    String id = itemIds.get(random.nextInt(itemIds.size()));
                    int max = maxStackSizes.getOrDefault(id, 64);
                    inv.add(SimSlot.item(id, 1 + random.nextInt(Math.min(8, max)), max, true));
                } else {
                    inv.add(randomNestedContainer(random, itemIds, maxStackSizes));
                }
                continue;
            }

            // Main inventory 0..35
            int roll = random.nextInt(100);
            if (tightInventory) {
                if (roll < 78) {
                    inv.add(SimSlot.item("minecraft:cobblestone", 64));
                } else if (roll < 90) {
                    // Bias toward higher denominations with count >= 2 so breaking them needs fresh change slots
                    String id = itemIds.get(itemIds.size() - 1);
                    int max = maxStackSizes.getOrDefault(id, 64);
                    int cnt = Math.min(max, 2 + random.nextInt(Math.max(1, Math.min(8, max - 1))));
                    inv.add(SimSlot.item(id, cnt, max, true));
                } else if (roll < 94) {
                    // Non-plain renamed currency item that must never be converted or merged
                    String id = itemIds.get(random.nextInt(itemIds.size()));
                    inv.add(SimSlot.item(id, 1 + random.nextInt(4), 64, false));
                } else {
                    inv.add(randomNestedContainer(random, itemIds, maxStackSizes));
                }
            } else {
                if (roll < 30) {
                    inv.add(SimSlot.empty());
                } else if (roll < 55) {
                    inv.add(SimSlot.item("minecraft:dirt", 1 + random.nextInt(64)));
                } else if (roll < 82) {
                    String id = itemIds.get(random.nextInt(itemIds.size()));
                    int max = maxStackSizes.getOrDefault(id, 64);
                    inv.add(SimSlot.item(id, 1 + random.nextInt(Math.min(16, max)), max, true));
                } else if (roll < 88) {
                    String id = itemIds.get(random.nextInt(itemIds.size()));
                    inv.add(SimSlot.item(id, 1 + random.nextInt(4), 64, false));
                } else {
                    inv.add(randomNestedContainer(random, itemIds, maxStackSizes));
                }
            }
        }
        return inv;
    }

    private static SimSlot randomNestedContainer(
        Random random,
        List<String> itemIds,
        Map<String, Integer> maxStackSizes
    ) {
        boolean isBundle = random.nextBoolean();
        String containerId = isBundle ? "minecraft:bundle" : "minecraft:shulker_box";
        int entries = 1 + random.nextInt(isBundle ? 3 : 6);
        var contents = new ArrayList<SimSlot>(entries);
        for (int i = 0; i < entries; i++) {
            if (random.nextInt(4) == 0) {
                contents.add(SimSlot.item("minecraft:cobblestone", 1 + random.nextInt(16)));
            } else {
                String id = itemIds.get(random.nextInt(itemIds.size()));
                int max = maxStackSizes.getOrDefault(id, 64);
                contents.add(SimSlot.item(id, 1 + random.nextInt(Math.min(8, max)), max, true));
            }
        }
        return SimSlot.container(containerId, contents);
    }

    private static List<SimSlot> deepCopy(List<SimSlot> inv) {
        var copy = new ArrayList<SimSlot>(inv.size());
        for (var s : inv) {
            copy.add(s.copy());
        }
        return copy;
    }

    private static long totalValue(List<SimSlot> inv, Map<String, Integer> unitValues, boolean includeNested) {
        long sum = 0;
        for (var s : inv) {
            if (s.isEmpty()) continue;
            if (s.plain) {
                sum += (long) s.count * unitValues.getOrDefault(s.itemId, 0);
            }
            if (includeNested && s.nested != null) {
                for (var child : s.nested) {
                    if (!child.isEmpty() && child.plain) {
                        sum += (long) child.count * unitValues.getOrDefault(child.itemId, 0);
                    }
                }
            }
        }
        return sum;
    }

    private static void assertNonCurrencyUntouched(
        List<SimSlot> before,
        List<SimSlot> after,
        Map<String, Integer> unitValues,
        int round
    ) {
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            var b = before.get(i);
            var a = after.get(i);
            if (!b.isEmpty() && (!b.plain || !unitValues.containsKey(b.itemId)) && b.nested == null) {
                assertSlotEquals(b, a, "Round " + round + ": non-currency/non-plain slot " + i + " mutated");
            }
        }
    }

    private static void assertInventoryEquals(List<SimSlot> expected, List<SimSlot> actual, String msg) {
        assertEquals(expected.size(), actual.size(), msg);
        for (int i = 0; i < expected.size(); i++) {
            assertSlotEquals(expected.get(i), actual.get(i), msg + " at slot " + i);
        }
    }

    private static void assertSlotEquals(SimSlot expected, SimSlot actual, String msg) {
        if (expected.isEmpty() && actual.isEmpty()) return;
        assertEquals(expected.itemId, actual.itemId, msg + " (itemId)");
        assertEquals(expected.count, actual.count, msg + " (count)");
        assertEquals(expected.plain, actual.plain, msg + " (plain)");
        if (expected.nested == null) {
            assertEquals(null, actual.nested, msg + " (nested null)");
        } else {
            assertEquals(expected.nested.size(), actual.nested.size(), msg + " (nested size)");
            for (int i = 0; i < expected.nested.size(); i++) {
                assertSlotEquals(expected.nested.get(i), actual.nested.get(i), msg + " nested[" + i + "]");
            }
        }
    }
}
