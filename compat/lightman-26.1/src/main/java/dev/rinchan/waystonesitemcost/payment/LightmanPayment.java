package dev.rinchan.waystonesitemcost.payment;

import dev.rinchan.waystonesitemcost.minecraft.RecipeCurrencies;
import io.github.lightman314.lightmanscurrency.api.LCApi;
import io.github.lightman314.lightmanscurrency.api.coins.value.CoinValue;
import io.github.lightman314.lightmanscurrency.api.helpers.interfaces.ISidedContext;
import io.github.lightman314.lightmanscurrency.api.money.OverflowHandler;
import io.github.lightman314.lightmanscurrency.api.money.resource.MoneyResourceHandler;
import io.github.lightman314.lightmanscurrency.api.money.values.MoneyValue;
import io.github.lightman314.lightmanscurrency.core.neoforge.LCDataAttachments;
import io.github.lightman314.lightmanscurrency.features.wallet.WalletAttachment;
import io.github.lightman314.lightmanscurrency.features.wallet.WalletItem;
import io.github.lightman314.lightmanscurrency.features.wallet.WalletStorage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Uses native Lightman's Currency 26.1 coin arithmetic on snapshot copies. */
final class LightmanPayment implements CurrencyPayment {
    private final Player player;
    private final List<ItemStack> inventory;
    private final ItemStack before;
    private ItemStack after;

    LightmanPayment(Player player, List<ItemStack> inventory) {
        this.player = player;
        this.inventory = inventory;
        WalletAttachment attachment = player.getData(LCDataAttachments.WALLET);
        this.before = attachment.getWallet().copy();
        this.after = this.before.copy();
    }

    @Override
    public boolean handles(ItemStack currency) {
        return RecipeCurrencies.plain(currency) && LCApi.getCoinAPI().isCoin(currency, true);
    }

    private final class Preview {
        final SimpleContainer main = new SimpleContainer(Math.min(36, inventory.size()));
        final SimpleContainer walletHolder = new SimpleContainer(1);
        final boolean hasWallet = WalletItem.isWallet(after);
        final SimpleContainer offhand = new SimpleContainer(inventory.size() > 40 && !inventory.get(40).isEmpty() ? 1 : 0);
        final List<ItemStack> overflow = new ArrayList<>();
        final List<MoneyResourceHandler> handlers = new ArrayList<>();
        final WalletStorage walletStorage;

        Preview() {
            for (int slot = 0; slot < main.getContainerSize(); slot++) main.setItem(slot, inventory.get(slot).copy());
            if (offhand.getContainerSize() > 0) offhand.setItem(0, inventory.get(40).copy());
            walletHolder.setItem(0, after.copy());

            ISidedContext sided = ISidedContext.wrap(player);
            OverflowHandler overflowHandler = OverflowHandler.forConsumer(stack -> {
                var remaining = main.addItem(stack.copy());
                if (!remaining.isEmpty()) overflow.add(remaining);
            });

            if (hasWallet) {
                walletStorage = new WalletStorage(ItemAccess.forHandlerIndex(VanillaContainerWrapper.of(walletHolder), 0));
                handlers.add(LCApi.getMoneyAPI().getContainersMoneyHandler(walletStorage, overflowHandler, sided, true));
            } else {
                walletStorage = null;
            }
            handlers.add(LCApi.getMoneyAPI().getContainersMoneyHandler(main, overflowHandler, sided, true));
            if (offhand.getContainerSize() > 0) {
                handlers.add(LCApi.getMoneyAPI().getContainersMoneyHandler(offhand, overflowHandler, sided, true));
            }
        }

        boolean extractAll(MoneyValue price) {
            MoneyValue remaining = price;
            try (var tx = Transaction.openRoot()) {
                for (var handler : handlers) {
                    if (remaining.isEmpty()) break;
                    MoneyValue extracted = handler.extract(remaining, tx);
                    if (!extracted.isEmpty()) {
                        remaining = remaining.subtractValue(extracted);
                    }
                }
                if (remaining.isEmpty() && overflow.isEmpty()) {
                    tx.commit();
                    return true;
                }
                return false;
            }
        }

        long totalInternalValue(MoneyValue unit) {
            long total = 0;
            for (var handler : handlers) {
                var stored = handler.getResource(unit.getKey());
                if (stored != null && !stored.isEmpty()) {
                    total += stored.getInternalValue();
                }
            }
            return total;
        }

        void accept() {
            if (hasWallet && walletStorage != null) {
                ItemStack updatedWallet = walletHolder.getItem(0);
                if (WalletItem.shouldAutoExchange(updatedWallet)) {
                    try (var tx = Transaction.openRoot()) {
                        LCApi.getCoinAPI().exchangeCoinsAllUp(walletStorage, tx);
                        tx.commit();
                    }
                }
                after = walletHolder.getItem(0).copy();
            }
            for (int slot = 0; slot < main.getContainerSize(); slot++) inventory.set(slot, main.getItem(slot).copy());
            if (offhand.getContainerSize() > 0) inventory.set(40, offhand.getItem(0).copy());
        }
    }

    @Override
    public Result chargeDetailed(ItemStack currency, int count) {
        var preview = new Preview();
        var price = CoinValue.fromItemOrValue(currency.getItem(), count, 0);
        if (price.isEmpty()) return Result.insufficient();
        if (preview.totalInternalValue(price) < price.getInternalValue()) {
            return Result.insufficient();
        }
        ItemStack walletBefore = after.copy();
        if (!preview.extractAll(price)) {
            if (!preview.overflow.isEmpty()) {
                return Result.inventoryFull(preview.overflow.size());
            }
            return Result.insufficient();
        }
        preview.accept();
        boolean walletTouched = !ItemStack.matches(walletBefore, after);
        return Result.ok(List.of(currency.copyWithCount(count)), List.of(), walletTouched || preview.hasWallet);
    }

    @Override
    public int available(ItemStack currency) {
        var unit = CoinValue.fromItemOrValue(currency.getItem(), 1, 0);
        if (unit.isEmpty() || unit.getInternalValue() <= 0) return 0;
        long total = new Preview().totalInternalValue(unit);
        return (int) Math.min(Integer.MAX_VALUE, total / unit.getInternalValue());
    }

    private boolean changed() {
        return !ItemStack.matches(before, after);
    }

    @Override
    public boolean matches(boolean original) {
        if (!changed()) return true;
        WalletAttachment attachment = player.getData(LCDataAttachments.WALLET);
        return ItemStack.matches(attachment.getWallet(), original ? before : after);
    }

    @Override
    public void write(boolean original) {
        if (!changed()) return;
        WalletAttachment attachment = player.getData(LCDataAttachments.WALLET);
        attachment.setWallet((original ? before : after).copy());
    }
}
