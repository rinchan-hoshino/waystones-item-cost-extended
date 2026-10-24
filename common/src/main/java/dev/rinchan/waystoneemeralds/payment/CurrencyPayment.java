package dev.rinchan.waystoneemeralds.payment;

import net.minecraft.world.item.ItemStack;

/** A currency's extra storage participates in the same inventory transaction. */
public interface CurrencyPayment {
    CurrencyPayment NONE = new CurrencyPayment() {};
    default boolean handles(ItemStack currency) { return false; }
    default boolean charge(ItemStack currency, int count) { return false; }
    default int available(ItemStack currency) { return 0; }
    default boolean matches(boolean before) { return true; }
    default void write(boolean before) {}
}
