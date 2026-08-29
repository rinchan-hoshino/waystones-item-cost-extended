package dev.rinchan.waystoneemeralds.payment;

public final class EmeraldDenomination {
    private static final int EMERALD_VALUE = 1;
    private static final int EMERALD_BLOCK_VALUE = 9;

    private EmeraldDenomination() {
    }

    public static int units(boolean emerald, boolean emeraldBlock) {
        if (emerald) {
            return EMERALD_VALUE;
        }
        if (emeraldBlock) {
            return EMERALD_BLOCK_VALUE;
        }
        return 0;
    }
}
