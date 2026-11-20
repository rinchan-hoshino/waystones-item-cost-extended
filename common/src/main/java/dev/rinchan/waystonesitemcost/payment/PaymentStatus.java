package dev.rinchan.waystonesitemcost.payment;

/** Result status of a payment check or charge. */
public enum PaymentStatus {
    SUCCESS,
    INSUFFICIENT_FUNDS,
    INVENTORY_FULL_FOR_CHANGE
}
