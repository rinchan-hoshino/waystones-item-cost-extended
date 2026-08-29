package dev.rinchan.waystoneemeralds.payment;

public final class EmeraldCostAccumulator {
    private int availableUnits;
    private int consumedUnits;

    public EmeraldCostAccumulator(int availableUnits) {
        this.availableUnits = Math.max(0, availableUnits);
    }

    public int simulateConsume(int requestedUnits) {
        int consumed = Math.min(availableUnits, Math.max(0, requestedUnits));
        availableUnits -= consumed;
        consumedUnits += consumed;
        return consumed;
    }

    public int availableUnits() {
        return availableUnits;
    }

    public int consumedUnits() {
        return consumedUnits;
    }
}
