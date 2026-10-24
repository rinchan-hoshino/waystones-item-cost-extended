package dev.rinchan.waystoneemeralds.payment;

import dev.rinchan.rinlib.payment.DenominatedPayment;
import dev.rinchan.waystoneemeralds.minecraft.RecipeCurrencies;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Plans every item charge against one inventory snapshot before changing any real slot. */
public final class ItemPayment {
    private final Player player;
    private final List<ItemStack> before = new ArrayList<>();
    private final List<ItemStack> after = new ArrayList<>();
    private final List<CraftingDenominations.Recipe> recipes;
    private final CurrencyPayment currencyPayment;
    private boolean committed;

    public ItemPayment(Player player) {
        this.player = player;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            before.add(stack.copy());
            after.add(stack.copy());
        }
        recipes = RecipeCurrencies.recipes(player);
        currencyPayment = OptionalCurrencies.open(player, after);
    }

    public boolean charge(ItemStack currency, int count) {
        if (count < 0) return false;
        if (player.getAbilities().instabuild || count == 0) return true;
        if (currencyPayment.handles(currency)) return currencyPayment.charge(currency, count);
        var id = RecipeCurrencies.id(currency.getItem());
        var values = RecipeCurrencies.plain(currency)
            ? CraftingDenominations.resolve(id, recipes).orElse(Map.of(id, 1)) : Map.of(id, 1);
        long required = (long) count * values.get(id);
        if (required > Integer.MAX_VALUE) return false;
        var stacks = new ArrayList<DenominatedPayment.Stack>();
        for (int slot = 0; slot < after.size(); slot++) {
            var stack = after.get(slot);
            boolean exact = RecipeCurrencies.same(currency, stack);
            int units = exact ? values.get(id)
                : RecipeCurrencies.plain(stack) ? values.getOrDefault(RecipeCurrencies.id(stack.getItem()), 0) : 0;
            if (!stack.isEmpty() && units > 0) stacks.add(new DenominatedPayment.Stack(slot, stack.getCount(), units));
        }
        // Prefer small denominations, reducing unnecessary change without changing the amount charged.
        stacks.sort(java.util.Comparator.comparingInt(DenominatedPayment.Stack::unitValue));
        var plan = DenominatedPayment.plan((int) required, stacks);
        if (plan.isEmpty()) return false;
        int change = plan.get().changeUnits();
        var planned = after.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (var removal : plan.get().removals()) planned.get(removal.slot()).shrink(removal.itemCount());
        var denominations = values.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).toList();
        for (var denomination : denominations) {
            int returned = change / denomination.getValue();
            if (returned > 0) {
                var returnedItem = RecipeCurrencies.item(denomination.getKey());
                if (!insert(planned, new ItemStack(returnedItem, returned))) return false;
                change %= denomination.getValue();
            }
        }
        after.clear();
        after.addAll(planned);
        return true;
    }

    public int available(ItemStack currency) {
        if (currencyPayment.handles(currency)) return currencyPayment.available(currency);
        var id = RecipeCurrencies.id(currency.getItem());
        var values = RecipeCurrencies.plain(currency)
            ? CraftingDenominations.resolve(id, recipes).orElse(Map.of(id, 1)) : Map.of(id, 1);
        long total = 0;
        for (var stack : after) {
            int value = RecipeCurrencies.same(currency, stack) ? values.get(id)
                : RecipeCurrencies.plain(stack) ? values.getOrDefault(RecipeCurrencies.id(stack.getItem()), 0) : 0;
            total = Math.min(Integer.MAX_VALUE * (long) values.get(id), total + (long) stack.getCount() * value);
        }
        return (int) (total / values.get(id));
    }

    public int available(java.util.function.Predicate<ItemStack> matcher) {
        long total = 0;
        for (var stack : after) if (matcher.test(stack)) total += stack.getCount();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public boolean charge(java.util.function.Predicate<ItemStack> matcher, int count) {
        if (count < 0) return false;
        if (player.getAbilities().instabuild) return true;
        if (available(matcher) < count) return false;
        for (var stack : after) {
            if (count == 0) break;
            if (matcher.test(stack)) {
                int removed = Math.min(stack.getCount(), count);
                stack.shrink(removed);
                count -= removed;
            }
        }
        return true;
    }

    private static boolean insert(List<ItemStack> inventory, ItemStack change) {
        // Only ordinary inventory slots receive change; armor and offhand are never used as empty storage.
        for (int slot = 0; slot < Math.min(36, inventory.size()) && !change.isEmpty(); slot++) {
            var stack = inventory.get(slot);
            if (!stack.isEmpty() && RecipeCurrencies.same(stack, change)) {
                int moved = Math.min(change.getCount(), Math.max(0, stack.getMaxStackSize() - stack.getCount()));
                stack.grow(moved);
                change.shrink(moved);
            }
        }
        for (int slot = 0; slot < Math.min(36, inventory.size()) && !change.isEmpty(); slot++) {
            if (inventory.get(slot).isEmpty()) {
                int moved = Math.min(change.getCount(), change.getMaxStackSize());
                inventory.set(slot, change.copyWithCount(moved));
                change.shrink(moved);
            }
        }
        return change.isEmpty();
    }

    public boolean commit() {
        if (committed || !matches(before) || !currencyPayment.matches(true)) return false;
        write(after);
        currencyPayment.write(false);
        committed = true;
        return true;
    }

    public boolean rollback() {
        if (!committed || !matches(after) || !currencyPayment.matches(false)) return false;
        write(before);
        currencyPayment.write(true);
        committed = false;
        return true;
    }

    private boolean matches(List<ItemStack> expected) {
        for (int slot = 0; slot < expected.size(); slot++) {
            if (!changed(slot)) continue;
            var actual = player.getInventory().getItem(slot);
            if (actual.getCount() != expected.get(slot).getCount()
                || !RecipeCurrencies.same(actual, expected.get(slot))) return false;
        }
        return true;
    }

    private boolean changed(int slot) {
        return before.get(slot).getCount() != after.get(slot).getCount() || !RecipeCurrencies.same(before.get(slot), after.get(slot));
    }

    private void write(List<ItemStack> stacks) {
        for (int slot = 0; slot < stacks.size(); slot++) if (changed(slot)) player.getInventory().setItem(slot, stacks.get(slot).copy());
        player.getInventory().setChanged();
    }
}
