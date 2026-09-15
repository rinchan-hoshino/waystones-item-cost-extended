package dev.rinchan.waystoneemeralds.minecraft;

import dev.rinchan.waystoneemeralds.payment.CraftingDenominations;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.rinchan.waystoneemeralds.payment.PaymentConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Normalizes active crafting recipes; Minecraft API differences live in RecipeAccess. */
public final class RecipeCurrencies {
    private RecipeCurrencies() {}
    private static final Map<Object, Cache> cache = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Map<Object, List<CraftingDenominations.Recipe>> client = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private record Cache(Object source, List<CraftingDenominations.Recipe> recipes) {}
    public static void receive(Player player, List<CraftingDenominations.Recipe> recipes) { client.put(player.level(), List.copyOf(recipes)); }

    public record Recipe(ItemStack result, List<ItemStack[]> ingredients) {}

    public static List<CraftingDenominations.Recipe> recipes(Player player) {
        if (player.level().isClientSide()) return client.getOrDefault(player.level(), List.of());
        if (!PaymentConfig.recipePayments()) return List.of();
        var source = RecipeAccess.all(player);
        var previous = cache.get(player.level());
        if (previous != null && previous.source() == source) return previous.recipes();
        var result = new ArrayList<CraftingDenominations.Recipe>();
        for (var holder : source) {
            var recipe = RecipeAccess.read(holder, player);
            if (recipe == null || !plain(recipe.result())) continue;
            Item input = null;
            int count = 0;
            boolean valid = true;
            for (var choices : recipe.ingredients()) {
                if (choices.length == 0) continue;
                if (choices.length != 1 || !plain(choices[0])
                    || (input != null && input != choices[0].getItem())) {
                    valid = false;
                    break;
                }
                input = choices[0].getItem();
                count++;
            }
            if (valid && input != null) {
                result.add(new CraftingDenominations.Recipe(id(input), count, id(recipe.result().getItem()), recipe.result().getCount()));
            }
        }
        var immutable = List.copyOf(result);
        cache.put(player.level(), new Cache(source, immutable));
        return immutable;
    }

    public static boolean same(ItemStack left, ItemStack right) { return RecipeAccess.same(left, right); }
    public static boolean plain(ItemStack stack) {
        return !stack.isEmpty() && same(stack, new ItemStack(stack.getItem()));
    }
    public static Item item(String id) { return RecipeAccess.item(id); }
    public static String id(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }
}
