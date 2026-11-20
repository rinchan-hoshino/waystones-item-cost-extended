package dev.rinchan.waystonesitemcost.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rinchan.waystonesitemcost.payment.CraftingDenominations.Recipe;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CraftingDenominationsTest {
    @Test
    void resolvesThreeTierReversibleChain() {
        var recipes = List.of(
            new Recipe("minecraft:iron_nugget", 9, "minecraft:iron_ingot", 1),
            new Recipe("minecraft:iron_ingot", 1, "minecraft:iron_nugget", 9),
            new Recipe("minecraft:iron_ingot", 9, "minecraft:iron_block", 1),
            new Recipe("minecraft:iron_block", 1, "minecraft:iron_ingot", 9)
        );
        var values = CraftingDenominations.resolve("minecraft:iron_ingot", recipes).orElseThrow();
        assertEquals(Map.of(
            "minecraft:iron_nugget", 1,
            "minecraft:iron_ingot", 9,
            "minecraft:iron_block", 81
        ), values);
    }

    @Test
    void ignoresOneWayAndLossyRecipes() {
        var recipes = List.of(
            new Recipe("minecraft:oak_log", 1, "minecraft:oak_planks", 4),
            new Recipe("minecraft:emerald", 9, "minecraft:emerald_block", 1),
            new Recipe("minecraft:emerald_block", 1, "minecraft:emerald", 8) // lossy!
        );
        var values = CraftingDenominations.resolve("minecraft:emerald", recipes).orElseThrow();
        assertEquals(Map.of("minecraft:emerald", 1), values);
    }

    @Test
    void rejectsConflictingCycle() {
        var recipes = List.of(
            new Recipe("a", 9, "b", 1),
            new Recipe("b", 1, "a", 9),
            new Recipe("a", 8, "b", 1),
            new Recipe("b", 1, "a", 8)
        );
        assertTrue(CraftingDenominations.resolve("a", recipes).isEmpty());
    }

    @Test
    void rejectsChainWithoutUnitDenomination() {
        // 2x A <-> 3x B: values are 2 and 3, neither is 1
        var recipes = List.of(
            new Recipe("a", 3, "b", 2),
            new Recipe("b", 2, "a", 3)
        );
        assertTrue(CraftingDenominations.resolve("a", recipes).isEmpty());
    }
}
