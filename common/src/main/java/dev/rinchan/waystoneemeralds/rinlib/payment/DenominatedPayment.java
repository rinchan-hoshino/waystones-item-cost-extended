package dev.rinchan.waystoneemeralds.rinlib.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plans payments from inventory stacks whose items have fixed integer unit values.
 * The planner is side-effect free; callers own inventory reads and mutations.
 */
public final class DenominatedPayment {
    private DenominatedPayment() {
    }

    public static Optional<Plan> plan(int requiredUnits, List<Stack> stacks) {
        if (requiredUnits < 0) {
            throw new IllegalArgumentException("requiredUnits must not be negative");
        }
        Objects.requireNonNull(stacks, "stacks");
        if (requiredUnits == 0) {
            return Optional.of(new Plan(List.of(), 0));
        }

        long remainingUnits = requiredUnits;
        var removals = new ArrayList<Removal>();
        for (var stack : stacks) {
            if (remainingUnits <= 0) {
                break;
            }

            long itemsNeeded = (remainingUnits + stack.unitValue() - 1L) / stack.unitValue();
            int itemsRemoved = (int) Math.min(stack.itemCount(), itemsNeeded);
            if (itemsRemoved == 0) {
                continue;
            }

            removals.add(new Removal(stack.slot(), itemsRemoved));
            remainingUnits -= (long) itemsRemoved * stack.unitValue();
        }

        return remainingUnits > 0
                ? Optional.empty()
                : Optional.of(new Plan(removals, Math.toIntExact(-remainingUnits)));
    }

    public record Stack(int slot, int itemCount, int unitValue) {
        public Stack {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must not be negative");
            }
            if (itemCount < 0) {
                throw new IllegalArgumentException("itemCount must not be negative");
            }
            if (unitValue <= 0) {
                throw new IllegalArgumentException("unitValue must be positive");
            }
        }
    }

    public record Removal(int slot, int itemCount) {
        public Removal {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must not be negative");
            }
            if (itemCount <= 0) {
                throw new IllegalArgumentException("itemCount must be positive");
            }
        }
    }

    public record Plan(List<Removal> removals, int changeUnits) {
        public Plan {
            removals = List.copyOf(removals);
            if (changeUnits < 0) {
                throw new IllegalArgumentException("changeUnits must not be negative");
            }
        }
    }
}
