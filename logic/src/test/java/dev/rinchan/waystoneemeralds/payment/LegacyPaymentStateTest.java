package dev.rinchan.waystoneemeralds.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LegacyPaymentStateTest {
    @Test
    void successfulSurvivalChargeProducesOneRefundReceipt() {
        var state = new LegacyPaymentState();

        state.recordCharge(true, false, 17);
        assertEquals(17, state.takeRefundUnits());
        assertEquals(0, state.takeRefundUnits());
    }

    @Test
    void failedChargeClearsAStaleReceipt() {
        var state = new LegacyPaymentState();

        state.recordCharge(true, false, 9);
        state.recordCharge(false, false, 9);
        assertEquals(0, state.takeRefundUnits());
    }

    @Test
    void creativeChargeNeverProducesAReceipt() {
        var state = new LegacyPaymentState();

        state.recordCharge(true, true, 9);
        assertEquals(0, state.takeRefundUnits());
    }
}
