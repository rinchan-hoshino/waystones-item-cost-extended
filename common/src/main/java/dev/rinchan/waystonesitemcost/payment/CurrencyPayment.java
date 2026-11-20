package dev.rinchan.waystonesitemcost.payment;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/** A currency's extra storage (e.g. Lightman's Currency wallet) participates in the same inventory transaction. */
public interface CurrencyPayment {
    CurrencyPayment NONE = new CurrencyPayment() {};

    record Result(
        PaymentStatus status,
        int slotsNeededForChange,
        List<ItemStack> consumed,
        List<ItemStack> change,
        boolean usedWallet
    ) {
        public static Result ok(List<ItemStack> consumed, List<ItemStack> change, boolean usedWallet) {
            return new Result(PaymentStatus.SUCCESS, 0, List.copyOf(consumed), List.copyOf(change), usedWallet);
        }

        public static Result insufficient() {
            return new Result(PaymentStatus.INSUFFICIENT_FUNDS, 0, List.of(), List.of(), false);
        }

        public static Result inventoryFull(int slotsNeeded) {
            return new Result(PaymentStatus.INVENTORY_FULL_FOR_CHANGE, Math.max(1, slotsNeeded), List.of(), List.of(), false);
        }

        public boolean isSuccess() {
            return status == PaymentStatus.SUCCESS;
        }
    }

    default boolean handles(ItemStack currency) {
        return false;
    }

    default Result chargeDetailed(ItemStack currency, int count) {
        return Result.insufficient();
    }

    default int available(ItemStack currency) {
        return 0;
    }

    default boolean matches(boolean before) {
        return true;
    }

    default void write(boolean before) {
    }
}
