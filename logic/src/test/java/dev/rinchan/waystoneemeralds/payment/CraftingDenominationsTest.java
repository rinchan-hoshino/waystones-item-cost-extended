package dev.rinchan.waystoneemeralds.payment;

import static org.junit.jupiter.api.Assertions.*;
import dev.rinchan.waystoneemeralds.payment.CraftingDenominations.Recipe;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CraftingDenominationsTest {
    private static final List<Recipe> IRON = List.of(
        new Recipe("nugget", 9, "ingot", 1), new Recipe("ingot", 1, "nugget", 9),
        new Recipe("ingot", 9, "block", 1), new Recipe("block", 1, "ingot", 9));

    @Test void resolvesEveryStartingDenominationToTheSameValues() {
        for (var currency : List.of("nugget", "ingot", "block")) {
            assertEquals(Map.of("nugget", 1, "ingot", 9, "block", 81),
                CraftingDenominations.resolve(currency, IRON).orElseThrow());
        }
    }

    @Test void oneWayCraftingDoesNotTurnLogsIntoCurrency() {
        assertEquals(Map.of("log", 1), CraftingDenominations.resolve("log",
            List.of(new Recipe("log", 1, "plank", 4))).orElseThrow());
    }

    @Test void inconsistentCyclesAreRejected() {
        assertTrue(CraftingDenominations.resolve("ingot", List.of(
            IRON.get(0), IRON.get(1), IRON.get(2), IRON.get(3),
            new Recipe("nugget", 80, "block", 1), new Recipe("block", 1, "nugget", 80))).isEmpty());
    }

    @Test void lossyReverseRecipesAreNotCurrency() {
        assertEquals(Map.of("ingot", 1), CraftingDenominations.resolve("ingot",
            List.of(new Recipe("ingot", 9, "block", 1), new Recipe("block", 1, "ingot", 8))).orElseThrow());
    }

    @Test void disconnectedConflictsDoNotDisableValidCurrency() {
        var recipes = new java.util.ArrayList<>(IRON);
        recipes.addAll(List.of(new Recipe("a", 2, "b", 1), new Recipe("b", 1, "a", 2),
            new Recipe("a", 3, "b", 1), new Recipe("b", 1, "a", 3)));
        assertEquals(81, CraftingDenominations.resolve("ingot", recipes).orElseThrow().get("block"));
    }
}
