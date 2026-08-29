package dev.rinchan.waystoneemeralds.payment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public final class EmeraldPaymentTransaction {
    private EmeraldPaymentTransaction() {
    }

    public static int availableUnits(InventoryAccess inventory) {
        long units = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            int count = Math.max(0, inventory.count(slot));
            int unitValue = Math.max(0, inventory.unitValue(slot));
            units = Math.min(Integer.MAX_VALUE, units + (long) count * unitValue);
        }
        return (int) units;
    }

    public static boolean canPay(InventoryAccess inventory, int requiredUnits, Planner planner) {
        if (inventory.creative()) {
            return true;
        }
        return validatedPlan(inventory, requiredUnits, planner).isPresent();
    }

    public static boolean consume(InventoryAccess inventory, int requiredUnits, Planner planner) {
        if (inventory.creative()) {
            return true;
        }

        var plan = validatedPlan(inventory, requiredUnits, planner);
        if (plan.isEmpty()) {
            return false;
        }

        for (var removal : plan.orElseThrow().removals()) {
            inventory.remove(removal.slot(), removal.itemCount());
        }
        inventory.giveEmeralds(plan.orElseThrow().changeUnits());
        return true;
    }

    public static void refund(InventoryAccess inventory, int units) {
        if (!inventory.creative() && units > 0) {
            inventory.giveEmeralds(units);
        }
    }

    private static Optional<Plan> validatedPlan(InventoryAccess inventory, int requiredUnits, Planner planner) {
        if (requiredUnits < 0) {
            return Optional.empty();
        }

        var stacks = new ArrayList<Stack>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            int count = inventory.count(slot);
            int unitValue = inventory.unitValue(slot);
            if (count > 0 && unitValue > 0) {
                stacks.add(new Stack(slot, count, unitValue));
            }
        }

        var candidate = planner.plan(requiredUnits, List.copyOf(stacks));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        var plan = candidate.orElseThrow();
        if (plan.changeUnits() < 0) {
            return Optional.empty();
        }

        long removedValue = 0;
        var seenSlots = new HashSet<Integer>();
        for (var removal : plan.removals()) {
            int slot = removal.slot();
            if (slot < 0 || slot >= inventory.size()
                || removal.itemCount() <= 0
                || !seenSlots.add(slot)
                || removal.itemCount() > inventory.count(slot)
                || inventory.unitValue(slot) <= 0) {
                return Optional.empty();
            }
            removedValue += (long) removal.itemCount() * inventory.unitValue(slot);
        }

        return removedValue - plan.changeUnits() == requiredUnits ? Optional.of(plan) : Optional.empty();
    }

    public interface InventoryAccess {
        boolean creative();

        int size();

        int count(int slot);

        int unitValue(int slot);

        void remove(int slot, int count);

        void giveEmeralds(int count);
    }

    @FunctionalInterface
    public interface Planner {
        Optional<Plan> plan(int requiredUnits, List<Stack> stacks);
    }

    public record Stack(int slot, int itemCount, int unitValue) {
    }

    public record Removal(int slot, int itemCount) {
    }

    public record Plan(List<Removal> removals, int changeUnits) {
        public Plan {
            removals = List.copyOf(removals);
        }
    }
}
