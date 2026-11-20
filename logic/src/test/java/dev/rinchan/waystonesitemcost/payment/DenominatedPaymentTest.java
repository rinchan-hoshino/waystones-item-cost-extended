package dev.rinchan.waystonesitemcost.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rinchan.waystonesitemcost.payment.DenominatedPayment.Removal;
import dev.rinchan.waystonesitemcost.payment.DenominatedPayment.Stack;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DenominatedPaymentTest {
    @Test
    void plansZeroCostWithoutRemovals() {
        var plan = DenominatedPayment.plan(0, List.of(new Stack(0, 10, 9))).orElseThrow();
        assertTrue(plan.removals().isEmpty());
        assertEquals(0, plan.changeUnits());
    }

    @Test
    void paysWithLargerDenominationAndComputesExactChange() {
        // Need 27 units (3 ingots), have 1 iron block (81 units)
        var plan = DenominatedPayment.plan(27, List.of(new Stack(4, 1, 81))).orElseThrow();
        assertEquals(List.of(new Removal(4, 1)), plan.removals());
        assertEquals(54, plan.changeUnits());
    }

    @Test
    void returnsEmptyWhenFundsInsufficient() {
        var plan = DenominatedPayment.plan(100, List.of(new Stack(0, 1, 81), new Stack(1, 2, 9)));
        assertTrue(plan.isEmpty());
    }

    @Test
    void rejectsNegativeRequiredUnits() {
        assertThrows(IllegalArgumentException.class, () -> DenominatedPayment.plan(-1, List.of()));
    }
}
