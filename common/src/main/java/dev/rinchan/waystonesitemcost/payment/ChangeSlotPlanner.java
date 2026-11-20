package dev.rinchan.waystonesitemcost.payment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java planner for multi-denomination payments, nested container deductions,
 * greedy high-to-low change decomposition, and exact missing slot computation.
 *
 * Ordinary player inventory slots are strictly 0..35; armor (36..39) and offhand (40)
 * are never used to receive change.
 */
public final class ChangeSlotPlanner {
    public static final int MAIN_INVENTORY_SIZE = 36;

    private ChangeSlotPlanner() {
    }

    public record ItemAmount(String itemId, int count) {
        public ItemAmount {
            if (itemId == null || itemId.isEmpty()) throw new IllegalArgumentException("itemId required");
            if (count <= 0) throw new IllegalArgumentException("count must be positive");
        }
    }

    /** Represents a slot in a simulated inventory (plain item or nested container). */
    public static final class SimSlot {
        public String itemId; // null or empty means empty slot
        public int count;
        public int maxStackSize;
        public boolean plain;
        public List<SimSlot> nested; // non-null if bundle or shulker box

        public SimSlot(String itemId, int count, int maxStackSize, boolean plain, List<SimSlot> nested) {
            this.itemId = itemId;
            this.count = count;
            this.maxStackSize = maxStackSize;
            this.plain = plain;
            this.nested = nested;
        }

        public static SimSlot empty() {
            return new SimSlot(null, 0, 64, true, null);
        }

        public static SimSlot item(String itemId, int count) {
            return new SimSlot(itemId, count, 64, true, null);
        }

        public static SimSlot item(String itemId, int count, int maxStackSize, boolean plain) {
            return new SimSlot(itemId, count, maxStackSize, plain, null);
        }

        public static SimSlot container(String containerId, List<SimSlot> contents) {
            return new SimSlot(containerId, 1, 1, false, new ArrayList<>(contents));
        }

        public boolean isEmpty() {
            return itemId == null || itemId.isEmpty() || count <= 0;
        }

        public SimSlot copy() {
            List<SimSlot> nestedCopy = null;
            if (nested != null) {
                nestedCopy = new ArrayList<>(nested.size());
                for (var child : nested) nestedCopy.add(child.copy());
            }
            return new SimSlot(itemId, count, maxStackSize, plain, nestedCopy);
        }
    }

    public record PlanOutcome(
        PaymentStatus status,
        int slotsNeededForChange,
        List<ItemAmount> consumed,
        List<ItemAmount> change,
        boolean usedConversion,
        boolean usedNestedContainer
    ) {
        public boolean isSuccess() {
            return status == PaymentStatus.SUCCESS;
        }
    }

    /**
     * Computes exact greedy change stacks from highest denomination to lowest.
     */
    public static List<ItemAmount> decomposeChange(int changeUnits, Map<String, Integer> unitValues) {
        if (changeUnits <= 0) return List.of();
        var denominations = unitValues.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .toList();
        var result = new ArrayList<ItemAmount>();
        int remaining = changeUnits;
        for (var denom : denominations) {
            int value = denom.getValue();
            if (value <= 0) continue;
            int qty = remaining / value;
            if (qty > 0) {
                result.add(new ItemAmount(denom.getKey(), qty));
                remaining %= value;
            }
        }
        return List.copyOf(result);
    }

    /**
     * Computes how many additional empty slots in 0..35 are needed to insert the given change items
     * into the post-deduction inventory snapshot. Returns 0 if all change fits.
     */
    public static int computeMissingSlots(List<SimSlot> postDeductionInventory, List<ItemAmount> changeItems, Map<String, Integer> maxStackSizes) {
        int limit = Math.min(MAIN_INVENTORY_SIZE, postDeductionInventory.size());
        var work = new ArrayList<SimSlot>(limit);
        for (int i = 0; i < limit; i++) {
            work.add(postDeductionInventory.get(i).copy());
        }
        int extraSlotsNeeded = 0;
        for (var change : changeItems) {
            int remaining = change.count();
            int maxStack = Math.max(1, maxStackSizes.getOrDefault(change.itemId(), 64));
            // 1. Merge into existing plain matching stacks in 0..35
            for (int slot = 0; slot < limit && remaining > 0; slot++) {
                var s = work.get(slot);
                if (!s.isEmpty() && s.plain && change.itemId().equals(s.itemId)) {
                    int space = Math.max(0, Math.min(s.maxStackSize, maxStack) - s.count);
                    int moved = Math.min(remaining, space);
                    s.count += moved;
                    remaining -= moved;
                }
            }
            // 2. Fill empty slots in 0..35
            for (int slot = 0; slot < limit && remaining > 0; slot++) {
                var s = work.get(slot);
                if (s.isEmpty()) {
                    int moved = Math.min(remaining, maxStack);
                    work.set(slot, SimSlot.item(change.itemId(), moved, maxStack, true));
                    remaining -= moved;
                }
            }
            // 3. Any remaining needs fresh empty slots
            if (remaining > 0) {
                extraSlotsNeeded += (remaining + maxStack - 1) / maxStack;
            }
        }
        return extraSlotsNeeded;
    }

    /**
     * Full pure-Java simulation of charging a denominated item cost against a player inventory
     * (supporting optional nested containers in slots 0..35 and 40, while strictly keeping change in 0..35).
     */
    public static PlanOutcome simulateCharge(
        List<SimSlot> inventory,
        String currencyId,
        int count,
        Map<String, Integer> unitValues,
        boolean searchNestedContainers,
        Map<String, Integer> maxStackSizes
    ) {
        if (count < 0) {
            return new PlanOutcome(PaymentStatus.INSUFFICIENT_FUNDS, 0, List.of(), List.of(), false, false);
        }
        if (count == 0) {
            return new PlanOutcome(PaymentStatus.SUCCESS, 0, List.of(), List.of(), false, false);
        }
        Integer baseUnit = unitValues.get(currencyId);
        if (baseUnit == null || baseUnit <= 0) {
            return new PlanOutcome(PaymentStatus.INSUFFICIENT_FUNDS, 0, List.of(), List.of(), false, false);
        }
        long requiredLong = (long) count * baseUnit;
        if (requiredLong > Integer.MAX_VALUE) {
            return new PlanOutcome(PaymentStatus.INSUFFICIENT_FUNDS, 0, List.of(), List.of(), false, false);
        }
        int requiredUnits = (int) requiredLong;

        record SourceRef(int topSlot, int nestedIndex, String itemId, int count, int unitValue, boolean isNested) {}
        var sources = new ArrayList<SourceRef>();

        for (int slot = 0; slot < inventory.size(); slot++) {
            var s = inventory.get(slot);
            if (s.isEmpty()) continue;
            if (s.plain) {
                int u = unitValues.getOrDefault(s.itemId, 0);
                if (u > 0) {
                    sources.add(new SourceRef(slot, -1, s.itemId, s.count, u, false));
                }
            }
            if (searchNestedContainers && s.nested != null) {
                for (int ni = 0; ni < s.nested.size(); ni++) {
                    var child = s.nested.get(ni);
                    if (!child.isEmpty() && child.plain) {
                        int u = unitValues.getOrDefault(child.itemId, 0);
                        if (u > 0) {
                            sources.add(new SourceRef(slot, ni, child.itemId, child.count, u, true));
                        }
                    }
                }
            }
        }

        // Prefer direct inventory over nested containers at the same denomination, and smaller denominations first
        sources.sort(Comparator.comparingInt(SourceRef::unitValue)
            .thenComparing(SourceRef::isNested));

        var stacks = new ArrayList<DenominatedPayment.Stack>(sources.size());
        for (int idx = 0; idx < sources.size(); idx++) {
            var src = sources.get(idx);
            stacks.add(new DenominatedPayment.Stack(idx, src.count(), src.unitValue()));
        }

        var planOpt = DenominatedPayment.plan(requiredUnits, stacks);
        if (planOpt.isEmpty()) {
            return new PlanOutcome(PaymentStatus.INSUFFICIENT_FUNDS, 0, List.of(), List.of(), false, false);
        }

        var plan = planOpt.get();
        var work = new ArrayList<SimSlot>(inventory.size());
        for (var s : inventory) work.add(s.copy());

        var consumedMap = new LinkedHashMap<String, Integer>();
        boolean usedNested = false;
        boolean usedConversion = false;

        for (var removal : plan.removals()) {
            var src = sources.get(removal.slot());
            if (src.isNested()) {
                usedNested = true;
                var child = work.get(src.topSlot()).nested.get(src.nestedIndex());
                child.count -= removal.itemCount();
                if (child.count <= 0) {
                    work.get(src.topSlot()).nested.set(src.nestedIndex(), SimSlot.empty());
                }
            } else {
                var s = work.get(src.topSlot());
                s.count -= removal.itemCount();
                if (s.count <= 0) {
                    work.set(src.topSlot(), SimSlot.empty());
                }
            }
            consumedMap.merge(src.itemId(), removal.itemCount(), Integer::sum);
            if (!src.itemId().equals(currencyId)) {
                usedConversion = true;
            }
        }

        var changeItems = decomposeChange(plan.changeUnits(), unitValues);
        if (!changeItems.isEmpty()) {
            usedConversion = true;
        }
        var consumedList = consumedMap.entrySet().stream()
            .map(e -> new ItemAmount(e.getKey(), e.getValue()))
            .toList();

        int missingSlots = computeMissingSlots(work, changeItems, maxStackSizes);
        if (missingSlots > 0) {
            return new PlanOutcome(PaymentStatus.INVENTORY_FULL_FOR_CHANGE, missingSlots, consumedList, changeItems, usedConversion, usedNested);
        }

        // Apply change into 0..35
        int limit = Math.min(MAIN_INVENTORY_SIZE, work.size());
        for (var change : changeItems) {
            int remaining = change.count();
            int maxStack = Math.max(1, maxStackSizes.getOrDefault(change.itemId(), 64));
            for (int slot = 0; slot < limit && remaining > 0; slot++) {
                var s = work.get(slot);
                if (!s.isEmpty() && s.plain && change.itemId().equals(s.itemId)) {
                    int moved = Math.min(remaining, Math.max(0, Math.min(s.maxStackSize, maxStack) - s.count));
                    s.count += moved;
                    remaining -= moved;
                }
            }
            for (int slot = 0; slot < limit && remaining > 0; slot++) {
                if (work.get(slot).isEmpty()) {
                    int moved = Math.min(remaining, maxStack);
                    work.set(slot, SimSlot.item(change.itemId(), moved, maxStack, true));
                    remaining -= moved;
                }
            }
        }

        inventory.clear();
        inventory.addAll(work);
        return new PlanOutcome(PaymentStatus.SUCCESS, 0, consumedList, changeItems, usedConversion, usedNested);
    }
}
