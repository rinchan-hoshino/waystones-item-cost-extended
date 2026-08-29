package dev.rinchan.waystoneemeralds.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmeraldDenominationTest {
    @Test
    void looseEmeraldIsOneUnit() {
        assertEquals(1, EmeraldDenomination.units(true, false));
    }

    @Test
    void emeraldBlockIsNineUnits() {
        assertEquals(9, EmeraldDenomination.units(false, true));
    }

    @Test
    void unrelatedItemIsNotCurrency() {
        assertEquals(0, EmeraldDenomination.units(false, false));
    }
}
