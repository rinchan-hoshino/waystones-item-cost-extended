package dev.rinchan.waystonesitemcost.payment;

import dev.rinchan.waystonesitemcost.minecraft.RecipeCurrencies;
import io.github.lightman314.lightmanscurrency.api.capability.money.IMoneyHandler;
import io.github.lightman314.lightmanscurrency.api.capability.money.MoneyHandler;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.types.builtin.coins.CoinContainerMoneyHandler;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.common.attachments.WalletHandler;
import io.github.lightman314.lightmanscurrency.common.items.WalletItem;
import io.github.lightman314.lightmanscurrency.common.items.data.WalletDataWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Uses native Lightman's Currency 1.21.1 coin arithmetic on snapshot copies. */
final class LightmanPayment implements CurrencyPayment {
    private final Player player;
    private final List<ItemStack> inventory;
    private final ItemStack before;
    private ItemStack after;

    LightmanPayment(Player player, List<ItemStack> inventory) {
        this.player = player;
        this.inventory = inventory;
        before = WalletHandler.get(player).getWallet().copy();
        after = before.copy();
    }

    @Override
    public boolean handles(ItemStack currency) {
        return RecipeCurrencies.plain(currency) && CoinAPI.getApi().IsCoin(currency, true);
    }

    private final class Preview {
        final SimpleContainer main = new SimpleContainer(Math.min(36, inventory.size()));
        final ItemStack wallet = after.copy();
        final WalletDataWrapper wrapper = WalletItem.isWallet(wallet) ? new WalletDataWrapper(wallet) : null;
        final net.minecraft.world.Container contents = wrapper == null ? new SimpleContainer(0) : wrapper.getContents();
        final SimpleContainer offhand = new SimpleContainer(inventory.size() > 40 && !inventory.get(40).isEmpty() ? 1 : 0);
        final List<ItemStack> overflow = new ArrayList<>();
        final IMoneyHandler money;

        Preview() {
            for (int slot = 0; slot < main.getContainerSize(); slot++) main.setItem(slot, inventory.get(slot).copy());
            if (offhand.getContainerSize() > 0) offhand.setItem(0, inventory.get(40).copy());
            Consumer<ItemStack> change = stack -> {
                var remaining = main.addItem(stack.copy());
                if (!remaining.isEmpty()) overflow.add(remaining);
            };
            money = MoneyHandler.combine(List.of(
                new CoinContainerMoneyHandler(contents, change),
                new CoinContainerMoneyHandler(main, change),
                new CoinContainerMoneyHandler(offhand, change)
            ));
        }

        void accept() {
            if (wrapper != null) {
                if (WalletItem.getAutoExchange(wallet)) CoinAPI.getApi().CoinExchangeAllUp(contents);
                wrapper.setContents(contents, null);
            }
            for (int slot = 0; slot < main.getContainerSize(); slot++) inventory.set(slot, main.getItem(slot).copy());
            if (offhand.getContainerSize() > 0) inventory.set(40, offhand.getItem(0).copy());
            after = wallet;
        }
    }

    @Override
    public Result chargeDetailed(ItemStack currency, int count) {
        var preview = new Preview();
        var price = CoinValue.fromItemOrValue(currency.getItem(), count, 0);
        if (!preview.money.extractMoney(price, true).isEmpty()) {
            return Result.insufficient();
        }
        ItemStack walletBefore = after.copy();
        if (!preview.money.extractMoney(price, false).isEmpty()) {
            return Result.insufficient();
        }
        if (!preview.overflow.isEmpty()) {
            return Result.inventoryFull(preview.overflow.size());
        }
        preview.accept();
        boolean walletTouched = !ItemStack.matches(walletBefore, after);
        return Result.ok(List.of(currency.copyWithCount(count)), List.of(), walletTouched || preview.wrapper != null);
    }

    @Override
    public int available(ItemStack currency) {
        var unit = CoinValue.fromItemOrValue(currency.getItem(), 1, 0);
        if (unit.getCoreValue() <= 0) return 0;
        var balance = new Preview().money.getStoredMoney().valueOf(unit.getUniqueName());
        return (int) Math.min(Integer.MAX_VALUE, balance.getCoreValue() / unit.getCoreValue());
    }

    private boolean changed() {
        return !ItemStack.matches(before, after);
    }

    @Override
    public boolean matches(boolean original) {
        return !changed() || ItemStack.matches(WalletHandler.get(player).getWallet(), original ? before : after);
    }

    @Override
    public void write(boolean original) {
        if (changed()) WalletHandler.get(player).setWallet((original ? before : after).copy());
    }
}
