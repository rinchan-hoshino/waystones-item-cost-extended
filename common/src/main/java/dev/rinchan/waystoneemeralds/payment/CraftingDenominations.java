package dev.rinchan.waystoneemeralds.payment;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Currency values derived from reversible, single-ingredient crafting recipes. */
public final class CraftingDenominations {
    private CraftingDenominations() {
    }

    public record Recipe(String ingredient, int inputCount, String result, int outputCount) {
        public Recipe {
            if (ingredient == null || result == null || inputCount <= 0 || outputCount <= 0) {
                throw new IllegalArgumentException("A recipe requires item IDs and positive counts");
            }
        }
    }

    /** Returns no conversion when recipes disagree about value or exceed the planner's range. */
    public static Optional<Map<String, Integer>> resolve(String currency, List<Recipe> recipes) {
        var edges = new HashMap<String, List<Recipe>>();
        for (var recipe : recipes) {
            if (recipe.ingredient().equals(recipe.result()) || recipe.inputCount() == recipe.outputCount()) {
                continue;
            }
            boolean reversible = recipes.stream().anyMatch(reverse ->
                reverse.ingredient().equals(recipe.result()) && reverse.result().equals(recipe.ingredient())
                    && (long) recipe.inputCount() * reverse.inputCount()
                        == (long) recipe.outputCount() * reverse.outputCount());
            if (reversible) {
                edges.computeIfAbsent(recipe.ingredient(), ignored -> new ArrayList<>()).add(recipe);
            }
        }

        var values = new LinkedHashMap<String, Fraction>();
        var pending = new ArrayDeque<String>();
        values.put(currency, new Fraction(BigInteger.ONE, BigInteger.ONE));
        pending.add(currency);
        while (!pending.isEmpty()) {
            var item = pending.remove();
            for (var edge : edges.getOrDefault(item, List.of())) {
                var value = values.get(item).multiply(edge.inputCount(), edge.outputCount());
                var previous = values.putIfAbsent(edge.result(), value);
                if (previous == null) {
                    pending.add(edge.result());
                } else if (!previous.equals(value)) {
                    return Optional.empty();
                }
            }
        }

        var scale = BigInteger.ONE;
        for (var value : values.values()) {
            scale = scale.divide(scale.gcd(value.denominator())).multiply(value.denominator());
        }
        var units = new LinkedHashMap<String, Integer>();
        try {
            for (var entry : values.entrySet()) {
                var value = entry.getValue();
                units.put(entry.getKey(), value.numerator().multiply(scale.divide(value.denominator())).intValueExact());
            }
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
        // A smallest denomination must represent one unit so every overpayment can be returned exactly.
        return units.containsValue(1) ? Optional.of(Map.copyOf(units)) : Optional.empty();
    }

    private record Fraction(BigInteger numerator, BigInteger denominator) {
        private Fraction {
            var divisor = numerator.gcd(denominator);
            numerator = numerator.divide(divisor);
            denominator = denominator.divide(divisor);
        }

        private Fraction multiply(int numerator, int denominator) {
            return new Fraction(this.numerator.multiply(BigInteger.valueOf(numerator)),
                this.denominator.multiply(BigInteger.valueOf(denominator)));
        }
    }
}
