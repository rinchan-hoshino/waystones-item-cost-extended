package dev.rinchan.waystonesitemcost.payment;

import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

final class OptionalCurrencies {
    private OptionalCurrencies() {}

    static CurrencyPayment open(Player player, List<ItemStack> inventory) {
        return CurrencyPayment.NONE;
    }
}
