package dev.rinchan.waystoneemeralds.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EmeraldPaymentTransactionTest {
    @Test
    void consumesMixedDenominationsWithoutCreatingValue() {
        var inventory = inventory(false, slot(1, 9), slot(4, 1), slot(7, 0));
        var planner = plan(new Removal(0, 1), new Removal(1, 1), 0);

        assertTrue(EmeraldPaymentTransaction.consume(inventory, 10, planner));
        assertEquals(3, inventory.count(1));
        assertEquals(7, inventory.count(2));
        assertEquals(10, inventory.initialValue() - inventory.currentValue());
    }

    @Test
    void returnsLooseEmeraldChangeForAnOverpayingBlock() {
        var inventory = inventory(false, slot(1, 9));

        assertTrue(EmeraldPaymentTransaction.consume(inventory, 1, plan(new Removal(0, 1), 8)));
        assertEquals(0, inventory.count(0));
        assertEquals(8, inventory.givenUnits());
        assertEquals(1, inventory.initialValue() - inventory.currentValue());
    }

    @Test
    void insufficientPaymentLeavesEverySlotUntouched() {
        var inventory = inventory(false, slot(1, 9), slot(4, 0));

        assertFalse(EmeraldPaymentTransaction.consume(inventory, 10, noPlan()));
        assertEquals(List.of(1, 4), inventory.counts());
        assertEquals(0, inventory.givenUnits());
    }

    @Test
    void creativePaymentAndRefundNeverMutateInventory() {
        var inventory = inventory(true, slot(1, 9));

        assertTrue(EmeraldPaymentTransaction.consume(inventory, 9, noPlan()));
        EmeraldPaymentTransaction.refund(inventory, 9);
        assertEquals(List.of(1), inventory.counts());
        assertEquals(0, inventory.givenUnits());
    }

    @Test
    void nonCurrencySlotsAreNeverRemoved() {
        var inventory = inventory(false, slot(1, 9), slot(64, 0));

        assertTrue(EmeraldPaymentTransaction.consume(inventory, 9, plan(new Removal(0, 1), 0)));
        assertEquals(64, inventory.count(1));
    }

    @Test
    void availableUnitsSaturatesAtIntegerMaximum() {
        var inventory = inventory(false, slot(Integer.MAX_VALUE, 9), slot(Integer.MAX_VALUE, 1));

        assertEquals(Integer.MAX_VALUE, EmeraldPaymentTransaction.availableUnits(inventory));
    }

    @Test
    void duplicateRemovalFaultIsRejectedWithoutMutation() {
        var inventory = inventory(false, slot(2, 9));
        var bad = plan(new Removal(0, 1), new Removal(0, 1), 8);

        assertFalse(EmeraldPaymentTransaction.consume(inventory, 10, bad));
        assertEquals(List.of(2), inventory.counts());
    }

    @Test
    void underchargeFaultIsRejectedWithoutMutation() {
        var inventory = inventory(false, slot(1, 9));

        assertFalse(EmeraldPaymentTransaction.consume(inventory, 9, plan(new Removal(0, 1), 1)));
        assertEquals(List.of(1), inventory.counts());
    }

    @Test
    void overdrawFaultIsRejectedWithoutMutation() {
        var inventory = inventory(false, slot(1, 9));

        assertFalse(EmeraldPaymentTransaction.consume(inventory, 18, plan(new Removal(0, 2), 0)));
        assertEquals(List.of(1), inventory.counts());
    }

    @Test
    void validPlansConserveValueAcrossDeterministicRandomInventories() {
        long seed = 0x5041494457415953L;
        var random = new Random(seed);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            int blockCount = 1 + random.nextInt(64);
            int emeraldCount = random.nextInt(64);
            int removeBlocks = random.nextInt(blockCount + 1);
            int removeEmeralds = random.nextInt(emeraldCount + 1);
            int removedValue = removeBlocks * 9 + removeEmeralds;
            int change = removedValue == 0 ? 0 : random.nextInt(removedValue + 1);
            int required = removedValue - change;
            var removals = new ArrayList<Removal>();
            if (removeBlocks > 0) {
                removals.add(new Removal(0, removeBlocks));
            }
            if (removeEmeralds > 0) {
                removals.add(new Removal(1, removeEmeralds));
            }
            var inventory = inventory(false, slot(blockCount, 9), slot(emeraldCount, 1));

            assertTrue(
                EmeraldPaymentTransaction.consume(inventory, required, plan(removals, change)),
                "seed=" + seed + ", iteration=" + iteration
            );
            assertEquals(
                required,
                inventory.initialValue() - inventory.currentValue(),
                "seed=" + seed + ", iteration=" + iteration
            );
        }
    }

    @Test
    void refundReturnsExactlyTheRecordedLooseUnits() {
        var inventory = inventory(false, slot(0, 1));

        EmeraldPaymentTransaction.refund(inventory, 17);
        assertEquals(17, inventory.givenUnits());
    }

    private static EmeraldPaymentTransaction.Planner plan(Removal removal, int change) {
        return plan(List.of(removal), change);
    }

    private static EmeraldPaymentTransaction.Planner plan(Removal first, Removal second, int change) {
        return plan(List.of(first, second), change);
    }

    private static EmeraldPaymentTransaction.Planner plan(List<Removal> removals, int change) {
        return (required, stacks) -> Optional.of(new EmeraldPaymentTransaction.Plan(
            removals.stream().map(it -> new EmeraldPaymentTransaction.Removal(it.slot(), it.count())).toList(),
            change
        ));
    }

    private static EmeraldPaymentTransaction.Planner noPlan() {
        return (required, stacks) -> Optional.empty();
    }

    private static Slot slot(int count, int value) {
        return new Slot(count, value);
    }

    private static FakeInventory inventory(boolean creative, Slot... slots) {
        return new FakeInventory(creative, List.of(slots));
    }

    private record Slot(int count, int value) {
    }

    private record Removal(int slot, int count) {
    }

    private static final class FakeInventory implements EmeraldPaymentTransaction.InventoryAccess {
        private final boolean creative;
        private final List<Slot> initial;
        private final List<Integer> counts;
        private int given;

        private FakeInventory(boolean creative, List<Slot> slots) {
            this.creative = creative;
            this.initial = List.copyOf(slots);
            this.counts = new ArrayList<>(slots.stream().map(Slot::count).toList());
        }

        @Override
        public boolean creative() {
            return creative;
        }

        @Override
        public int size() {
            return counts.size();
        }

        @Override
        public int count(int slot) {
            return counts.get(slot);
        }

        @Override
        public int unitValue(int slot) {
            return initial.get(slot).value();
        }

        @Override
        public void remove(int slot, int count) {
            counts.set(slot, counts.get(slot) - count);
        }

        @Override
        public void giveEmeralds(int count) {
            given += count;
        }

        private int initialValue() {
            int value = 0;
            for (int i = 0; i < initial.size(); i++) {
                value += initial.get(i).count() * initial.get(i).value();
            }
            return value;
        }

        private int currentValue() {
            int value = given;
            for (int i = 0; i < counts.size(); i++) {
                value += counts.get(i) * initial.get(i).value();
            }
            return value;
        }

        private int givenUnits() {
            return given;
        }

        private List<Integer> counts() {
            return List.copyOf(counts);
        }
    }
}
