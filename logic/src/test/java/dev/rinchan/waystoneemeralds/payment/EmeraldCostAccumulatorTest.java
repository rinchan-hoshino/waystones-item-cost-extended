package dev.rinchan.waystoneemeralds.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmeraldCostAccumulatorTest {
    @Test
    void accumulatesMultipleAffordableCostsForOneCommit() {
        var accumulator = new EmeraldCostAccumulator(15);

        assertEquals(9, accumulator.simulateConsume(9));
        assertEquals(6, accumulator.simulateConsume(6));
        assertEquals(15, accumulator.consumedUnits());
        assertEquals(0, accumulator.availableUnits());
    }

    @Test
    void reportsOnlyTheCurrentRequestsAffordableAmount() {
        var accumulator = new EmeraldCostAccumulator(10);

        assertEquals(8, accumulator.simulateConsume(8));
        assertEquals(2, accumulator.simulateConsume(8));
        assertEquals(10, accumulator.consumedUnits());
    }

    @Test
    void negativeRequestFaultCannotCreateAvailability() {
        var accumulator = new EmeraldCostAccumulator(10);

        assertEquals(0, accumulator.simulateConsume(-5));
        assertEquals(10, accumulator.availableUnits());
        assertEquals(0, accumulator.consumedUnits());
    }
}
