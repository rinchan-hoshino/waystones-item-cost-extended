package dev.rinchan.waystonesitemcost.payment;

import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.minecraft.RecipeAccess;
import dev.rinchan.waystonesitemcost.minecraft.RecipeCurrencies;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Plans every item charge against one inventory snapshot before changing any real slot.
 * Supports reversible crafting denominations, nested containers (Bundles & Shulker Boxes),
 * optional external wallets (Lightman's Currency), and exact missing-slot diagnostics.
 */
public final class ItemPayment {
    private static final int MAIN_INVENTORY_SIZE = ChangeSlotPlanner.MAIN_INVENTORY_SIZE;

    private final Player player;
    private final List<ItemStack> before = new ArrayList<>();
    private final List<ItemStack> after = new ArrayList<>();
    private final List<CraftingDenominations.Recipe> recipes;
    private final CurrencyPayment currencyPayment;
    private boolean committed;

    // Cumulative diagnostics for UI tooltips and rejection messages
    private PaymentStatus status = PaymentStatus.SUCCESS;
    private int slotsNeededForChange = 0;
    private boolean usedConversion = false;
    private boolean usedWallet = false;
    private boolean usedNestedContainer = false;
    private final Map<String, ItemStack> consumedTotals = new LinkedHashMap<>();
    private final Map<String, ItemStack> changeTotals = new LinkedHashMap<>();

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

    public PaymentStatus status() {
        return status;
    }

    public int slotsNeededForChange() {
        return slotsNeededForChange;
    }

    public boolean usedConversion() {
        return usedConversion;
    }

    public boolean usedWallet() {
        return usedWallet;
    }

    public boolean usedNestedContainer() {
        return usedNestedContainer;
    }

    public List<ItemStack> consumedSummary() {
        return List.copyOf(consumedTotals.values());
    }

    public List<ItemStack> changeSummary() {
        return List.copyOf(changeTotals.values());
    }

    public boolean hasSpecialBreakdown() {
        return usedConversion || usedWallet || usedNestedContainer || !changeTotals.isEmpty();
    }

    private void recordAmount(Map<String, ItemStack> target, ItemStack template, int count) {
        if (count <= 0 || template.isEmpty()) return;
        String key = RecipeCurrencies.id(template.getItem());
        var existing = target.get(key);
        if (existing == null) {
            target.put(key, template.copyWithCount(count));
        } else {
            existing.grow(count);
        }
    }

    public boolean charge(ItemStack currency, int count) {
        if (count < 0) {
            status = PaymentStatus.INSUFFICIENT_FUNDS;
            return false;
        }
        if (player.getAbilities().instabuild || count == 0) return true;
        if (currencyPayment.handles(currency)) {
            var res = currencyPayment.chargeDetailed(currency, count);
            if (!res.isSuccess()) {
                status = res.status();
                if (res.status() == PaymentStatus.INVENTORY_FULL_FOR_CHANGE) {
                    slotsNeededForChange = Math.max(slotsNeededForChange, res.slotsNeededForChange());
                }
                return false;
            }
            if (res.usedWallet()) usedWallet = true;
            for (var c : res.consumed()) recordAmount(consumedTotals, c, c.getCount());
            for (var ch : res.change()) recordAmount(changeTotals, ch, ch.getCount());
            return true;
        }

        var id = RecipeCurrencies.id(currency.getItem());
        var values = RecipeCurrencies.plain(currency) && PaymentConfig.recipePayments()
            ? CraftingDenominations.resolve(id, recipes).orElse(Map.of(id, 1))
            : Map.of(id, 1);
        long required = (long) count * values.get(id);
        if (required > Integer.MAX_VALUE) {
            status = PaymentStatus.INSUFFICIENT_FUNDS;
            return false;
        }

        boolean searchNested = PaymentConfig.searchNestedContainers();
        record StackSource(int topSlot, int nestedIndex, ItemStack stack, int unitValue, boolean isNested) {}
        var sources = new ArrayList<StackSource>();

        for (int slot = 0; slot < after.size(); slot++) {
            var stack = after.get(slot);
            if (stack.isEmpty()) continue;
            int units = matchUnits(currency, id, values, stack);
            if (units > 0) {
                sources.add(new StackSource(slot, -1, stack, units, false));
            }
            if (searchNested && (slot < MAIN_INVENTORY_SIZE || slot == 40)) {
                var nestedItems = RecipeAccess.readNestedItems(stack);
                if (nestedItems != null && !nestedItems.isEmpty()) {
                    for (int ni = 0; ni < nestedItems.size(); ni++) {
                        var child = nestedItems.get(ni);
                        if (child.isEmpty()) continue;
                        int childUnits = matchUnits(currency, id, values, child);
                        if (childUnits > 0) {
                            sources.add(new StackSource(slot, ni, child, childUnits, true));
                        }
                    }
                }
            }
        }

        // Prefer smaller denominations first, and direct inventory over nested containers at the same value.
        sources.sort(Comparator.comparingInt(StackSource::unitValue).thenComparing(StackSource::isNested));

        var stacks = new ArrayList<DenominatedPayment.Stack>(sources.size());
        for (int idx = 0; idx < sources.size(); idx++) {
            var src = sources.get(idx);
            stacks.add(new DenominatedPayment.Stack(idx, src.stack().getCount(), src.unitValue()));
        }

        var planOpt = DenominatedPayment.plan((int) required, stacks);
        if (planOpt.isEmpty()) {
            status = PaymentStatus.INSUFFICIENT_FUNDS;
            return false;
        }

        var plan = planOpt.get();
        int changeUnits = plan.changeUnits();
        var planned = new ArrayList<ItemStack>(after.size());
        for (var s : after) planned.add(s.copy());

        // Track modified nested containers
        var modifiedNested = new LinkedHashMap<Integer, List<ItemStack>>();
        boolean stepNested = false;
        boolean stepConversion = false;
        var stepConsumed = new ArrayList<ItemStack>();

        for (var removal : plan.removals()) {
            var src = sources.get(removal.slot());
            if (src.isNested()) {
                stepNested = true;
                var list = modifiedNested.computeIfAbsent(src.topSlot(), slot -> {
                    var read = RecipeAccess.readNestedItems(planned.get(slot));
                    var copy = new ArrayList<ItemStack>(read != null ? read.size() : 0);
                    if (read != null) {
                        for (var item : read) copy.add(item.copy());
                    }
                    return copy;
                });
                list.get(src.nestedIndex()).shrink(removal.itemCount());
            } else {
                planned.get(src.topSlot()).shrink(removal.itemCount());
            }
            stepConsumed.add(src.stack().copyWithCount(removal.itemCount()));
            if (!RecipeCurrencies.id(src.stack().getItem()).equals(id)) {
                stepConversion = true;
            }
        }

        for (var entry : modifiedNested.entrySet()) {
            RecipeAccess.writeNestedItems(planned.get(entry.getKey()), entry.getValue());
        }

        // Decompose change greedily from largest to smallest denomination
        var changeStacks = new ArrayList<ItemStack>();
        var denominations = values.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .toList();
        int remainingChange = changeUnits;
        for (var denom : denominations) {
            int returned = remainingChange / denom.getValue();
            if (returned > 0) {
                var returnedItem = RecipeCurrencies.item(denom.getKey());
                changeStacks.add(new ItemStack(returnedItem, returned));
                remainingChange %= denom.getValue();
                stepConversion = true;
            }
        }

        // Check if all change fits into ordinary slots 0..35; if not, compute exact missing slots
        var insertAttempt = new ArrayList<ItemStack>(planned.size());
        for (var s : planned) insertAttempt.add(s.copy());
        int missingSlots = insertAndCountMissingSlots(insertAttempt, changeStacks);
        if (missingSlots > 0) {
            status = PaymentStatus.INVENTORY_FULL_FOR_CHANGE;
            slotsNeededForChange = Math.max(slotsNeededForChange, missingSlots);
            return false;
        }

        after.clear();
        after.addAll(insertAttempt);
        if (stepNested) usedNestedContainer = true;
        if (stepConversion) usedConversion = true;
        for (var c : stepConsumed) recordAmount(consumedTotals, c, c.getCount());
        for (var ch : changeStacks) recordAmount(changeTotals, ch, ch.getCount());
        return true;
    }

    private static int matchUnits(ItemStack currency, String currencyId, Map<String, Integer> values, ItemStack candidate) {
        if (RecipeCurrencies.same(currency, candidate)) {
            return values.get(currencyId);
        }
        if (RecipeCurrencies.plain(candidate)) {
            return values.getOrDefault(RecipeCurrencies.id(candidate.getItem()), 0);
        }
        return 0;
    }

    public int available(ItemStack currency) {
        if (currencyPayment.handles(currency)) return currencyPayment.available(currency);
        var id = RecipeCurrencies.id(currency.getItem());
        var values = RecipeCurrencies.plain(currency) && PaymentConfig.recipePayments()
            ? CraftingDenominations.resolve(id, recipes).orElse(Map.of(id, 1))
            : Map.of(id, 1);
        long total = 0;
        long cap = Integer.MAX_VALUE * (long) values.get(id);
        boolean searchNested = PaymentConfig.searchNestedContainers();
        for (int slot = 0; slot < after.size(); slot++) {
            var stack = after.get(slot);
            if (stack.isEmpty()) continue;
            int value = matchUnits(currency, id, values, stack);
            if (value > 0) {
                total = Math.min(cap, total + (long) stack.getCount() * value);
            }
            if (searchNested && (slot < MAIN_INVENTORY_SIZE || slot == 40)) {
                var nested = RecipeAccess.readNestedItems(stack);
                if (nested != null) {
                    for (var child : nested) {
                        if (child.isEmpty()) continue;
                        int childVal = matchUnits(currency, id, values, child);
                        if (childVal > 0) {
                            total = Math.min(cap, total + (long) child.getCount() * childVal);
                        }
                    }
                }
            }
        }
        return (int) (total / values.get(id));
    }

    public int available(Predicate<ItemStack> matcher) {
        long total = 0;
        boolean searchNested = PaymentConfig.searchNestedContainers();
        for (int slot = 0; slot < after.size(); slot++) {
            var stack = after.get(slot);
            if (stack.isEmpty()) continue;
            if (matcher.test(stack)) total += stack.getCount();
            if (searchNested && (slot < MAIN_INVENTORY_SIZE || slot == 40)) {
                var nested = RecipeAccess.readNestedItems(stack);
                if (nested != null) {
                    for (var child : nested) {
                        if (!child.isEmpty() && matcher.test(child)) total += child.getCount();
                    }
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public boolean charge(Predicate<ItemStack> matcher, int count) {
        if (count < 0) {
            status = PaymentStatus.INSUFFICIENT_FUNDS;
            return false;
        }
        if (player.getAbilities().instabuild || count == 0) return true;
        if (available(matcher) < count) {
            status = PaymentStatus.INSUFFICIENT_FUNDS;
            return false;
        }
        int remaining = count;
        for (int slot = 0; slot < after.size() && remaining > 0; slot++) {
            var stack = after.get(slot);
            if (!stack.isEmpty() && matcher.test(stack)) {
                int removed = Math.min(stack.getCount(), remaining);
                recordAmount(consumedTotals, stack, removed);
                stack.shrink(removed);
                remaining -= removed;
            }
        }
        if (remaining > 0 && PaymentConfig.searchNestedContainers()) {
            for (int slot = 0; slot < after.size() && remaining > 0; slot++) {
                if (slot >= MAIN_INVENTORY_SIZE && slot != 40) continue;
                var container = after.get(slot);
                var nested = RecipeAccess.readNestedItems(container);
                if (nested == null || nested.isEmpty()) continue;
                boolean modified = false;
                var copy = new ArrayList<ItemStack>(nested.size());
                for (var child : nested) copy.add(child.copy());
                for (var child : copy) {
                    if (remaining == 0) break;
                    if (!child.isEmpty() && matcher.test(child)) {
                        int removed = Math.min(child.getCount(), remaining);
                        recordAmount(consumedTotals, child, removed);
                        child.shrink(removed);
                        remaining -= removed;
                        modified = true;
                        usedNestedContainer = true;
                    }
                }
                if (modified) {
                    RecipeAccess.writeNestedItems(container, copy);
                }
            }
        }
        return remaining == 0;
    }

    /**
     * Inserts change into ordinary inventory slots (0..35) ONLY.
     * Armor (36..39) and offhand (40) are never used.
     * Returns 0 if all change fit, or the exact number of additional empty slots needed.
     */
    public static int insertAndCountMissingSlots(List<ItemStack> inventory, List<ItemStack> changeItems) {
        int limit = Math.min(MAIN_INVENTORY_SIZE, inventory.size());
        int missingSlots = 0;
        for (var original : changeItems) {
            var change = original.copy();
            int maxStack = Math.max(1, change.getMaxStackSize());
            for (int slot = 0; slot < limit && !change.isEmpty(); slot++) {
                var stack = inventory.get(slot);
                if (!stack.isEmpty() && RecipeCurrencies.same(stack, change)) {
                    int moved = Math.min(change.getCount(), Math.max(0, stack.getMaxStackSize() - stack.getCount()));
                    stack.grow(moved);
                    change.shrink(moved);
                }
            }
            for (int slot = 0; slot < limit && !change.isEmpty(); slot++) {
                if (inventory.get(slot).isEmpty()) {
                    int moved = Math.min(change.getCount(), maxStack);
                    inventory.set(slot, change.copyWithCount(moved));
                    change.shrink(moved);
                }
            }
            if (!change.isEmpty()) {
                missingSlots += (change.getCount() + maxStack - 1) / maxStack;
            }
        }
        return missingSlots;
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
        return before.get(slot).getCount() != after.get(slot).getCount()
            || !RecipeCurrencies.same(before.get(slot), after.get(slot));
    }

    private void write(List<ItemStack> stacks) {
        for (int slot = 0; slot < stacks.size(); slot++) {
            if (changed(slot)) player.getInventory().setItem(slot, stacks.get(slot).copy());
        }
        player.getInventory().setChanged();
    }
}
