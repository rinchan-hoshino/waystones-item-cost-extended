package dev.rinchan.waystoneemeralds.payment;

import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class OptionalCurrencies {
    private OptionalCurrencies() {}
    public static CurrencyPayment open(Player player, List<ItemStack> inventory) {
        return CurrencyPayment.NONE;
    }
}
