package dev.rinchan.waystoneemeralds.payment;

public final class LegacyPaymentState {
    private int consumedUnits;

    public void recordCharge(boolean paid, boolean creative, int requiredUnits) {
        consumedUnits = paid && !creative && requiredUnits > 0 ? requiredUnits : 0;
    }

    public int takeRefundUnits() {
        int refundUnits = consumedUnits;
        consumedUnits = 0;
        return refundUnits;
    }
}
